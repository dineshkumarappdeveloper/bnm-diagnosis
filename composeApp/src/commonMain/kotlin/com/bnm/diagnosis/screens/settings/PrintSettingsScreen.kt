package com.bnm.diagnosis.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.billing.PrintKind
import com.bnm.diagnosis.billing.PrintProfiles
import com.bnm.diagnosis.print.BtPrinter
import com.bnm.diagnosis.report.ReportPalette
import com.bnm.diagnosis.report.ReportPrefs
import com.bnm.diagnosis.ui.theme.AppTheme

/** Desktop is the primary target, so the two profiles sit side by side as soon
 *  as there is room. Same breakpoint the lab screens use (LabHomeScreen). */
private val WIDE_BREAKPOINT = 900.dp

/**
 * **Print settings — one printer per document kind.**
 *
 * A lab prints two very different documents on two very different machines: the
 * bill on an 80mm thermal roll at the counter, the patient report on the A4
 * laser in the back office. Until profiles existed both read ONE shared block of
 * prefs, so configuring the counter silently re-pointed reports — that confusion
 * is what this page exists to end. Each section therefore names the document it
 * drives, carries its own live summary, and never shares a control with the other.
 *
 * Everything here is DEVICE-LOCAL: [com.bnm.diagnosis.billing.PrintProfile] and
 * [ReportPrefs] write straight into the Settings store, so there is no save
 * button — edits persist as they are made, exactly as Billing settings behaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSettingsScreen(
    onBack: () -> Unit,
    /**
     * Opens the Bluetooth printer picker FOR THE GIVEN PROFILE (the picker page
     * itself is owned by the host, which persists the choice into that profile).
     * Null hides the button entirely — a host that cannot show a picker should
     * not offer one.
     */
    onPickBluetooth: ((PrintKind) -> Unit)? = null,
    /** License-bound lab name, shown as the (read-only) letterhead heading. */
    labName: String? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Print settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(inner)) {
            val wide = maxWidth >= WIDE_BREAKPOINT
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Bills and reports print on separate printers, configured separately. " +
                        "These settings live on THIS device only — every counter and every " +
                        "back-office PC keeps its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.textSecondary,
                )

                if (wide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PrinterProfileCard(PrintKind.INVOICE, onPickBluetooth, Modifier.weight(1f))
                        PrinterProfileCard(PrintKind.REPORT, onPickBluetooth, Modifier.weight(1f)) {
                            LetterheadBlock(labName)
                        }
                    }
                } else {
                    PrinterProfileCard(PrintKind.INVOICE, onPickBluetooth, Modifier.fillMaxWidth())
                    PrinterProfileCard(PrintKind.REPORT, onPickBluetooth, Modifier.fillMaxWidth()) {
                        LetterheadBlock(labName)
                    }
                }
            }
        }
    }
}

/**
 * One document kind's printer, top to bottom: what it prints → whether it prints
 * → where it prints → on what paper → how many copies.
 *
 * [extra] hangs kind-specific settings off the bottom of the same card; the
 * report uses it for the letterhead, because a report's paper and its letterhead
 * are one decision and splitting them across two cards is how you end up with a
 * pre-printed letterpad selected for a thermal roll.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PrinterProfileCard(
    kind: PrintKind,
    onPickBluetooth: ((PrintKind) -> Unit)?,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val c = AppTheme.colors
    // PrintProfiles.of() mints a fresh wrapper per call; hold ONE so the mirrored
    // state below and the store stay in step.
    val profile = remember(kind) { PrintProfiles.of(kind) }
    val btPrinter = remember { BtPrinter.getInstance() }

    // Each pref is mirrored into state for recomposition and written through to
    // the store in the same gesture — device-local prefs, no save button.
    var enabled by remember(kind) { mutableStateOf(profile.enabled) }
    var conn by remember(kind) { mutableStateOf(profile.connection) }
    var ip by remember(kind) { mutableStateOf(profile.ip) }
    var port by remember(kind) { mutableStateOf(profile.port.toString()) }
    var paper by remember(kind) { mutableStateOf(profile.paperWidth) }
    var autoPrint by remember(kind) { mutableStateOf(profile.autoPrint) }
    var copies by remember(kind) { mutableStateOf(profile.copies) }

    // NOT mirrored: the Bluetooth selection is written by the picker, which this
    // screen does not own. Reading it straight from the profile means whatever
    // the picker stored is what shows the next time this card composes.
    val btName = profile.btName
    val btAddress = profile.btAddress

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, c.border),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── Which printer is this? ──
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileBadge(if (kind == PrintKind.INVOICE) Icons.AutoMirrored.Outlined.ReceiptLong else Icons.Outlined.Biotech)
                Column(Modifier.weight(1f)) {
                    Text(kind.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(kind.blurb, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it; profile.enabled = it })
            }
            // profile.summary is read live rather than recomputed here: every
            // control on this card writes through before updating its state, so
            // the recomposition that follows always reads the new value.
            Text(
                profile.summary,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) c.accentTextOnSoft else c.textTertiary,
                modifier = Modifier
                    .background(if (enabled) c.accentSoft else c.surfaceMuted, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            if (!enabled) {
                Text(
                    "Printing is off for this document — it can still be saved, shared or re-printed later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }

            HorizontalDivider(color = c.border)

            // ── Where it prints ──
            Text("Connection", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChip("System print dialog", conn == "system") { conn = "system"; profile.connection = "system" }
                ChoiceChip("LAN", conn == "network") { conn = "network"; profile.connection = "network" }
                ChoiceChip("Bluetooth", conn == "bluetooth") { conn = "bluetooth"; profile.connection = "bluetooth" }
            }

            when (conn) {
                "network" -> {
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it.trim(); profile.ip = it },
                        label = { Text("Printer IP (e.g. 192.168.1.50)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        // Only a parseable value is stored: mid-edit the field can
                        // legitimately be empty, and blanking it must not silently
                        // repoint the printer at port 0.
                        onValueChange = { v ->
                            port = v.filter { it.isDigit() }.take(5)
                            port.toIntOrNull()?.let { profile.port = it }
                        },
                        label = { Text("Port (TVS / Epson = 9100)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Raw ESC/POS over the network. Plug the printer into the router and print its " +
                            "self-test (hold FEED while powering on) to find the IP — it must be on this device's network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                    if (ip.isBlank()) Caution("No IP set yet — nothing will reach this printer.")
                }

                "bluetooth" -> {
                    if (!btPrinter.isSupported) {
                        Caution("Bluetooth printing isn't available on this device — use LAN instead. (Desktop has no portable Bluetooth stack.)")
                    } else {
                        Text(
                            if (btName.isNotBlank()) "Selected: $btName"
                            else if (btAddress.isNotBlank()) "Selected: $btAddress"
                            else "No printer selected yet",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (onPickBluetooth != null) {
                            OutlinedButton(
                                onClick = { onPickBluetooth(kind) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (btName.isBlank() && btAddress.isBlank()) "Choose printer…" else "Change printer…") }
                        }
                        if (btAddress.isBlank()) Caution("No printer chosen yet — nothing will print over Bluetooth.")
                    }
                }
            }

            HorizontalDivider(color = c.border)

            // ── On what paper ──
            Text("Paper", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChip("58 mm", paper == 32) { paper = 32; profile.paperWidth = 32 }
                ChoiceChip("80 mm", paper == 48) { paper = 48; profile.paperWidth = 48 }
                ChoiceChip("A4", paper == 64) { paper = 64; profile.paperWidth = 64 }
            }
            // A4 and a direct ESC/POS transport genuinely cannot both be true. Say
            // so instead of accepting a setting that quietly prints garbage.
            if (paper == 64 && conn != "system") {
                Caution(
                    "A4 only works through the system print dialog. A LAN or Bluetooth thermal printer " +
                        "takes 58 or 80 mm rolls — pick a roll width, or switch this document to the system dialog.",
                )
            }
            if (kind == PrintKind.REPORT && paper != 64) {
                Text(
                    "On a roll the report prints as a plain monospace slip, not the styled A4 PDF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }

            HorizontalDivider(color = c.border)

            // ── How it prints ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-print", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (kind == PrintKind.INVOICE) "Print as soon as a bill is saved."
                        else "Print as soon as a report is released.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
                Switch(checked = autoPrint, onCheckedChange = { autoPrint = it; profile.autoPrint = it })
            }
            Text("Copies", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (1..5).forEach { n ->
                    ChoiceChip(n.toString(), copies == n) { copies = n; profile.copies = n }
                }
            }
            // NOTE: no "Test print" button. There is no shared test-page helper in
            // print/ — the only sample page is a private fun inside
            // BillingSettingsScreen — and minting a second private copy here is
            // exactly the duplication this page exists to remove.

            extra()
        }
    }
}

/**
 * Report letterhead — the other half of "how a report comes out of the printer".
 * Lives inside the report card on purpose: choosing a pre-printed letterpad while
 * the report is pointed at a thermal roll is a contradiction you should be able
 * to see in one glance.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.LetterheadBlock(labName: String?) {
    val c = AppTheme.colors
    val prefs = remember { ReportPrefs() }

    var mode by remember { mutableStateOf(prefs.letterheadMode) }
    var headerMm by remember { mutableStateOf(prefs.headerMm.toString()) }
    var footerMm by remember { mutableStateOf(prefs.footerMm.toString()) }
    var address by remember { mutableStateOf(prefs.addressLine) }
    var phone by remember { mutableStateOf(prefs.phoneLine) }
    var email by remember { mutableStateOf(prefs.emailLine) }
    var extra by remember { mutableStateOf(prefs.extraLine) }
    var accent by remember { mutableStateOf(prefs.accentRgb) }

    HorizontalDivider(color = c.border)

    Text("Letterhead", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        // The lab name is license-bound and read-only in-app, so it is stated
        // rather than offered as a field.
        if (labName != null) "Reports are headed \"$labName\" — set by your license, not editable here."
        else "The lab name on the letterhead comes from your license and can't be edited here.",
        style = MaterialTheme.typography.bodySmall,
        color = c.textSecondary,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceChip("Print letterhead", mode == "printed") { mode = "printed"; prefs.letterheadMode = "printed" }
        ChoiceChip("Pre-printed letterpad", mode == "preprinted") { mode = "preprinted"; prefs.letterheadMode = "preprinted" }
    }
    Text(
        if (mode == "preprinted")
            "Nothing is drawn in the header/footer areas — the space is reserved blank so results land under your letterpad's printed header."
        else
            "The app draws the letterhead (accent band, lab name, the lines below) inside the header space on every page.",
        style = MaterialTheme.typography.bodySmall,
        color = c.textSecondary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = headerMm,
            onValueChange = { v ->
                headerMm = v.filter { it.isDigit() }.take(3)
                headerMm.toIntOrNull()?.let { prefs.headerMm = it }
            },
            label = { Text("Header space (mm)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = footerMm,
            onValueChange = { v ->
                footerMm = v.filter { it.isDigit() }.take(3)
                footerMm.toIntOrNull()?.let { prefs.footerMm = it }
            },
            label = { Text("Footer space (mm)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    // Only the drawn letterhead uses these lines; on a letterpad they would be
    // printed twice, so there is nothing to fill in.
    if (mode == "printed") {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; prefs.addressLine = it },
            label = { Text("Address line") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it; prefs.phoneLine = it },
                label = { Text("Phone") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; prefs.emailLine = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = extra,
            onValueChange = { extra = it; prefs.extraLine = it },
            label = { Text("Extra line (NABL / GSTIN / tagline)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Accent colour", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReportPalette.presets.forEach { (name, rgb) ->
                AccentSwatch(rgb = rgb, name = name, selected = accent == rgb) {
                    accent = rgb; prefs.accentRgb = rgb
                }
            }
        }
    }
}

/** Rounded tinted square behind a section's icon — the lab-screen card idiom. */
@Composable
private fun ProfileBadge(icon: ImageVector) {
    val c = AppTheme.colors
    Box(
        Modifier.size(30.dp).background(c.accentSoft, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = c.accentTextOnSoft, modifier = Modifier.size(17.dp))
    }
}

/** Inline warning for a setting that cannot do what it says it will. */
@Composable
private fun Caution(text: String) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.warningSoft, RoundedCornerShape(10.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = c.warning, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** One report accent-colour preset: a filled circle, ringed when selected. */
@Composable
private fun AccentSwatch(rgb: Int, name: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .size(34.dp)
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
                .padding(4.dp)
                .background(Color(0xFF000000.toInt() or rgb), CircleShape)
                .clickable(onClick = onClick),
        )
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else AppTheme.colors.textSecondary,
        )
    }
}
