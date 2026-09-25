package com.bnm.analyzersim.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bnm.analyzersim.Sender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Everything the window remembers, and the one place a run is started.
 *
 * The sending itself happens on a plain daemon [Thread], not a coroutine: the
 * core is blocking by design — it opens sockets, waits out ACK timeouts and
 * sleeps between samples, exactly as an analyzer does — and a thread can be
 * INTERRUPTED, which is what makes Stop work at all. Events come back through
 * an unbounded channel and are folded into the transcript by [scope], which the
 * composition owns, so every Compose state write happens on the UI thread and
 * nothing the network does can make the window stutter.
 */
class SimState(
    private val store: PresetStore,
    private val scope: CoroutineScope,
    /**
     * Builds the run. Swapped in tests so one can be driven without a socket.
     *
     * Returns the [Sender] rather than running it, so [stop] has something to
     * call [Sender.cancel] on — and it is built on the UI thread, before the
     * worker starts, so a Stop pressed immediately cannot race a null handle.
     */
    private val runner: (SimForm, ForwardingSendListener) -> Sender = { form, listener ->
        Sender(form.toOptions(), listener)
    },
    /**
     * The blocking link probe. Swapped in tests so they can raise the failures a
     * client's PC raises — including the Errors that used to leave the button a
     * disabled spinner for the rest of the session.
     */
    private val probe: (SimForm) -> LinkTestResult = LinkTest::check,
    /** The blocking serial-port enumeration, swapped for the same reason. */
    private val ports: () -> List<String> = LinkTest::serialPorts,
) {
    var form by mutableStateOf(SimForm())
    var blocks by mutableStateOf(emptyList<TranscriptBlock>())
        private set
    var running by mutableStateOf(false)
        private set
    var stopping by mutableStateOf(false)
        private set

    var faultsExpanded by mutableStateOf(false)
    var presets by mutableStateOf(store.load())
        private set
    var serialPorts by mutableStateOf(emptyList<String>())
        private set
    var linkResult by mutableStateOf<LinkTestResult?>(null)
        private set
    var testingLink by mutableStateOf(false)
        private set
    /** True while the serial ports are being enumerated off the UI thread. */
    var scanningPorts by mutableStateOf(false)
        private set
    /** A one-line answer to the last thing the engineer pressed. */
    var status by mutableStateOf<String?>(null)

    private var worker: Thread? = null
    private var sender: Sender? = null

    val presetPath: String get() = store.path

    // ── the form ──

    fun edit(change: (SimForm) -> SimForm) {
        form = change(form)
        // A form change invalidates the last link test: the address it answered
        // about may no longer be the one in the boxes.
        linkResult = null
    }

    /**
     * Enumerate the serial ports, OFF the UI thread.
     *
     * jSerialComm's first call extracts its native library into the temp
     * directory and then walks the OS device tree. On a client's Windows laptop
     * — antivirus watching %TEMP%, or a wedged USB-serial driver — that is
     * seconds, and this used to run inside the composition, BEFORE the window
     * existed. The engineer double-clicked an installer icon that drew nothing
     * and started the app a second time. The Mindray/TCP user, who is the
     * common case, was paying it for a list they never open.
     *
     * [announce] is false for the first scan at launch: the status line should
     * say what the window is for, not report on a list nobody asked for.
     */
    fun refreshSerialPorts(announce: Boolean = true) {
        if (scanningPorts) return
        scanningPorts = true
        val answer = Channel<List<String>>(1)
        Thread({
            try {
                answer.trySend(ports())
            } finally {
                answer.close()
            }
        }, "analyzer-sim-ports").apply { isDaemon = true }.start()
        scope.launch {
            serialPorts = answer.receiveCatching().getOrNull().orEmpty()
            if (announce) status = if (serialPorts.isEmpty())
                "No serial ports on this machine. Plug the USB adapter in and press Refresh."
            else "Serial ports: ${serialPorts.joinToString(", ")}"
            // Last, so that "not scanning" means everything it settles is settled.
            scanningPorts = false
        }
    }

    // ── presets ──

    fun loadPreset(preset: PresetJson) {
        form = preset.toForm()
        linkResult = null
        status = "Loaded \"${preset.name}\"."
    }

    fun savePreset(name: String) {
        if (name.isBlank()) return
        presets = store.save(name, form)
        status = "Saved \"${name.trim()}\" to $presetPath"
    }

    fun deletePreset(name: String) {
        presets = store.delete(name)
        status = "Deleted \"$name\"."
    }

    // ── the link ──

    fun testLink() {
        if (testingLink) return
        testingLink = true
        linkResult = null
        status = null
        val snapshot = form
        val answer = Channel<LinkTestResult>(1)
        Thread({
            // Throwable, not Exception, and a finally that closes the channel.
            // jSerialComm's static initialiser raises an ERROR when it cannot
            // unpack its native library — a locked-down %TEMP%, antivirus
            // quarantine, a jlink image missing jdk.unsupported — and an Error
            // escaping here used to kill this thread before it answered. The
            // receive below then waited forever, the button stayed a disabled
            // spinner for the rest of the session, and the engineer was told
            // nothing at all, on exactly the machine where the link test is
            // the thing they need most.
            try {
                answer.trySend(probe(snapshot))
            } catch (t: Throwable) {
                answer.trySend(LinkTestResult(false,
                    "The link test could not run on this machine: " +
                        (t.message ?: t::class.java.simpleName)))
            } finally {
                answer.close()
            }
        }, "analyzer-sim-linktest").apply { isDaemon = true }.start()
        scope.launch {
            // receiveCatching: a closed-without-a-value channel still has to
            // clear the spinner, or the button is dead until the app restarts.
            linkResult = answer.receiveCatching().getOrNull()
                ?: LinkTestResult(false, "The link test ended without an answer. Press it again.")
            testingLink = false
        }
    }

    // ── the run ──

    fun send() {
        if (running) return
        val problems = form.problems()
        if (problems.isNotEmpty()) {
            status = problems.first().message
            return
        }
        status = null
        linkResult = null
        blocks = emptyList()
        running = true
        stopping = false

        val events = Channel<SimEvent>(Channel.UNLIMITED)
        val listener = ForwardingSendListener { events.trySend(it) }
        // Built here, on the UI thread: Stop needs a handle on it, and building
        // it inside the worker would leave a window in which Stop had nothing
        // to cancel. The constructor opens nothing — run() does.
        val run = runner(form, listener)
        sender = run
        val thread = Thread({
            try {
                run.run()
            } finally {
                events.close()
            }
        }, "analyzer-sim-send").apply { isDaemon = true }
        worker = thread
        thread.start()

        scope.launch {
            for (event in events) {
                // An interrupted sleep is not a fault when the engineer is the
                // one who pressed Stop; the summary block says so instead.
                if (stopping && event is SimEvent.Failed) continue
                blocks = applyEvent(blocks, event)
            }
            worker = null
            sender = null
            // The id advances per SAMPLE inside the run (Options.sampleIds);
            // this moves the box PAST the whole batch, so the next run starts
            // at the sixth accession rather than back on top of the second.
            // A stopped run advances nothing: it did not send the batch.
            if (!stopping) form = form.afterRun()
            stopping = false
            // Last, so "not running" means the transcript, the id box and the
            // buttons have all already settled.
            running = false
        }
    }

    /**
     * Call the run off.
     *
     * Both halves matter. [Sender.cancel] sets a flag the sender checks between
     * samples and between write chunks — the only thing that works at the
     * default interval of 0, where there is no sleep for an interrupt to land
     * on; the interrupt then wakes a sender that IS sleeping out an interval so
     * it does not sit there until the next tick. The sample already on the wire
     * finishes either way, which is what "Stopping…" on the button means.
     */
    fun stop() {
        if (!running) return
        stopping = true
        sender?.cancel()
        worker?.interrupt()
    }

    /** Replay events into the transcript — the render tests' way in. */
    internal fun replay(vararg events: SimEvent) {
        for (e in events) blocks = applyEvent(blocks, e)
    }
}
