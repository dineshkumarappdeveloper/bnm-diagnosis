package com.bnm.lab

import com.bnm.lab.instruments.FirewallFacts
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentStatus
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.LinkAction
import com.bnm.lab.instruments.LinkCheck
import com.bnm.lab.instruments.LinkCheckReport
import com.bnm.lab.instruments.LinkFacts
import com.bnm.lab.instruments.LinkLogRow
import com.bnm.lab.instruments.LinkLogSummaries
import com.bnm.lab.instruments.LinkState
import com.bnm.lab.instruments.PingResult
import com.bnm.lab.instruments.SampleFrames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link-check evaluator, step by step: every OK / BLOCKED / WAITING /
 * UNKNOWN / SKIPPED transition each row can make, the verdict line, and the
 * PHI rule (ids quoted from log rows are masked). Pure — no engine, no OS.
 */
class LinkCheckTest {

    private val tcp = InstrumentConfig(id = "i1", name = "Mispa bench 1", driver = "mispa_count_x",
        transport = InstrumentTransport.TCP, tcpPort = 5500)
    private val serial = InstrumentConfig(id = "i2", name = "Mispa serial", driver = "mispa_count_x",
        transport = InstrumentTransport.SERIAL, serialPort = "COM3", baud = 115200)

    private val listening = InstrumentStatus("listening", "TCP port 5500", boundAt = "2026-09-25T09:00:00Z")
    private val fwOnNoRule = FirewallFacts(applicable = true, known = true, enabled = true, profile = "Private")
    private val windows = LinkFacts(os = "Windows 11", isWindows = true, localIpv4 = listOf("192.168.1.20"),
        serialPorts = listOf("COM1", "COM4"), firewall = fwOnNoRule)

    private fun row(r: LinkCheckReport, step: String) = r.rows.first { it.step == step }
    private fun logs(vararg rows: LinkLogRow) = LinkLogSummaries.of(rows.toList())
    private fun eval(cfg: InstrumentConfig = tcp, status: InstrumentStatus? = listening,
                     logs: LinkLogSummaries = LinkLogSummaries(), facts: LinkFacts = windows) =
        LinkCheck.evaluate(cfg, status, logs, facts)

    // ── the owner's exact case ──

    @Test
    fun `listening, firewall on, no rule, zero bytes - blocked at Windows Firewall`() {
        val r = eval()
        assertEquals("Blocked at: Windows Firewall", r.verdict)
        assertEquals(LinkState.BLOCKED, r.verdictState)
        assertEquals(listOf("listening", "address", "firewall", "reachable", "connected", "understood", "matched"), r.rows.map { it.step })
        assertEquals(LinkState.OK, row(r, "listening").state)
        assertEquals(LinkState.OK, row(r, "address").state)
        assertTrue(row(r, "address").detail.contains("use 192.168.1.20, port 5500, TCP client mode"), row(r, "address").detail)
        val fw = row(r, "firewall")
        assertEquals(LinkState.BLOCKED, fw.state)
        assertEquals(LinkAction.ADD_FIREWALL_RULE, fw.action)
        assertTrue(fw.detail.contains("no inbound rule allows port 5500"), fw.detail)
        assertTrue(fw.hint!!.contains("Add firewall rule"), fw.hint)
        assertEquals(LinkState.WAITING, row(r, "connected").state)
        assertEquals(fw, r.blockedAt)
    }

    // ── step 1: listening ──

    @Test
    fun `listening transitions`() {
        assertEquals(LinkState.OK, row(eval(), "listening").state)
        assertTrue(row(eval(), "listening").detail.startsWith("Open since 2026-09-25 09:00"))

        val opening = row(eval(status = InstrumentStatus("listening", "TCP port 5500")), "listening")
        assertEquals(LinkState.WAITING, opening.state)

        assertEquals(LinkState.UNKNOWN, row(eval(status = null), "listening").state)

        val inUse = row(eval(status = InstrumentStatus("error", "Address already in use: bind")), "listening")
        assertEquals(LinkState.BLOCKED, inUse.state)
        assertTrue(inUse.hint!!.contains("Another program on this PC already uses port 5500"), inUse.hint)
        assertTrue(inUse.hint!!.contains("5501"), inUse.hint)
        assertEquals("Blocked at: BNM Lab is listening on port 5500", eval(status = InstrumentStatus("error", "Address already in use: bind")).verdict)

        val otherError = row(eval(status = InstrumentStatus("error", "Network is unreachable")), "listening")
        assertEquals(LinkState.BLOCKED, otherError.state)
        assertTrue(otherError.hint!!.contains("retries by itself"), otherError.hint)

        val off = row(eval(status = InstrumentStatus("off", "Stopped")), "listening")
        assertEquals(LinkState.BLOCKED, off.state)

        val disabled = row(eval(cfg = tcp.copy(enabled = false)), "listening")
        assertEquals(LinkState.BLOCKED, disabled.state)
        assertTrue(disabled.detail.contains("switched off"))

        val noPort = row(eval(cfg = tcp.copy(tcpPort = null)), "listening")
        assertEquals(LinkState.BLOCKED, noPort.state)
        assertEquals(LinkAction.CHANGE_PORT, noPort.action)
    }

    // ── step 2: address ──

    @Test
    fun `address transitions`() {
        assertEquals(LinkState.OK, row(eval(), "address").state)
        val two = row(eval(facts = windows.copy(localIpv4 = listOf("192.168.1.20", "10.8.0.2"))), "address")
        assertTrue(two.detail.contains("also 10.8.0.2"), two.detail)
        val none = row(eval(facts = windows.copy(localIpv4 = emptyList())), "address")
        assertEquals(LinkState.BLOCKED, none.state)
        assertEquals("Blocked at: This PC's address", eval(facts = windows.copy(localIpv4 = emptyList())).verdict)
        val phone = row(eval(facts = LinkFacts(os = "Android", isWindows = false, environmentKnown = false)), "address")
        assertEquals(LinkState.UNKNOWN, phone.state)
    }

    // ── step 3: firewall ──

    @Test
    fun `firewall transitions`() {
        assertEquals(LinkState.BLOCKED, row(eval(), "firewall").state)

        val byPort = row(eval(facts = windows.copy(firewall = fwOnNoRule.copy(ruleByPort = "BNM Lab analyzer port 5500"))), "firewall")
        assertEquals(LinkState.OK, byPort.state)
        assertTrue(byPort.detail.contains("\"BNM Lab analyzer port 5500\" allows port 5500"), byPort.detail)

        val byProgram = row(eval(facts = windows.copy(firewall = fwOnNoRule.copy(ruleByProgram = "BNM Lab"))), "firewall")
        assertEquals(LinkState.OK, byProgram.state)
        assertTrue(byProgram.detail.contains("allows the BNM Lab program"), byProgram.detail)

        val off = row(eval(facts = windows.copy(firewall = fwOnNoRule.copy(enabled = false))), "firewall")
        assertEquals(LinkState.OK, off.state)
        assertTrue(off.detail.startsWith("Off (Private)"), off.detail)

        val unknown = row(eval(facts = windows.copy(firewall = FirewallFacts.unknown("non-English Windows"))), "firewall")
        assertEquals(LinkState.UNKNOWN, unknown.state)
        assertTrue(unknown.detail.contains("non-English Windows"), unknown.detail)
        assertEquals(LinkAction.ADD_FIREWALL_RULE, unknown.action)
        assertEquals("Waiting for the analyzer to send · not checked: Windows Firewall",
            eval(facts = windows.copy(firewall = FirewallFacts.unknown("x"))).verdict)

        val notRead = row(eval(facts = windows.copy(firewall = null)), "firewall")
        assertEquals(LinkState.UNKNOWN, notRead.state)
        assertEquals("Checking…", notRead.detail)

        val mac = row(eval(facts = windows.copy(isWindows = false, os = "Mac OS X 15.5", firewall = FirewallFacts.NOT_APPLICABLE)), "firewall")
        assertEquals(LinkState.SKIPPED, mac.state)

        // Bytes already arrived: whatever netsh says, the firewall is not what blocks.
        val reached = row(eval(status = listening.copy(bytesIn = 2048, framesIn = 1)), "firewall")
        assertEquals(LinkState.OK, reached.state)
        assertTrue(reached.detail.contains("already reached this PC"), reached.detail)
    }

    @Test
    fun `an unreadable firewall stops being a gap once bytes have crossed`() {
        // Un-read (a localised Windows, netsh refused) AND data arriving is an
        // answer, not "not checked" — the verdict must move on to the real step.
        val arrived = listening.copy(bytesIn = 2048)
        for (fw in listOf(FirewallFacts.unknown("non-English Windows"), null)) {
            val r = eval(status = arrived, facts = windows.copy(firewall = fw))
            val row = row(r, "firewall")
            assertEquals(LinkState.OK, row.state, fw.toString())
            assertTrue(row.detail.contains("already reached this PC"), row.detail)
            assertEquals("Blocked at: Frames understood by the driver", r.verdict)
        }
        // Still a gap while nothing has arrived.
        assertEquals(LinkState.UNKNOWN, row(eval(facts = windows.copy(firewall = FirewallFacts.unknown("x"))), "firewall").state)
    }

    // ── step 4: reachable ──

    @Test
    fun `reachable transitions`() {
        val noHost = row(eval(), "reachable")
        assertEquals(LinkState.UNKNOWN, noHost.state)
        assertEquals(LinkAction.ENTER_ANALYZER_HOST, noHost.action)

        val withHost = tcp.copy(analyzerHost = "192.168.1.77")
        assertEquals("Pinging 192.168.1.77…", row(eval(cfg = withHost), "reachable").detail)
        assertEquals(LinkState.UNKNOWN, row(eval(cfg = withHost), "reachable").state)

        val ok = row(eval(cfg = withHost, facts = windows.copy(ping = PingResult(true, "3 ms"))), "reachable")
        assertEquals(LinkState.OK, ok.state)

        val fail = row(eval(cfg = withHost, facts = windows.copy(firewall = fwOnNoRule.copy(enabled = false), ping = PingResult(false, "no reply in 1004 ms"))), "reachable")
        assertEquals(LinkState.BLOCKED, fail.state)
        assertTrue(fail.hint!!.contains("same network as 192.168.1.20"), fail.hint)
        assertEquals("Blocked at: Analyzer reachable from this PC",
            eval(cfg = withHost, facts = windows.copy(firewall = fwOnNoRule.copy(enabled = false), ping = PingResult(false, "x"))).verdict)

        val failButConnected = row(eval(cfg = withHost, status = listening.copy(bytesIn = 10),
            facts = windows.copy(ping = PingResult(false, "x"))), "reachable")
        assertEquals(LinkState.OK, failButConnected.state)

        val phone = row(eval(cfg = withHost, facts = LinkFacts(os = "Android", isWindows = false, environmentKnown = false)), "reachable")
        assertEquals(LinkState.UNKNOWN, phone.state)
    }

    // ── step 5: connected ──

    @Test
    fun `connected transitions`() {
        val waiting = row(eval(facts = windows.copy(firewall = fwOnNoRule.copy(enabled = false))), "connected")
        assertEquals(LinkState.WAITING, waiting.state)
        assertEquals("Nothing received since 2026-09-25 09:00", waiting.detail)
        assertTrue(waiting.hint!!.contains("192.168.1.20 port 5500"), waiting.hint)
        assertEquals("Waiting for the analyzer to send", eval(facts = windows.copy(firewall = fwOnNoRule.copy(enabled = false))).verdict)
        assertEquals(LinkState.WAITING, eval(facts = windows.copy(firewall = fwOnNoRule.copy(enabled = false))).verdictState)

        val peer = row(eval(status = listening.copy(bytesIn = 3_072, peerIp = "192.168.1.77")), "connected")
        assertEquals(LinkState.OK, peer.state)
        assertEquals("3 KB received from 192.168.1.77", peer.detail)

        // The self-probe connects from 127.0.0.1 — never presented as the analyzer.
        val self = row(eval(status = listening.copy(bytesIn = 12, peerIp = "127.0.0.1")), "connected")
        assertFalse(self.detail.contains("127.0.0.1"), self.detail)

        val serialWaiting = row(eval(cfg = serial, status = InstrumentStatus("listening", "COM3 @ 115200", boundAt = "2026-09-25T09:00:00Z"),
            facts = windows.copy(serialPorts = listOf("COM3"))), "connected")
        assertTrue(serialWaiting.hint!!.contains("Send / Transmit"), serialWaiting.hint)
    }

    // ── step 6: understood ──

    @Test
    fun `bytes but no frame, frames but none parsed, and parsed`() {
        assertEquals(LinkState.WAITING, row(eval(), "understood").state)

        val noFrame = eval(status = listening.copy(bytesIn = 500))
        assertEquals(LinkState.BLOCKED, row(noFrame, "understood").state)
        assertTrue(row(noFrame, "understood").hint!!.contains("Agappe Mispa Count X driver"), row(noFrame, "understood").hint)
        assertEquals("Blocked at: Frames understood by the driver", noFrame.verdict)

        val unframed = eval(status = listening.copy(bytesIn = 500),
            logs = logs(LinkLogRow("info", "Connection closed with 500 unframed bytes (ignored)", "2026-09-25T09:05:00Z")))
        assertTrue(row(unframed, "understood").detail.contains("never formed a complete frame"), row(unframed, "understood").detail)

        val notUnderstood = eval(status = listening.copy(bytesIn = 900, framesIn = 2),
            logs = logs(LinkLogRow("error", "Frame not understood by mispa_count_x: $$$20260908\$12\$SPEC12345\$PAT99887", "2026-09-25T09:06:00Z")))
        val u = row(notUnderstood, "understood")
        assertEquals(LinkState.BLOCKED, u.state)
        assertTrue(u.detail.startsWith("2 frames arrived, none understood"), u.detail)
        assertTrue(u.detail.contains("Agappe Mispa Count X"), u.detail)
        assertFalse(u.detail.contains("SPEC12345"), u.detail)
        assertTrue(u.detail.contains("S***2345"), u.detail)
        assertFalse(u.detail.contains("PAT99887"), u.detail)

        val parsed = row(eval(status = listening.copy(bytesIn = 900, framesIn = 3, framesParsed = 2, framesIgnored = 1)), "understood")
        assertEquals(LinkState.OK, parsed.state)
        assertEquals("2 of 3 frames carried a result · 1 ignored (QC or non-result messages)", parsed.detail)
    }

    // ── step 7: matched ──

    @Test
    fun `unmatched results block with the masked reason and the claim-queue action`() {
        val st = listening.copy(bytesIn = 900, framesIn = 1, framesParsed = 1, framesUnmatched = 1)
        val r = eval(status = st, logs = logs(
            LinkLogRow("info", "Queued for manual claim — no order matches 'S12345'", "2026-09-25T09:07:00Z"),
            LinkLogRow("rx", "Result frame · specimen S12345 · 20 params · 0 histograms", "2026-09-25T09:07:00Z")))
        val m = row(r, "matched")
        assertEquals(LinkState.BLOCKED, m.state)
        assertEquals(LinkAction.ASSIGN_WAITING_RESULTS, m.action)
        assertTrue(m.detail.startsWith("1 result waiting for an order — no order matches 'S***2345'"), m.detail)
        assertFalse(m.detail.contains("S12345"))
        assertTrue(m.hint!!.contains("accession number"), m.hint)
        assertEquals("Blocked at: Result matched to an order", r.verdict)
    }

    @Test
    fun `verify_pending blocks at matching until Verified`() {
        val m = row(eval(cfg = tcp.copy(verifyPending = true), status = listening.copy(bytesIn = 1, framesIn = 1, framesParsed = 1)), "matched")
        assertEquals(LinkState.BLOCKED, m.state)
        assertEquals(LinkAction.PRESS_VERIFIED, m.action)
    }

    @Test
    fun `verify_pending does not claim the verdict before any result exists`() {
        // The gate is real but it holds RESULTS; with nothing yet sent, the step
        // that blocks is still the analyzer, and the verdict must say so.
        val r = eval(cfg = tcp.copy(verifyPending = true), facts = windows.copy(firewall = fwOnNoRule.copy(enabled = false)))
        val m = row(r, "matched")
        assertEquals(LinkState.WAITING, m.state)
        assertNull(m.action)
        assertTrue(m.detail.contains("until the bench presses Verified"), m.detail)
        assertEquals("Waiting for the analyzer to send", r.verdict)
        assertNull(r.blockedAt)
    }

    @Test
    fun `no result yet waits, applied results are linked with the masked accession`() {
        assertEquals(LinkState.WAITING, row(eval(status = listening.copy(bytesIn = 5)), "matched").state)

        val st = listening.copy(bytesIn = 4000, framesIn = 3, framesParsed = 3, framesApplied = 2, framesUnmatched = 1,
            lastFrameAt = "2026-09-25T10:42:07Z", peerIp = "192.168.1.77")
        val r = eval(status = st, logs = logs(
            LinkLogRow("info", "Applied 18/20 params to ACC-S1-00042 · CBC · 2 unmapped", "2026-09-25T10:42:07Z"),
            LinkLogRow("info", "Queued for manual claim — no order matches '7'", "2026-09-25T10:30:00Z")))
        assertEquals("Linked — last result 2026-09-25 10:42, applied to A***0042", r.verdict)
        assertEquals(LinkState.OK, r.verdictState)
        val m = row(r, "matched")
        assertEquals(LinkState.OK, m.state)
        assertTrue(m.detail.contains("A***0042"), m.detail)
        assertFalse(m.detail.contains("ACC-S1-00042"), m.detail)
        assertTrue(m.detail.endsWith("· 1 waiting for an order"), m.detail)
        assertEquals(LinkAction.ASSIGN_WAITING_RESULTS, m.action)
        assertNull(r.blockedAt)

        // Linked even when the firewall could not be read — results prove the link.
        val fwUnknown = eval(status = st, facts = windows.copy(firewall = FirewallFacts.unknown("x")))
        assertTrue(fwUnknown.verdict.startsWith("Linked"), fwUnknown.verdict)
    }

    // ── serial ──

    @Test
    fun `serial port missing lists what exists and blocks first`() {
        val r = eval(cfg = serial, status = InstrumentStatus("error", "Couldn't open COM3 — in use, or unplugged?"))
        assertEquals(listOf("port_present", "port_open", "settings", "connected", "understood", "matched"), r.rows.map { it.step })
        val p = row(r, "port_present")
        assertEquals(LinkState.BLOCKED, p.state)
        assertEquals("COM3 not found — this PC has COM1, COM4", p.detail)
        assertEquals(LinkAction.CHOOSE_SERIAL_PORT, p.action)
        assertEquals("Blocked at: Serial port COM3 is present", r.verdict)
        assertEquals(LinkState.BLOCKED, row(r, "port_open").state)
        assertTrue(row(r, "settings").detail.contains("115200 baud, 8 data bits, no parity, 1 stop bit (8-N-1)"))

        val noneAtAll = row(eval(cfg = serial, status = null, facts = windows.copy(serialPorts = emptyList())), "port_present")
        assertEquals("COM3 not found — this PC has no serial ports at all", noneAtAll.detail)
    }

    @Test
    fun `serial port present but held by another program, then open`() {
        val facts = windows.copy(serialPorts = listOf("COM3"))
        val held = eval(cfg = serial, status = InstrumentStatus("error", "Couldn't open COM3 — in use, or unplugged?"), facts = facts)
        assertEquals(LinkState.OK, row(held, "port_present").state)
        assertEquals(LinkState.BLOCKED, row(held, "port_open").state)
        assertTrue(row(held, "port_open").hint!!.contains("another program"), row(held, "port_open").hint)
        assertEquals("Blocked at: Port opened by BNM Lab", held.verdict)

        val open = eval(cfg = serial, status = InstrumentStatus("listening", "COM3 @ 115200", boundAt = "2026-09-25T09:00:00Z"), facts = facts)
        assertEquals(LinkState.OK, row(open, "port_open").state)
        assertEquals("Waiting for the analyzer to send", open.verdict)

        assertEquals(LinkState.BLOCKED, row(eval(cfg = serial.copy(serialPort = null), facts = facts), "port_present").state)
        assertEquals(LinkState.BLOCKED, row(eval(cfg = serial.copy(enabled = false), facts = facts), "port_present").state)
        assertEquals(LinkState.UNKNOWN, row(eval(cfg = serial, facts = LinkFacts(os = "Android", isWindows = false, environmentKnown = false)), "port_present").state)
    }

    // ── report text + samples ──

    @Test
    fun `copy report carries version, verdict, counters and masked ids only`() {
        val st = listening.copy(bytesIn = 4000, framesIn = 1, framesParsed = 1, framesApplied = 1, lastFrameAt = "2026-09-25T10:42:07Z",
            lastError = "Frame not understood by mispa_count_x: $$$1\$2\$SPEC12345", lastErrorAt = "2026-09-25T08:00:00Z")
        val logs = logs(LinkLogRow("info", "Applied 20/20 params to ACC-S1-00042 · CBC", "2026-09-25T10:42:07Z"),
            LinkLogRow("rx", "Result frame · specimen ACC-S1-00042 · 20 params · 3 histograms", "2026-09-25T10:42:07Z"))
        val report = eval(cfg = tcp.copy(analyzerHost = "192.168.1.77"), status = st, logs = logs)
        val text = LinkCheck.reportText(tcp.copy(analyzerHost = "192.168.1.77"), st, logs, windows, report, "1.4.0")
        assertTrue(text.startsWith("BNM Lab 1.4.0 · Analyzer link check · Windows 11"), text)
        assertTrue(text.contains("Verdict: Linked — last result 2026-09-25 10:42, applied to A***0042"), text)
        assertTrue(text.contains("Counters: state=listening"), text)
        assertTrue(text.contains("bytes=4000"), text)
        assertTrue(text.contains("1. [OK] BNM Lab is listening on port 5500"), text)
        assertTrue(text.contains("PC: ipv4=192.168.1.20 serial=COM1,COM4 firewall=on/Private rulePort=- ruleProgram=-"), text)
        assertFalse(text.contains("ACC-S1-00042"), text)
        assertFalse(text.contains("SPEC12345"), text)
        assertTrue(text.contains("A***0042"), text)
    }

    @Test
    fun `bundled sample frames parse with their driver and carry the never-matched test specimen`() {
        for (driver in listOf("mispa_count_x", "mindray_hl7")) {
            val text = assertNotNull(SampleFrames.forDriver(driver), driver)
            val (stored, note) = assertNotNull(InstrumentEngine.parseFrameText(driver, text), "$driver parses")
            assertEquals(SampleFrames.SPECIMEN, stored.specimenId, driver)
            assertNull(stored.patientId, "$driver carries no patient id")
            assertTrue(stored.params.size >= 14, "$driver: ${stored.params.keys}")
            assertNull(note, driver)
        }
        assertNull(SampleFrames.forDriver("astm_serial"))
    }

    @Test
    fun `applied accession is read from the summary and masked`() {
        assertEquals("A***0042", LinkCheck.appliedAccession("Applied 18/20 params to ACC-S1-00042 · CBC"))
        assertNull(LinkCheck.appliedAccession("Queued for manual claim — no order"))
    }
}
