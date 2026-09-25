package com.bnm.analyzersim.ui

import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules behind the boxes.
 *
 * All of this used to live in a CLI's argument parser, where it was covered.
 * Moving it into a window is exactly where such rules go quietly wrong — a port
 * that does not follow the analyzer, a serial radio a Mindray should never be
 * able to select — and none of that shows up in a screenshot.
 */
class SimFormTest {

    @Test
    fun `the defaults are the ones BNM Lab ships listening on`() {
        val f = SimForm()
        assertEquals(Analyzer.MINDRAY, f.analyzer)
        assertEquals("5500", f.port)
        assertEquals("127.0.0.1", f.host)
        assertEquals("mindray_hl7", f.driverKey)
        assertEquals(TransportKind.TCP, f.transport)
        assertTrue(f.problems().isEmpty(), "the form must be sendable the moment it opens: ${f.problems()}")
    }

    @Test
    fun `choosing the other analyzer moves the port to its default`() {
        val mispa = SimForm().withAnalyzer(Analyzer.MISPA)
        assertEquals("5501", mispa.port)
        assertEquals("mispa_count_x", mispa.driverKey)
        assertEquals("5500", mispa.withAnalyzer(Analyzer.MINDRAY).port)
    }

    @Test
    fun `a port the engineer typed is never overwritten by switching analyzer`() {
        // The whole point: glancing at the other analyzer must not silently send
        // the next run to a port nothing is listening on.
        val typed = SimForm().copy(port = "6100")
        assertEquals("6100", typed.withAnalyzer(Analyzer.MISPA).port)
    }

    @Test
    fun `serial is a Mispa link only, and says why`() {
        val mindray = SimForm()
        assertTrue(!mindray.serialAllowed)
        assertTrue(mindray.serialRefusal!!.contains("ACK"), mindray.serialRefusal!!)
        // Selecting it does nothing at all rather than arming a Send that refuses.
        assertEquals(TransportKind.TCP, mindray.withTransport(TransportKind.SERIAL).transport)

        val mispa = SimForm().withAnalyzer(Analyzer.MISPA)
        assertNull(mispa.serialRefusal)
        assertEquals(TransportKind.SERIAL, mispa.withTransport(TransportKind.SERIAL).transport)
    }

    @Test
    fun `switching back to the Mindray drops a serial link rather than leaving it selected`() {
        val onCable = SimForm().withAnalyzer(Analyzer.MISPA).withTransport(TransportKind.SERIAL)
        assertEquals(TransportKind.TCP, onCable.withAnalyzer(Analyzer.MINDRAY).transport)
    }

    @Test
    fun `the id advances by one, keeping the width an accession needs`() {
        assertEquals("SIM-0002", nextSampleId("SIM-0001"))
        assertEquals("ACC-S1-00043", nextSampleId("ACC-S1-00042"))
        assertEquals("L1-0100", nextSampleId("L1-0099"))
        // Overflowing the width has to grow it; 0099 -> 0100 does not.
        assertEquals("100", nextSampleId("99"))
        // No trailing number at all: a counter, rather than repeating forever.
        assertEquals("TUBE-2", nextSampleId("TUBE"))
    }

    @Test
    fun `five runs of the rehearsal preset are five accessions`() {
        var form = SimForm().copy(sampleId = "SIM-0001", autoIncrementId = true)
        val ids = (1..5).map { form.sampleId.also { _ -> form = form.afterRun() } }
        assertEquals(listOf("SIM-0001", "SIM-0002", "SIM-0003", "SIM-0004", "SIM-0005"), ids)
    }

    @Test
    fun `with auto-increment off every run reuses the same id`() {
        val form = SimForm().copy(sampleId = "ACC-S1-00042", autoIncrementId = false)
        assertEquals("ACC-S1-00042", form.afterRun().afterRun().sampleId)
    }

    @Test
    fun `a bad host, port, baud or count is refused in words, under the right box`() {
        assertEquals(FormField.HOST, SimForm().copy(host = "  ").problems().single().field)
        assertTrue(SimForm().copy(port = "99999").problem(FormField.PORT)!!.contains("1 and 65535"))
        assertTrue(SimForm().copy(port = "five").problem(FormField.PORT)!!.contains("whole number"))
        assertTrue(SimForm().copy(count = "0").problem(FormField.COUNT)!!.contains("at least one"))
        assertTrue(SimForm().copy(count = "9000").problem(FormField.COUNT)!!.contains("load test"))
        assertTrue(SimForm().copy(intervalSeconds = "-1").problem(FormField.INTERVAL) != null)
        assertTrue(SimForm().copy(seed = "x").problem(FormField.SEED)!!.contains("whole number"))

        val serial = SimForm().withAnalyzer(Analyzer.MISPA).withTransport(TransportKind.SERIAL)
        assertTrue(serial.problem(FormField.SERIAL_PORT)!!.contains("Refresh"))
        assertTrue(serial.copy(serialPort = "COM3", baud = "nope").problem(FormField.BAUD)!!.contains("115200"))
        // On a cable the host and port boxes are not even on screen, so they
        // must not be able to block Send.
        assertTrue(serial.copy(serialPort = "COM3", host = "", port = "x").problems().isEmpty())
    }

    @Test
    fun `a blank id is only a problem when something is expected to be keyed`() {
        assertTrue(SimForm().copy(sampleId = "").problem(FormField.SAMPLE_ID) != null)
        val claimQueue = SimForm().copy(sampleId = "", faults = FaultForm(noSpecimen = true))
        assertNull(claimQueue.problem(FormField.SAMPLE_ID))
    }

    @Test
    fun `the fault boxes only complain when their fault is ticked`() {
        assertNull(SimForm().copy(faults = FaultForm(slowChunksMs = "junk")).problem(FormField.SLOW_CHUNKS))
        assertTrue(SimForm().copy(faults = FaultForm(slowChunks = true, slowChunksMs = "junk"))
            .problem(FormField.SLOW_CHUNKS) != null)
        assertTrue(SimForm().copy(faults = FaultForm(burst = true, burstCount = "0"))
            .problem(FormField.BURST) != null)
    }

    @Test
    fun `the form becomes exactly the options the core would have got from the command line`() {
        val form = SimForm().copy(
            host = "192.168.1.50", port = "5500", sampleId = "ACC-S1-000{1..3}", count = "3",
            intervalSeconds = "2.5", profile = Profile.CRITICAL, patientName = "Asha Menon",
            patientId = "PAT-9001", qc = true, histograms = false, image = true, seed = "42",
            faults = FaultForm(truncated = true, slowChunks = true, slowChunksMs = "40",
                unknownCode = true, badUnits = true, noSpecimen = true, duplicate = true,
                burst = true, burstCount = "4", hang = true, garbage = true),
        )
        val o = form.toOptions()
        assertEquals("192.168.1.50", o.host)
        assertEquals(5500, o.port)
        assertEquals(listOf("ACC-S1-0001", "ACC-S1-0002", "ACC-S1-0003"), o.ids)
        assertEquals(3, o.samples)
        assertEquals(2.5, o.intervalSeconds)
        assertEquals(Profile.CRITICAL, o.profile)
        assertEquals("Asha Menon", o.patientName)
        assertTrue(o.qc && !o.histograms && o.image)
        assertEquals(42L, o.seed)
        assertNull(o.serialPort, "a TCP form must never hand the core a serial port")
        assertTrue(o.unknownCode && o.badUnits && o.noSpecimen)
        assertTrue(o.faults.truncated && o.faults.garbage && o.faults.duplicate && o.faults.hang)
        assertEquals(40L, o.faults.slowChunksMs)
        assertEquals(4, o.faults.burst)
    }

    @Test
    fun `an unticked fault reaches the core as nothing, not as a zero`() {
        val o = SimForm().copy(faults = FaultForm(slowChunksMs = "40", burstCount = "5")).toOptions()
        assertNull(o.faults.slowChunksMs, "an unticked slow-chunks must not dribble at 40ms")
        assertEquals(0, o.faults.burst)
    }

    @Test
    fun `blank patient fields are absent, not empty strings`() {
        val o = SimForm().copy(patientName = "  ", patientId = "").toOptions()
        assertNull(o.patientName)
        assertNull(o.patientId)
    }

    @Test
    fun `a serial form hands the core the cable and the Mindray never can`() {
        val mispa = SimForm().withAnalyzer(Analyzer.MISPA).withTransport(TransportKind.SERIAL)
            .copy(serialPort = "/dev/tty.usbserial-110", baud = "115200")
        assertEquals("/dev/tty.usbserial-110", mispa.toOptions().serialPort)
        assertEquals(115200, mispa.toOptions().baud)
        // Forcing the field by hand still cannot produce a serial Mindray.
        val forced = mispa.copy(analyzer = Analyzer.MINDRAY)
        assertNull(forced.toOptions().serialPort)
    }
}
