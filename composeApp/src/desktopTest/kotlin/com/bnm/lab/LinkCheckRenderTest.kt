package com.bnm.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.bnm.lab.instruments.FirewallFacts
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentStatus
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.LinkCheck
import com.bnm.lab.instruments.LinkCheckReport
import com.bnm.lab.instruments.LinkFacts
import com.bnm.lab.instruments.LinkLogRow
import com.bnm.lab.instruments.LinkLogSummaries
import com.bnm.lab.instruments.LinkState
import com.bnm.lab.instruments.LocalAddress
import com.bnm.lab.instruments.NetshParser
import com.bnm.lab.screens.settings.LinkCheckBody
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.ui.theme.ThemeChoice
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Draws the Link check panel in each verdict — one ImageComposeScene per
 * test, as RemoteSupportUiTest does, because a layout-time crash never shows
 * in a unit test and would close the editor the moment a lab opened it.
 * Frames land in build/link-check-render for a human look.
 */
class LinkCheckRenderTest {

    private val tcp = InstrumentConfig(id = "i1", name = "Mispa bench 1", driver = "mispa_count_x",
        transport = InstrumentTransport.TCP, tcpPort = 5500, analyzerHost = "192.168.1.77")
    private val serial = InstrumentConfig(id = "i2", name = "Mispa serial", driver = "mispa_count_x",
        transport = InstrumentTransport.SERIAL, serialPort = "COM3")
    private val listening = InstrumentStatus("listening", "TCP port 5500", boundAt = "2026-09-25T09:00:00Z")
    private val windows = LinkFacts(os = "Windows 11", isWindows = true,
        localIpv4 = listOf(LocalAddress("192.168.1.20", "Ethernet")),
        serialPorts = listOf("COM1", "COM4"),
        firewall = FirewallFacts(applicable = true, known = true, enabled = true, profile = "Private"))

    private fun render(name: String, height: Int = 900, content: @Composable () -> Unit) {
        val out = File("build/link-check-render").apply { mkdirs() }
        ImageComposeScene(560, height, Density(1f)) {
            AppTheme(themeChoice = ThemeChoice.LIGHT) {
                Surface { Column(Modifier.padding(16.dp)) { content() } }
            }
        }.use { scene ->
            var img = scene.render(0)
            repeat(4) { i -> img = scene.render((i + 1) * 50_000_000L) }
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Composable
    private fun body(report: LinkCheckReport, testResult: List<String>? = null, ruleMessage: String? = null,
                     ruleCommand: String? = null, canAddRule: Boolean = false) {
        LinkCheckBody(report, canAddRule = canAddRule, busy = false, testResult = testResult,
            ruleMessage = ruleMessage, ruleCommand = ruleCommand,
            onCheckAgain = {}, onTest = {}, onAddRule = {}, onCopyReport = {}, onCopyCommand = {})
    }

    @Test
    fun `blocked at the firewall - with the fallback command after a refused elevation`() {
        val report = LinkCheck.evaluate(tcp, listening, LinkLogSummaries(), windows)
        assertEquals("Blocked at: Windows Firewall", report.verdict)
        render("blocked-at-firewall") {
            body(report, canAddRule = true,
                ruleMessage = "cancelled or refused (exit 1). Run this in an Administrator Command Prompt (Start ▸ type cmd ▸ right-click ▸ Run as administrator):",
                ruleCommand = NetshParser.addRuleCommand(5500))
        }
    }

    @Test
    fun `waiting for the analyzer to send`() {
        val facts = windows.copy(firewall = FirewallFacts(applicable = true, known = true, enabled = true, profile = "Private", ruleByPort = "BNM Lab analyzer port 5500"))
        val report = LinkCheck.evaluate(tcp, listening, LinkLogSummaries(), facts)
        assertEquals(LinkState.WAITING, report.verdictState)
        render("waiting") { body(report) }
    }

    @Test
    fun `linked - with a test connection result`() {
        val st = listening.copy(bytesIn = 4_096, framesIn = 3, framesParsed = 3, framesApplied = 3,
            lastFrameAt = "2026-09-25T10:42:07Z", peerIp = "192.168.1.77")
        val logs = LinkLogSummaries.of(listOf(
            LinkLogRow("info", "Applied 20/20 params to ACC-S1-00042 · CBC", "2026-09-25T10:42:07Z"),
            LinkLogRow("rx", "Result frame · specimen ACC-S1-00042 · 20 params · 3 histograms", "2026-09-25T10:42:07Z")))
        val report = LinkCheck.evaluate(tcp, st, logs, windows.copy(firewall = FirewallFacts(applicable = true, known = true, enabled = false)))
        assertEquals("Linked — last result 2026-09-25 10:42, applied to A***0042", report.verdict)
        render("linked") {
            body(report, testResult = listOf(
                "✓ This PC accepted a connection on port 5500",
                "✓ Sample frame parsed by Agappe Mispa Count X: 20 values (WBC, RBC, PLT, HGB, HCT, MCV, …)",
                "✓ 18 of 20 map to \"Complete Blood Count\" (best test in the catalog)",
                "… Unmapped: GRAN%, GRAN# (set them in the param map if the test has them)",
                "… Would match an order: no — the sample specimen BNMTEST-1 is never matched; a real sample with the accession as its id would be",
            ))
        }
    }

    @Test
    fun `serial port missing`() {
        val report = LinkCheck.evaluate(serial, InstrumentStatus("error", "Couldn't open COM3 — in use, or unplugged?"), LinkLogSummaries(), windows)
        assertEquals("Blocked at: Serial port COM3 is present", report.verdict)
        render("serial-port-missing", height = 800) { body(report) }
    }
}
