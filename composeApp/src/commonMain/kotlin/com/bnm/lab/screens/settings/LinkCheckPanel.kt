package com.bnm.lab.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.lab.BuildInfo
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentStatus
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.LinkAction
import com.bnm.lab.instruments.LinkCheck
import com.bnm.lab.instruments.LinkCheckReport
import com.bnm.lab.instruments.LinkEnvironment
import com.bnm.lab.instruments.LinkFacts
import com.bnm.lab.instruments.LinkLogSummaries
import com.bnm.lab.instruments.LinkState
import com.bnm.lab.instruments.SampleFrames
import com.bnm.lab.instruments.driverFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Link check" for one analyzer — the ordered checklist from [LinkCheck]
 * with ✓ / ✗ / … rows, the hint under the row that blocks, and the four
 * buttons: Check again, Test connection, Add firewall rule (only while
 * blocked there) and Copy report (PHI-free text for WhatsApp to BNM).
 *
 * Live inputs (status counters, log rows, the PC snapshot) are state the
 * caller owns; the panel re-evaluates on every change and on a 2 s tick
 * while visible. [LinkCheckBody] is the stateless drawing, so a render test
 * can pin each verdict's layout without an engine.
 */
@Composable
fun LinkCheckPanel(
    cfg: InstrumentConfig,
    status: InstrumentStatus?,
    logs: LinkLogSummaries,
    facts: LinkFacts,
    /** Results of THIS analyzer still in the claim queue — the DB truth, not a session counter. */
    queued: Int,
    environment: LinkEnvironment,
    engine: InstrumentEngine,
    /** Re-read the PC facts now (the screen owns the snapshot). */
    onCheckAgain: () -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) { delay(2_000); tick++ }
    }
    val report = remember(cfg, status, logs, facts, queued, tick) { LinkCheck.evaluate(cfg, status, logs, facts, queued) }

    var busy by remember { mutableStateOf(false) }
    var testResult by remember(cfg.id) { mutableStateOf<List<String>?>(null) }
    var ruleMessage by remember(cfg.id) { mutableStateOf<String?>(null) }
    var ruleCommand by remember(cfg.id) { mutableStateOf<String?>(null) }

    LinkCheckBody(
        report = report,
        // Only when a firewall answer was actually read AND there is a port to
        // allow. The "Checking…" row carries no action, so a press can neither
        // fire a UAC prompt before anything has been read nor hit the
        // `tcpPort ?: return` below and do nothing at all.
        canAddRule = cfg.tcpPort != null &&
            report.rows.any { it.step == "firewall" && it.action == LinkAction.ADD_FIREWALL_RULE },
        busy = busy,
        testResult = testResult,
        ruleMessage = ruleMessage,
        ruleCommand = ruleCommand,
        onCheckAgain = onCheckAgain,
        onTest = {
            busy = true
            scope.launch {
                testResult = runCatching { testConnection(cfg, status, environment, engine) }
                    .getOrElse { listOf("✗ Test failed: ${it.message ?: it::class.simpleName}") }
                busy = false
            }
        },
        onAddRule = {
            val port = cfg.tcpPort ?: return@LinkCheckBody
            busy = true; ruleMessage = null; ruleCommand = null
            scope.launch {
                val outcome = withContext(Dispatchers.Default) { environment.addFirewallRule(port) }
                ruleMessage = outcome.detail
                delay(3_000)
                val after = withContext(Dispatchers.Default) { environment.firewall(port) }
                val stillBlocked = !after.known || (after.enabled == true && !after.hasRule)
                if (!outcome.launched || stillBlocked) {
                    ruleCommand = environment.firewallRuleCommand(port)
                    ruleMessage = (if (outcome.launched) "The rule is not there yet. " else "${outcome.detail}. ") +
                        "Run this in an Administrator Command Prompt (Start ▸ type cmd ▸ right-click ▸ Run as administrator):"
                } else {
                    ruleMessage = "Firewall rule added — port $port is allowed."
                }
                onCheckAgain()
                busy = false
            }
        },
        onCopyReport = {
            clipboard.setText(AnnotatedString(LinkCheck.reportText(cfg, status, logs, facts, report, BuildInfo.VERSION)))
            onMessage("Link check report copied — paste it into WhatsApp to BNM")
        },
        onCopyCommand = { cmd ->
            clipboard.setText(AnnotatedString(cmd))
            onMessage("Command copied")
        },
    )
}

/** TCP: self-probe + the bundled sample frame through dryRun; serial: open/close only while the listener is stopped. */
private suspend fun testConnection(
    cfg: InstrumentConfig,
    status: InstrumentStatus?,
    environment: LinkEnvironment,
    engine: InstrumentEngine,
): List<String> {
    val lines = ArrayList<String>()
    val driverLabel = driverFor(cfg.driver)?.label ?: cfg.driver
    if (cfg.transport == InstrumentTransport.SERIAL) {
        val port = cfg.serialPort?.trim().orEmpty()
        when {
            port.isEmpty() -> lines += "✗ No serial port chosen"
            status?.state == "listening" ->
                lines += "… $port is held by BNM Lab's own listener — that IS the link. To probe the port itself, switch the analyzer off first."
            else -> {
                val problem = withContext(Dispatchers.Default) { environment.probeSerial(port) }
                lines += if (problem == null) "✓ $port opened and closed fine — nothing else is holding it" else "✗ $problem"
            }
        }
    } else {
        val port = cfg.tcpPort
        if (port == null) lines += "✗ No listen port set"
        else {
            val probe = withContext(Dispatchers.Default) { environment.tcpSelfProbe(port) }
            lines += (if (probe.accepted) "✓ " else "✗ ") + probe.detail.replaceFirstChar { it.uppercase() }
        }
    }
    val sample = SampleFrames.forDriver(cfg.driver)
    if (sample == null) {
        lines += "… No sample frame is bundled for $driverLabel"
        return lines
    }
    val dry = engine.dryRun(cfg, sample)
    if (!dry.parsed) {
        lines += "✗ Sample frame: ${dry.note ?: "not parsed"}"
        return lines
    }
    val keys = dry.paramKeys
    lines += "✓ Sample frame parsed by $driverLabel: ${keys.size} values (${keys.take(6).joinToString(", ")}${if (keys.size > 6) ", …" else ""})"
    lines += if (dry.testName != null)
        "✓ ${dry.mapped.size} of ${keys.size} map to \"${dry.testName}\" (${if (dry.mappingBasis == "catalog") "best test in the catalog" else "the ordered tests"})"
    else "✗ No test in the catalog takes these values — add a CBC-type test with these parameters"
    if (dry.unmapped.isNotEmpty()) lines += "… Unmapped: ${dry.unmapped.joinToString(", ")} (set them in the param map if the test has them)"
    if (dry.unitMismatches.isNotEmpty()) lines += "✗ Unit not convertible: ${dry.unitMismatches.joinToString(", ")}"
    lines += "… Would match an order: no — the sample specimen ${SampleFrames.SPECIMEN} is never matched; a real sample with the accession as its id would be"
    return lines
}

@Composable
fun LinkCheckBody(
    report: LinkCheckReport,
    /** Whether "Add firewall rule" can do anything — see [LinkCheckPanel]. */
    canAddRule: Boolean,
    busy: Boolean,
    testResult: List<String>?,
    ruleMessage: String?,
    ruleCommand: String?,
    onCheckAgain: () -> Unit,
    onTest: () -> Unit,
    onAddRule: () -> Unit,
    onCopyReport: () -> Unit,
    onCopyCommand: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(linkColor(report.verdictState), CircleShape))
            Text(report.verdict, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = linkColor(report.verdictState))
        }
        report.rows.forEach { row -> LinkRow(row) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCheckAgain, enabled = !busy) { Text("Check again") }
            TextButton(onClick = onTest, enabled = !busy) { Text(if (busy) "Testing…" else "Test connection") }
            if (canAddRule) {
                TextButton(onClick = onAddRule, enabled = !busy) { Text("Add firewall rule") }
            }
            TextButton(onClick = onCopyReport) { Text("Copy report") }
        }

        testResult?.let { lines ->
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("Test connection", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        ruleMessage?.let { msg ->
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(msg, style = MaterialTheme.typography.bodySmall)
                ruleCommand?.let { cmd ->
                    Text(cmd, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { onCopyCommand(cmd) }) { Text("Copy command") }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(row: com.bnm.lab.instruments.LinkCheckRow) {
    val color = linkColor(row.state)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(
            linkGlyph(row.state),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.width(16.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.state == LinkState.BLOCKED) FontWeight.SemiBold else FontWeight.Normal,
                color = if (row.state == LinkState.BLOCKED) color else MaterialTheme.colorScheme.onSurface)
            Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.hint != null && (row.state == LinkState.BLOCKED || row.state == LinkState.UNKNOWN)) {
                Text(
                    row.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = LINK_HINT_FG,
                    modifier = Modifier.fillMaxWidth()
                        .background(LINK_HINT_BG, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Fixed rather than theme roles — green/amber/red must read the same in both themes. */
internal val LINK_GREEN = Color(0xFF1B7F3B)
internal val LINK_AMBER = Color(0xFFB26A00)
internal val LINK_RED = Color(0xFFB3261E)
private val LINK_HINT_BG = Color(0xFFFFF3CD)
private val LINK_HINT_FG = Color(0xFF7A4F00)

@Composable
internal fun linkColor(state: LinkState): Color = when (state) {
    LinkState.OK -> LINK_GREEN
    LinkState.BLOCKED -> LINK_RED
    LinkState.WAITING -> LINK_AMBER
    LinkState.UNKNOWN, LinkState.SKIPPED -> MaterialTheme.colorScheme.outline
}

internal fun linkGlyph(state: LinkState): String = when (state) {
    LinkState.OK -> "✓"
    LinkState.BLOCKED -> "✗"
    LinkState.WAITING -> "…"
    LinkState.UNKNOWN -> "?"
    LinkState.SKIPPED -> "–"
}
