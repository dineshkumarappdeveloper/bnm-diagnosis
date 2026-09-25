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
    /** Swapped in tests so a run can be driven without a socket. */
    private val runner: (SimState, ForwardingSendListener) -> Unit = { state, listener ->
        Sender(state.form.toOptions(), listener).run()
    },
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
    /** A one-line answer to the last thing the engineer pressed. */
    var status by mutableStateOf<String?>(null)

    private var worker: Thread? = null

    val presetPath: String get() = store.path

    // ── the form ──

    fun edit(change: (SimForm) -> SimForm) {
        form = change(form)
        // A form change invalidates the last link test: the address it answered
        // about may no longer be the one in the boxes.
        linkResult = null
    }

    fun refreshSerialPorts() {
        serialPorts = LinkTest.serialPorts()
        status = if (serialPorts.isEmpty())
            "No serial ports on this machine. Plug the USB adapter in and press Refresh."
        else "Serial ports: ${serialPorts.joinToString(", ")}"
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
        Thread({ answer.trySend(LinkTest.check(snapshot)) }, "analyzer-sim-linktest")
            .apply { isDaemon = true }.start()
        scope.launch {
            linkResult = answer.receive()
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
        val thread = Thread({
            try {
                runner(this, listener)
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
            running = false
            worker = null
            // The id advances once per RUN, not once per sample: five samples
            // two seconds apart are five accessions, and the sixth run starts
            // after them.
            if (!stopping) form = form.afterRun()
            stopping = false
        }
    }

    /**
     * Interrupt the sender. It lands at the next sleep between samples, so a
     * single sample already waiting out its ACK timeout finishes first — the
     * button says "Stopping…" rather than pretending otherwise.
     */
    fun stop() {
        if (!running) return
        stopping = true
        worker?.interrupt()
    }

    /** Replay events into the transcript — the render tests' way in. */
    internal fun replay(vararg events: SimEvent) {
        for (e in events) blocks = applyEvent(blocks, e)
    }
}
