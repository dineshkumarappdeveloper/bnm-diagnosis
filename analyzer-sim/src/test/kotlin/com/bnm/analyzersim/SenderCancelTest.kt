package com.bnm.analyzersim

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stop has to actually stop.
 *
 * It used to be nothing but `Thread.interrupt()`, and the ONLY interruptible
 * point in a sequential run was the sleep between samples — which at the
 * default interval of 0 never happens. So pressing Stop on a 50-sample run sent
 * all 50 while the button read "Stopping…", and then reported "Done — 50
 * sample(s) sent." The one control whose job is to keep invented results out of
 * a lab did nothing, and said it had succeeded.
 */
class SenderCancelTest {

    /** Counts frames and lets the test block the sender mid-run. */
    private class Gate(private val onWrite: (Int) -> Unit = {}) : AnalyzerTransport {
        val writes = AtomicInteger(0)
        override val describe = "gate"
        override fun write(bytes: ByteArray) = onWrite(writes.incrementAndGet())
        override fun readReply(timeoutMs: Long): ByteArray = ByteArray(0)
        override fun close() {}

        fun factory() = object : TransportFactory {
            override val perSample = true
            override val describe = "gate"
            override fun open(): AnalyzerTransport = this@Gate
        }
    }

    /** The finish event, whatever shape the run took. */
    private class Finish : SendListener {
        var summary: RunFinished? = null
        val failures = mutableListOf<Failure>()
        override fun runStarted(run: RunStart) = Unit
        override fun sampleStarted(sample: SampleStarted) = Unit
        override fun bytesSent(sent: BytesSent) = Unit
        override fun ackReceived(ack: AckOutcome) = Unit
        override fun failed(failure: Failure) { synchronized(failures) { failures += failure } }
        override fun note(line: String) = Unit
        override fun detail(line: String) = Unit
        override fun finished(summary: RunFinished) { this.summary = summary }
    }

    @Test
    fun `stop halts a back-to-back run instead of sending the rest of it`() {
        val stopped = CountDownLatch(1)
        val listener = Finish()
        // No --interval: the default, and the case that used to be unstoppable.
        val options = Cli.parse(listOf("mindray", "--count", "200", "--no-ack-wait"))
        lateinit var sender: Sender
        val gate = Gate { n ->
            // Let a few out, then call it off from another thread, as the
            // window's Stop button does.
            if (n == 3) { sender.cancel(); stopped.countDown() }
        }
        sender = Sender(options, listener, transportOverride = gate.factory())

        val worker = Thread { sender.run() }.apply { isDaemon = true; start() }
        assertTrue(stopped.await(10, TimeUnit.SECONDS), "the run never reached the third sample")
        worker.join(10_000)
        assertTrue(!worker.isAlive, "the run did not stop: it is still sending")

        assertTrue(gate.writes.get() < 200, "every sample went out anyway — Stop did nothing")
        assertEquals(RunKind.ABORTED, listener.summary?.kind,
            "a stopped run must not report itself as a completed one")
        assertTrue(listener.summary?.ok == false, "a stopped run is not a success")
    }

    @Test
    fun `a stopped run reports no failure — the engineer asked for it`() {
        val listener = Finish()
        lateinit var sender: Sender
        val gate = Gate { n -> if (n == 2) sender.cancel() }
        sender = Sender(
            Cli.parse(listOf("mindray", "--count", "20", "--no-ack-wait")),
            listener, transportOverride = gate.factory(),
        )
        sender.run()
        assertTrue(listener.failures.isEmpty(),
            "a Stop was reported as a fault: ${listener.failures.map { it.message }}")
    }

    /** With an interval there IS a sleep to interrupt, and that path must keep working. */
    @Test
    fun `an interrupt still stops a run that is sleeping between samples`() {
        val listener = Finish()
        val gate = Gate()
        val sender = Sender(
            Cli.parse(listOf("mindray", "--count", "20", "--interval", "30", "--no-ack-wait")),
            listener, transportOverride = gate.factory(),
        )
        val worker = Thread { sender.run() }.apply { isDaemon = true; start() }
        while (gate.writes.get() < 1) Thread.sleep(5)
        worker.interrupt()
        worker.join(10_000)
        assertTrue(!worker.isAlive, "the sleeping run ignored its interrupt")
        assertEquals(RunKind.ABORTED, listener.summary?.kind)
    }

    /**
     * A burst's children hold their own connections. Interrupting only the
     * parent left them dribbling frames into the lab while the window drew
     * "Stopped — nothing further was sent."
     */
    @Test
    fun `stopping a burst reaches the connections the parent thread did not open`() {
        val listener = Finish()
        val started = CountDownLatch(4)
        val released = CountDownLatch(1)
        lateinit var sender: Sender
        val gate = Gate {
            started.countDown()
            // Hold every burst child inside its write until the test lets go,
            // so the Stop lands while they are all still live.
            released.await(10, TimeUnit.SECONDS)
        }
        sender = Sender(
            Cli.parse(listOf("mindray", "--burst", "4", "--no-ack-wait")),
            listener, transportOverride = gate.factory(),
        )
        val worker = Thread { sender.run() }.apply { isDaemon = true; start() }
        assertTrue(started.await(10, TimeUnit.SECONDS), "the burst never opened its connections")

        sender.cancel()
        // Every child must have been interrupted, which is what releases them
        // from the latch above — the parent alone could not do this.
        released.countDown()
        worker.join(10_000)
        assertTrue(!worker.isAlive, "the burst never finished unwinding")
        assertEquals(RunKind.ABORTED, listener.summary?.kind)
    }

    @Test
    fun `a burst that is not stopped still reports itself as a burst`() {
        val listener = Finish()
        val gate = Gate()
        Sender(
            Cli.parse(listOf("mindray", "--burst", "3", "--no-ack-wait")),
            listener, transportOverride = gate.factory(),
        ).run()
        assertEquals(RunKind.BURST, listener.summary?.kind)
        assertEquals(3, listener.summary?.total)
        assertEquals(0, listener.summary?.stragglers)
        assertEquals(3, gate.writes.get())
    }
}
