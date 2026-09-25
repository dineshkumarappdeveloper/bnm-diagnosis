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
        var form = SimForm().copy(sampleId = "SIM-0001", count = "1", autoIncrementId = true)
        val ids = (1..5).map { form.sampleId.also { _ -> form = form.afterRun() } }
        assertEquals(listOf("SIM-0001", "SIM-0002", "SIM-0003", "SIM-0004", "SIM-0005"), ids)
    }

    /**
     * ONE run of five, not five runs of one — which is what the shipped
     * "Commissioning rehearsal" preset does and what step 9 of the README's
     * rehearsal script asks for.
     *
     * This used to send BNMTEST-0001 five times: `toOptions` put the single id
     * in `ids` and the 5 in `count`, and the core picked `ids[index % 1]` every
     * time. Five results landed on one order, each overwriting the last, so the
     * step that exists to prove "none lost" was the one step that could not.
     */
    @Test
    fun `one run of the rehearsal preset puts five DIFFERENT accessions on the wire`() {
        val rehearsal = PresetStore.BUILT_IN.first { it.name == "Commissioning rehearsal" }.toForm()
        assertEquals("5", rehearsal.count, "the preset is no longer a five-sample run")
        assertTrue(rehearsal.autoIncrementId, "the preset no longer asks for advancing ids")

        val ids = rehearsal.toOptions().sampleIds
        assertEquals(5, ids.size)
        assertEquals(ids.size, ids.distinct().size, "the rehearsal still files five results onto one order: $ids")
        assertEquals(
            listOf("BNMTEST-0001", "BNMTEST-0002", "BNMTEST-0003", "BNMTEST-0004", "BNMTEST-0005"),
            ids,
        )
    }

    @Test
    fun `the next run starts after the whole batch, not on top of it`() {
        val after = SimForm().copy(sampleId = "SIM-0001", count = "5", autoIncrementId = true).afterRun()
        assertEquals("SIM-0006", after.sampleId,
            "run two would have reused run one's accessions")
    }

    @Test
    fun `a burst consumes an accession per connection`() {
        val form = SimForm().copy(sampleId = "SIM-0001", count = "1",
            faults = FaultForm(burst = true, burstCount = "4"))
        assertEquals(listOf("SIM-0001", "SIM-0002", "SIM-0003", "SIM-0004"), form.toOptions().sampleIds)
        assertEquals("SIM-0005", form.afterRun().sampleId)
    }

    @Test
    fun `a pattern in the id box is an enumeration — it cycles, and the box is left alone`() {
        val form = SimForm().copy(sampleId = "ACC-S1-000{1..3}", count = "3", autoIncrementId = true)
        assertEquals(listOf("ACC-S1-0001", "ACC-S1-0002", "ACC-S1-0003"), form.toOptions().sampleIds)
        assertEquals("ACC-S1-000{1..3}", form.afterRun().sampleId,
            "advancing rewrote the pattern into a single id and destroyed it")
    }

    @Test
    fun `with auto-increment off a five-sample run really does reuse one accession`() {
        // Not a bug — the engineer asked for it, and the core warns in the
        // transcript. What matters is that the tick means something.
        val form = SimForm().copy(sampleId = "ACC-S1-00042", count = "5", autoIncrementId = false)
        assertEquals(List(5) { "ACC-S1-00042" }, form.toOptions().sampleIds)
    }

    /**
     * `MispaFrames` never reads badUnits — that format has no unit field — so a
     * tick carried over from a Mindray would put "bad-units" in the transcript
     * over a byte-for-byte clean frame.
     */
    @Test
    fun `bad units cannot survive a switch to the Mispa, which has no units to spoil`() {
        val mindray = SimForm().copy(faults = FaultForm(badUnits = true))
        assertTrue(mindray.toOptions().badUnits, "a Mindray run must still be able to spoil the unit")

        val mispa = mindray.withAnalyzer(Analyzer.MISPA)
        assertTrue(!mispa.faults.badUnits, "the tick survived the switch")
        assertTrue(!mispa.toOptions().badUnits)

        // And a preset saved on a Mindray, loaded onto a Mispa, cannot smuggle it in.
        val loaded = SimForm().copy(analyzer = Analyzer.MISPA, faults = FaultForm(badUnits = true))
        assertTrue(!loaded.toOptions().badUnits, "a preset carried the no-op fault onto a Mispa")
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
        assertEquals(3, o.count)
        // A burst IS its samples: four connections carrying one frame each. This
        // form ticks every fault at once, and Sender.burst is the one that runs.
        assertEquals(4, o.samples)
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

    // ── the safety rules the core added, which a UI is exactly where they
    // ── quietly stop applying. These run through Cli.validate, so they hold
    // ── here because there is ONE gate, not a second copy of the rules.

    @Test
    fun `the default id is one no accession series can produce`() {
        // "42" would tail-match ACC-S1-00042 on a real lab's own series.
        assertEquals("BNMTEST-0001", SimForm().sampleId)
        assertEquals("BNMTEST-0002", nextSampleId("BNMTEST-0001"))
    }

    @Test
    fun `sending to another machine is refused until it is consented to, by hand`() {
        val remote = SimForm().withHost("192.168.1.50")
        assertTrue(remote.sendsToAnotherMachine)
        val refusal = remote.problem(FormField.LIVE_LAB)
        assertTrue(refusal != null, "a run at another machine must not be sendable: ${remote.problems()}")
        assertTrue(refusal!!.contains("INVENTED"), refusal)
        assertTrue(remote.copy(liveLab = true).problems().isEmpty())
    }

    @Test
    fun `loopback is this machine and needs no consent`() {
        for (host in listOf("127.0.0.1", "localhost", "127.1.2.3", "::1")) {
            val f = SimForm().withHost(host)
            assertTrue(!f.sendsToAnotherMachine, "$host should be this machine")
            assertTrue(f.problems().isEmpty(), "$host: ${f.problems()}")
        }
    }

    @Test
    fun `changing the address withdraws the consent given for the old one`() {
        val consented = SimForm().withHost("192.168.1.50").copy(liveLab = true)
        assertTrue(consented.problems().isEmpty())
        val moved = consented.withHost("192.168.1.51")
        assertTrue(!moved.liveLab, "consent is per-target; a new address is a new decision")
        assertTrue(moved.problem(FormField.LIVE_LAB) != null)
        // Re-typing the same address is not a change.
        assertTrue(consented.withHost("192.168.1.50").liveLab)
    }

    @Test
    fun `a serial link refuses the three faults that need a connection`() {
        val cable = SimForm().withAnalyzer(Analyzer.MISPA).withTransport(TransportKind.SERIAL)
            .copy(serialPort = "COM3")
        assertTrue(cable.problems().isEmpty())
        for (f in listOf(FaultForm(truncated = true), FaultForm(burst = true), FaultForm(hang = true))) {
            val refusal = cable.copy(faults = f).problem(FormField.FAULTS)
            assertTrue(refusal != null, "a cable has no connection to $f: ${cable.copy(faults = f).problems()}")
        }
        // Over TCP the same three are fine.
        val tcp = SimForm().copy(faults = FaultForm(truncated = true, hang = true))
        assertNull(tcp.problem(FormField.FAULTS))
    }

    @Test
    fun `CBC-only is a Mindray run mode and never reaches a 3-part analyzer`() {
        assertTrue(SimForm().copy(cbcOnly = true).toOptions().cbcOnly)
        // Even if the flag is forced on, the Mispa never receives it — it has no
        // CBC-only mode, and Cli.validate would refuse the run outright.
        val mispa = SimForm().withAnalyzer(Analyzer.MISPA).copy(cbcOnly = true)
        assertTrue(!mispa.toOptions().cbcOnly)
        assertTrue(mispa.problems().isEmpty())
    }

    @Test
    fun `QC on a Mispa is allowed but is not a QC run — the core says so, not the form`() {
        // The form does not block it: the value is telling an engineer WHY it
        // does nothing, which the run's caveats do. What must not happen is the
        // transcript claiming a QC run happened.
        val mispa = SimForm().withAnalyzer(Analyzer.MISPA).copy(qc = true)
        assertTrue(mispa.problems().isEmpty())
        assertTrue(mispa.toOptions().qc)
    }
}
