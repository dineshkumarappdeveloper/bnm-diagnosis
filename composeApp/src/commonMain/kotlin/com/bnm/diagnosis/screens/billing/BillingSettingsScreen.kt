package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.api.BillingApi
import com.bnm.diagnosis.auth.AuthRepository
import com.bnm.diagnosis.billing.BillingPrefs
import com.bnm.diagnosis.billing.PrintProfiles
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.chat.LocalSyncEngine
import com.bnm.diagnosis.chat.currentFy
import com.bnm.diagnosis.staff.LocalStaffSession
import com.bnm.diagnosis.sync.LabSyncEngine
import com.bnm.diagnosis.update.AppVersionPanel
import kotlinx.coroutines.launch

/**
 * Settings — an INDEX, not a form.
 *
 * This screen used to be one long scroll with every form inlined, so finding the
 * printer meant scrolling past the GST field and the letterhead editor. It is now
 * a Telegram-style list: an identity block, then grouped rows that each carry
 * their CURRENT VALUE as the subtitle ("LAN 192.168.1.50:9100 · 80mm" rather than
 * a bare "Printer"), so the common question — *what is this seat set to?* — is
 * answered without opening anything.
 *
 * A row either opens a short dialog (things that are two fields and a button) or
 * navigates to a page (things that are not). Printer + letterhead moved out to
 * [com.bnm.diagnosis.screens.settings.PrintSettingsScreen] via [onOpenPrintSettings];
 * nothing else moved, and nothing was dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingSettingsScreen(
    api: BillingApi,
    authRepository: AuthRepository,
    businessId: String,
    onBack: () -> Unit,
    onOpenLicense: (() -> Unit)? = null,
    /** Invoked once an update installer has been launched — the host should quit
     *  so the installer can replace the running application. */
    onQuitForUpdate: () -> Unit = {},
    /** P4: opens Staff & roles. Null hides the row entirely. */
    onOpenStaff: (() -> Unit)? = null,
    /** P4: only the OWNER may manage staff — others see the row, disabled. */
    staffManageAllowed: Boolean = false,
    /** Opens the printer page (invoice + report profiles, letterhead). Defaults to
     *  a no-op only so the host can be wired in a separate pass — App.kt should
     *  always point this at Screen.PrintSettings. */
    onOpenPrintSettings: () -> Unit = {},
    /** P3: the lab sync spine — renders the "Lab data sync" row when provided. */
    labSync: LabSyncEngine? = null,
    /** License-bound lab name — printed on the report letterhead (read-only here). */
    labName: String = "BNM Diagnosis",
    /** I0: opens Instruments (analyzer interfacing). Null hides the row. */
    onOpenInstruments: (() -> Unit)? = null,
    /** Live one-liner for the Instruments row ("2 listening", "a listener is down"). */
    instrumentsSummary: String? = null,
) {
    val repo = LocalBillingRepository.current
    val scope = rememberCoroutineScope()
    val settings by repo.invoiceSettingsFlow(businessId).collectAsState(null)
    val syncEngine = LocalSyncEngine.current
    val signedInStaff by LocalStaffSession.current.current.collectAsState()

    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearMsg by remember { mutableStateOf<String?>(null) }

    // ── Counter series ──
    var currentSeries by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(businessId) {
        currentSeries = repo.deviceSeries(businessId)?.let { "${it.prefix}-${it.series} (FY ${it.fy}, last #${it.highWater})" }
    }
    var showSeriesDialog by remember { mutableStateOf(false) }
    var seriesCode by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("INV") }
    var registering by remember { mutableStateOf(false) }
    var seriesMsg by remember { mutableStateOf<String?>(null) }

    // ── Business GST ──
    var showGstDialog by remember { mutableStateOf(false) }
    var taxId by remember { mutableStateOf("") }
    var savingSettings by remember { mutableStateOf(false) }
    var gstMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings) { settings?.taxId?.let { if (taxId.isBlank()) taxId = it } }

    // ── Barcode scanner ──
    val prefs = remember { BillingPrefs() }
    var showBarcodeDialog by remember { mutableStateOf(false) }
    var barcodeOn by remember { mutableStateOf(prefs.barcodeEnabled) }
    var barcodeMode by remember { mutableStateOf(prefs.barcodeMode) }

    // Deliberately NOT remembered: the printer page edits these prefs behind our
    // back, and this destination is recomposed from scratch when it comes back
    // off the nav stack — a plain read is what keeps the subtitle honest.
    val invoicePrinter = PrintProfiles.invoice.summary
    val reportPrinter = PrintProfiles.report.summary

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.Close, contentDescription = "Back") }
            })
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            // Desktop is the primary target: a lab PC window is wide, and rows
            // stretched across 2000px are unreadable. Centre a column instead.
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Identity: who this seat is, before any setting ──
            item {
                IdentityHeader(
                    labName = labName,
                    staffLine = signedInStaff
                        ?.let { "${it.name} · ${it.roleLabel}" }
                        ?: "No one signed in on this seat",
                    deviceLine = currentSeries?.let { "This counter: $it" }
                        ?: "This counter has no billing series yet",
                    modifier = Modifier.settingsWidth(),
                )
            }

            // ── Billing setup ──
            item {
                SettingsGroup("Billing", Modifier.settingsWidth()) {
                    SettingsRow(
                        icon = Icons.Outlined.Numbers,
                        tint = MaterialTheme.colorScheme.primary,
                        title = "Counter series",
                        subtitle = currentSeries ?: "Not registered on this device",
                        onClick = { seriesMsg = null; showSeriesDialog = true },
                    )
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Percent,
                        tint = MaterialTheme.colorScheme.primary,
                        title = "Business GST",
                        subtitle = taxId.ifBlank { "GSTIN not set" },
                        onClick = { gstMsg = null; showGstDialog = true },
                    )
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Print,
                        tint = MaterialTheme.colorScheme.primary,
                        title = "Printing",
                        // Both profiles at a glance — the counter roll and the
                        // back-office A4 are genuinely different machines.
                        subtitle = "Invoice — $invoicePrinter\nReport — $reportPrinter",
                        subtitleMaxLines = 2,
                        onClick = onOpenPrintSettings,
                    )
                    RowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.QrCodeScanner,
                        tint = MaterialTheme.colorScheme.primary,
                        title = "Barcode scanner",
                        subtitle = when {
                            !barcodeOn -> "Off"
                            barcodeMode == "camera" -> "On · camera"
                            else -> "On · USB / Bluetooth"
                        },
                        onClick = { showBarcodeDialog = true },
                    )
                    if (onOpenInstruments != null) {
                        RowDivider()
                        SettingsRow(
                            icon = Icons.Outlined.Cable,
                            tint = MaterialTheme.colorScheme.primary,
                            title = "Instruments",
                            subtitle = instrumentsSummary
                                ?: "Connect analyzers — results enter themselves",
                            subtitleMaxLines = 2,
                            onClick = onOpenInstruments,
                        )
                    }
                }
            }

            // ── Data ──
            item {
                SettingsGroup("Data", Modifier.settingsWidth()) {
                    if (labSync != null) {
                        // Self-contained: the row, its state and its dialog share
                        // one collectAsState rather than hoisting a nullable flow.
                        LabSyncRow(labSync)
                        RowDivider()
                    }
                    SettingsRow(
                        icon = Icons.Outlined.DeleteSweep,
                        tint = MaterialTheme.colorScheme.tertiary,
                        title = "Clear local data & re-sync",
                        subtitle = clearMsg ?: "Drop this device's cache, pull everything fresh",
                        subtitleMaxLines = 2,
                        enabled = !clearing,
                        onClick = { showClearConfirm = true },
                        trailing = if (clearing) {
                            { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                        } else null,
                    )
                }
            }

            // ── People & licence ──
            if (onOpenStaff != null || onOpenLicense != null) {
                item {
                    SettingsGroup("Lab & licence", Modifier.settingsWidth()) {
                        if (onOpenStaff != null) {
                            SettingsRow(
                                icon = Icons.Outlined.People,
                                tint = MaterialTheme.colorScheme.secondary,
                                title = "Staff & roles",
                                subtitle = if (staffManageAllowed)
                                    "Who works this lab, and what each of them may do"
                                else
                                    "Only the lab owner can manage staff",
                                subtitleMaxLines = 2,
                                enabled = staffManageAllowed,
                                onClick = onOpenStaff,
                            )
                            if (onOpenLicense != null) RowDivider()
                        }
                        if (onOpenLicense != null) {
                            SettingsRow(
                                icon = Icons.Outlined.Key,
                                tint = MaterialTheme.colorScheme.secondary,
                                title = "License & devices",
                                subtitle = "Licensed to $labName · manage the seats in use",
                                subtitleMaxLines = 2,
                                onClick = onOpenLicense,
                            )
                        }
                    }
                }
            }

            // ── App version + in-app update ──
            // Stays a full panel, not a row: it is what an operator is asked to
            // read out when reporting a bug, and it owns a live download progress
            // bar that must not be dismissable mid-install.
            item {
                Column(Modifier.settingsWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupCaption("App")
                    AppVersionPanel(onQuitForUpdate = onQuitForUpdate)
                }
            }

            // ── Account ──
            item {
                SettingsGroup("Account", Modifier.settingsWidth()) {
                    SettingsRow(
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        tint = MaterialTheme.colorScheme.error,
                        title = "Sign out this device",
                        subtitle = "Needs a new pairing code (or owner login) to bill again",
                        subtitleMaxLines = 2,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { showLogoutConfirm = true },
                    )
                }
            }
        }
    }

    // ── Counter series (short form → dialog) ──
    if (showSeriesDialog) {
        AlertDialog(
            onDismissRequest = { if (!registering) showSeriesDialog = false },
            title = { Text("Counter series") },
            text = {
                Column(
                    Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(currentSeries ?: "No series registered on this device yet.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Each billing counter gets its OWN series (e.g. C1, C2) so two counters can bill offline in parallel with no number collision.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = seriesCode, onValueChange = { seriesCode = it.uppercase() },
                        label = { Text("Series code (e.g. C1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = prefix, onValueChange = { prefix = it.uppercase() },
                        label = { Text("Prefix") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    seriesMsg?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (registering || seriesCode.isBlank()) return@TextButton
                        registering = true; seriesMsg = null
                        val fy = currentFy()
                        val fmt = "{prefix}-{series}-{seq}"
                        scope.launch {
                            api.registerCounter(businessId, seriesCode.trim(), prefix.trim().ifBlank { "INV" }, fmt, fy, 0)
                                .onSuccess { c ->
                                    repo.registerSeriesLocal(businessId, c.seriesCode, c.fy ?: fy, c.prefix ?: prefix, c.numberFormat ?: fmt, c.highWater)
                                    currentSeries = "${c.prefix}-${c.seriesCode} (FY ${c.fy ?: fy}, last #${c.highWater})"
                                    seriesMsg = "Series ${c.seriesCode} registered on this device"
                                    registering = false
                                }.onFailure { seriesMsg = it.message ?: "Failed to register"; registering = false }
                        }
                    },
                    enabled = !registering && seriesCode.isNotBlank(),
                ) { Text(if (registering) "Registering…" else "Register on this device") }
            },
            dismissButton = {
                TextButton(onClick = { if (!registering) showSeriesDialog = false }) { Text("Close") }
            },
        )
    }

    // ── Business GST (one field → dialog) ──
    if (showGstDialog) {
        AlertDialog(
            onDismissRequest = { if (!savingSettings) showGstDialog = false },
            title = { Text("Business GST") },
            text = {
                Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = taxId, onValueChange = { taxId = it.uppercase() },
                        label = { Text("Business GSTIN (first 2 digits = home state)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    gstMsg?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (savingSettings) return@TextButton
                        savingSettings = true; gstMsg = null
                        val body = "{\"tax_id\":\"${taxId.trim()}\"}"
                        scope.launch {
                            api.updateSettings(businessId, body)
                                .onSuccess { gstMsg = "Saved" }.onFailure { gstMsg = it.message ?: "Failed" }
                            savingSettings = false
                        }
                    },
                    enabled = !savingSettings,
                ) { Text(if (savingSettings) "Saving…" else "Save GSTIN") }
            },
            dismissButton = {
                TextButton(onClick = { if (!savingSettings) showGstDialog = false }) { Text("Close") }
            },
        )
    }

    // ── Barcode scanner (a switch and a mode → dialog) ──
    if (showBarcodeDialog) {
        AlertDialog(
            onDismissRequest = { showBarcodeDialog = false },
            title = { Text("Barcode scanner") },
            text = {
                Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SwitchRow("Enable barcode scanner", barcodeOn) { barcodeOn = it; prefs.barcodeEnabled = it }
                    Text("Scanner type", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("USB / Bluetooth", barcodeMode == "wedge") { barcodeMode = "wedge"; prefs.barcodeMode = "wedge" }
                        ChoiceChip("Camera", barcodeMode == "camera") { barcodeMode = "camera"; prefs.barcodeMode = "camera" }
                    }
                    Text("Scan a product barcode to add it to the open bill. USB/Bluetooth scanners act as a keyboard on every platform; camera scanning (Android/iOS) is being wired up.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showBarcodeDialog = false }) { Text("Done") } },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { if (!clearing) showClearConfirm = false },
            title = { Text("Clear local data?") },
            text = { Text("Wipes this device's cached products, bills and other synced data, then re-downloads everything current from the server. Your login, counter series and any un-synced bills are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    if (clearing) return@TextButton
                    clearing = true; clearMsg = null
                    scope.launch {
                        try {
                            repo.clearLocalData()
                            syncEngine.syncAll(businessId)
                            clearMsg = "Local data cleared & re-synced"
                        } catch (e: Throwable) {
                            clearMsg = "Failed: ${e.message}"
                        } finally {
                            clearing = false; showClearConfirm = false
                        }
                    }
                }) { Text("Clear & re-sync") }
            },
            dismissButton = { TextButton(onClick = { if (!clearing) showClearConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Sign out?") },
            text = { Text("This device will be signed out. You'll need a new pairing code (or owner login) to bill again.") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    scope.launch { authRepository.signOut() }
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") } },
        )
    }
}

/** Lab data sync: the row, its live state and its detail dialog in one place. */
@Composable
private fun LabSyncRow(labSync: LabSyncEngine) {
    val scope = rememberCoroutineScope()
    val state by labSync.state.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    SettingsRow(
        icon = Icons.Outlined.CloudSync,
        tint = MaterialTheme.colorScheme.tertiary,
        title = "Lab data sync",
        subtitle = when {
            state.disabled -> "Off — this licence is standalone"
            state.syncing -> "Syncing…"
            state.lastError != null -> "Last attempt failed — tap for details"
            else -> "Last synced: ${state.lastSyncAt?.replace('T', ' ')?.take(16) ?: "never"}"
        },
        subtitleColor = if (state.lastError != null && !state.syncing) MaterialTheme.colorScheme.error else null,
        onClick = { showDialog = true },
        trailing = if (state.syncing) {
            { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
        } else null,
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Lab data sync") },
            text = {
                Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (state.disabled)
                            "Sync is off — this license is standalone (not linked to a BNM business). Everything keeps working fully offline."
                        else
                            "Backs up patients, orders and results to BNM, converges other seats, and exchanges EMR orders with partner clinics. The app never needs it to work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Last synced: ${state.lastSyncAt?.replace('T', ' ')?.take(16) ?: "never"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.lastError?.let {
                        Text("Last attempt failed: $it", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { scope.launch { labSync.syncNow() } },
                    enabled = !state.syncing,
                ) { Text(if (state.syncing) "Syncing…" else "Sync now") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Close") } },
        )
    }
}

/** The profile block: which lab, who is at the keyboard, which counter. */
@Composable
private fun IdentityHeader(
    labName: String,
    staffLine: String,
    deviceLine: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Biotech, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(labName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(staffLine, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(deviceLine, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A caption + one rounded card holding the group's rows. */
@Composable
private fun SettingsGroup(
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupCaption(caption)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(content = content)
        }
    }
}

@Composable
private fun GroupCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/** Hairline between two rows, inset past the icon so it reads as a list. */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One list row: tinted icon square · title · current value · chevron.
 *
 * [trailing] replaces the chevron (a switch, or a spinner while something runs);
 * a row with no [onClick] and no [trailing] is simply informational.
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    subtitleMaxLines: Int = 1,
    subtitleColor: Color? = null,
    titleColor: Color? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(tint.copy(alpha = 0.14f * alpha), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = (titleColor ?: MaterialTheme.colorScheme.onSurface).copy(alpha = alpha),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = (subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = alpha),
                    maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(
                Icons.Outlined.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f * alpha),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Content column width. Rows are a list, not a canvas — they stop growing. */
private fun Modifier.settingsWidth(): Modifier = this.fillMaxWidth().widthIn(max = 720.dp)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
