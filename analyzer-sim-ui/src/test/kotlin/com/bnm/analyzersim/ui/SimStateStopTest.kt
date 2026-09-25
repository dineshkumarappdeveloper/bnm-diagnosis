package com.bnm.analyzersim.ui

import com.bnm.analyzersim.AnalyzerTransport
import com.bnm.analyzersim.SimMllp
import com.bnm.analyzersim.Sender
import com.bnm.analyzersim.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Stop button, through the window's own path.
 *
 * `stop()` used to be `worker.interrupt()` and nothing else, and the only
 * interruptible point in a run was the sleep between samples — which at the
 * form's default "Seconds apart 0" never happens. So Stop on a 50-sample run
 * sent all 50 while the button read "Stopping…", then closed the transcript with
 * a green "Done — 50 sample(s) sent."
 */
class SimStateStopTest {

    /** Counts frames and calls the test back on each one. */
    private class Gate(val ack: ByteArray? = null, val onWrite: (Int) -> Unit = {}) : AnalyzerTransport {
        val writes = AtomicInteger(0)
        override val describe = "gate"
        override fun write(bytes: ByteArray) = onWrite(writes.incrementAndGet())
        override fun readReply(timeoutMs: Long) = ack ?: ByteArray(0)
        override fun close() {}
        fun factory() = object : TransportFactory {
            override val perSample = true
            override val describe = "gate"
            override fun open(): AnalyzerTransport = this@Gate
        }
    }

    private companion object {
        /** A real accept, so the happy-path run finishes green rather than
         *  reporting every sample as 'no ACK'. */
        val GOOD_ACK: ByteArray = SimMllp.wrap(
            "MSH|^~\\&|BNM Lab||BC-5130|Mindray|20260101090001||ACK^R01|A1|P|2.3.1\rMSA|AA|X\r")
    }

    private fun await(what: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!until() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(until(), "timed out waiting for $what")
    }

    @Test
    fun `Stop on a back-to-back run really stops it, and the transcript says so`() {
        lateinit var state: SimState
        lateinit var gate: Gate
        gate = Gate(onWrite = { n -> if (n == 3) state.stop() })
        state = SimState(
            PresetStore(File(System.getProperty("java.io.tmpdir"), "bnm-sim-stop-${System.nanoTime()}")),
            CoroutineScope(Dispatchers.Unconfined),
            runner = { form, listener ->
                Sender(form.toOptions(), listener, transportOverride = gate.factory())
            },
        )
        // The form's own defaults: 0 seconds apart is what an engineer gets.
        state.edit { it.copy(count = "200", sampleId = "BNMTEST-0001") }
        assertEquals("0", state.form.intervalSeconds)

        state.send()
        await("the run to end") { !state.running }

        assertTrue(gate.writes.get() < 200,
            "every sample went out anyway — Stop did nothing (${gate.writes.get()} sent)")
        val text = transcriptText(state.blocks)
        assertTrue(text.contains("Stopped"), "the transcript does not say it stopped:\n$text")
        assertTrue(!text.contains("Done —"), "a stopped run claimed it finished:\n$text")
    }

    @Test
    fun `a stopped run leaves the id box where it was, so nothing is skipped`() {
        lateinit var state: SimState
        lateinit var gate: Gate
        gate = Gate(onWrite = { n -> if (n == 2) state.stop() })
        state = SimState(
            PresetStore(File(System.getProperty("java.io.tmpdir"), "bnm-sim-stop2-${System.nanoTime()}")),
            CoroutineScope(Dispatchers.Unconfined),
            runner = { form, listener ->
                Sender(form.toOptions(), listener, transportOverride = gate.factory())
            },
        )
        state.edit { it.copy(count = "50", sampleId = "SIM-0001", autoIncrementId = true) }
        state.send()
        await("the run to end") { !state.running }
        assertEquals("SIM-0001", state.form.sampleId,
            "a stopped run advanced the box past accessions it never sent")
        assertTrue(!state.stopping, "the button is still stuck on Stopping…")
    }

    @Test
    fun `a run nobody stops advances the box past the whole batch`() {
        val gate = Gate(ack = GOOD_ACK)
        val state = SimState(
            PresetStore(File(System.getProperty("java.io.tmpdir"), "bnm-sim-stop3-${System.nanoTime()}")),
            CoroutineScope(Dispatchers.Unconfined),
            runner = { form, listener ->
                Sender(form.toOptions(), listener, transportOverride = gate.factory())
            },
        )
        state.edit { it.copy(count = "5", sampleId = "SIM-0001", autoIncrementId = true) }
        state.send()
        await("the run to end") { !state.running }
        assertEquals(5, gate.writes.get())
        assertEquals("SIM-0006", state.form.sampleId)
        assertTrue(transcriptText(state.blocks).contains("Done — 5 sample(s) sent."),
            transcriptText(state.blocks))
    }
}
