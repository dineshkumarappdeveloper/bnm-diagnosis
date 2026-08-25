package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.api.BillingApi
import com.bnm.diagnosis.auth.AuthRepository
import com.bnm.diagnosis.billing.BillingPrefs
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.chat.LocalSyncEngine
import com.bnm.diagnosis.chat.currentFy
import com.bnm.diagnosis.print.BtPrinter
import com.bnm.diagnosis.print.EscPos
import com.bnm.diagnosis.report.ReportPalette
import com.bnm.diagnosis.report.ReportPrefs
import com.bnm.diagnosis.report.openPdf
import com.bnm.diagnosis.report.sampleReportDoc
import com.bnm.diagnosis.report.writeLabReportPdf
import com.bnm.diagnosis.sync.LabSyncEngine
import com.bnm.diagnosis.print.printReceipt
import com.bnm.diagnosis.print.printToNetworkPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingSettingsScreen(
    api: BillingApi,
    authRepository: AuthRepository,
    businessId: String,
    onBack: () -> Unit,
    onOpenLicense: (() -> Unit)? = null,
    /** P3: the lab sync spine — renders the "Sync now" card when provided. */
    labSync: LabSyncEngine? = null,
    /** License-bound lab name — printed on the report letterhead (read-only here). */
    labName: String = "BNM Diagnosis",
) {
    val repo = LocalBillingRepository.current
    val scope = rememberCoroutineScope()
    val settings by repo.invoiceSettingsFlow(businessId).collectAsState(null)
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val syncEngine = LocalSyncEngine.current
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearMsg by remember { mutableStateOf<String?>(null) }

    var currentSeries by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(businessId) {
        currentSeries = repo.deviceSeries(businessId)?.let { "${it.prefix}-${it.series} (FY ${it.fy}, last #${it.highWater})" }
    }

    var seriesCode by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("INV") }
    var registering by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    var taxId by remember { mutableStateOf("") }
    var savingSettings by remember { mutableStateOf(false) }
    LaunchedEffect(settings) { settings?.taxId?.let { if (taxId.isBlank()) taxId = it } }

    val prefs = remember { BillingPrefs() }
    var printerOn by remember { mutableStateOf(prefs.printerEnabled) }
    var paper by remember { mutableStateOf(prefs.paperWidth) }
    var autoPrint by remember { mutableStateOf(prefs.autoPrint) }
    var conn by remember { mutableStateOf(prefs.printerConnection) }
    var printerIp by remember { mutableStateOf(prefs.printerIp) }
    var printerPort by remember { mutableStateOf(prefs.printerPort.toString()) }
    var barcodeOn by remember { mutableStateOf(prefs.barcodeEnabled) }
    var barcodeMode by remember { mutableStateOf(prefs.barcodeMode) }
    var printMsg by remember { mutableStateOf<String?>(null) }
    // Bluetooth printer state
    val btPrinter = remember { BtPrinter.getInstance() }
    var btAddress by remember { mutableStateOf(prefs.printerBtAddress) }
    var btName by remember { mutableStateOf(prefs.printerBtName) }
    var showBtPicker by remember { mutableStateOf(false) }

    // ── Report & letterhead (A4 PDF reports) ──
    val reportPrefs = remember { ReportPrefs() }
    var lhMode by remember { mutableStateOf(reportPrefs.letterheadMode) }
    var headerMm by remember { mutableStateOf(reportPrefs.headerMm.toString()) }
    var footerMm by remember { mutableStateOf(reportPrefs.footerMm.toString()) }
    var lhAddress by remember { mutableStateOf(reportPrefs.addressLine) }
    var lhPhone by remember { mutableStateOf(reportPrefs.phoneLine) }
    var lhEmail by remember { mutableStateOf(reportPrefs.emailLine) }
    var lhExtra by remember { mutableStateOf(reportPrefs.extraLine) }
    var accent by remember { mutableStateOf(reportPrefs.accentRgb) }
    var reportMsg by remember { mutableStateOf<String?>(null) }

    if (showBtPicker) {
        // Swap the whole screen for the picker page; back restores the settings screen.
        BtPrinterPickerPage(
            btPrinter = btPrinter,
            selectedAddress = btAddress,
            onSelect = { dev ->
                btAddress = dev.address; btName = dev.displayName
                prefs.printerBtAddress = dev.address; prefs.printerBtName = dev.displayName
                showBtPicker = false
            },
            onBack = { showBtPicker = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Billing settings") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.Close, contentDescription = "Back") }
            })
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Numbering series ──
            item { Text("Counter series (this device)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(currentSeries ?: "No series registered on this device yet.",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Each billing counter gets its OWN series (e.g. C1, C2) so two counters can bill offline in parallel with no number collision.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = seriesCode, onValueChange = { seriesCode = it.uppercase() },
                            label = { Text("Series code (e.g. C1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = prefix, onValueChange = { prefix = it.uppercase() },
                            label = { Text("Prefix") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(
                            onClick = {
                                if (registering || seriesCode.isBlank()) return@Button
                                registering = true; msg = null
                                val fy = currentFy()
                                val fmt = "{prefix}-{series}-{seq}"
                                scope.launch {
                                    api.registerCounter(businessId, seriesCode.trim(), prefix.trim().ifBlank { "INV" }, fmt, fy, 0)
                                        .onSuccess { c ->
                                            repo.registerSeriesLocal(businessId, c.seriesCode, c.fy ?: fy, c.prefix ?: prefix, c.numberFormat ?: fmt, c.highWater)
                                            currentSeries = "${c.prefix}-${c.seriesCode} (FY ${c.fy ?: fy}, last #${c.highWater})"
                                            msg = "Series ${c.seriesCode} registered on this device"
                                            registering = false
                                        }.onFailure { msg = it.message ?: "Failed to register"; registering = false }
                                }
                            },
                            enabled = !registering && seriesCode.isNotBlank()
                        ) { Text(if (registering) "Registering…" else "Register series on this device") }
                    }
                }
            }

            // ── Business GST / numbering prefix ──
            item { Text("Business GST", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = taxId, onValueChange = { taxId = it.uppercase() },
                            label = { Text("Business GSTIN (first 2 digits = home state)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(
                            onClick = {
                                if (savingSettings) return@Button
                                savingSettings = true; msg = null
                                val body = "{\"tax_id\":\"${taxId.trim()}\"}"
                                scope.launch {
                                    api.updateSettings(businessId, body)
                                        .onSuccess { msg = "Saved" }.onFailure { msg = it.message ?: "Failed" }
                                    savingSettings = false
                                }
                            },
                            enabled = !savingSettings
                        ) { Text(if (savingSettings) "Saving…" else "Save GSTIN") }
                    }
                }
            }
            msg?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) } }

            // ── Printer ──
            item { Text("Printer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingRow("Receipt printing", printerOn) { printerOn = it; prefs.printerEnabled = it }
                        SettingRow("Auto-print after each bill", autoPrint) { autoPrint = it; prefs.autoPrint = it }
                        Text("Paper width", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChoiceChip("58 mm", paper == 32) { paper = 32; prefs.paperWidth = 32 }
                            ChoiceChip("80 mm", paper == 48) { paper = 48; prefs.paperWidth = 48 }
                            ChoiceChip("A4", paper == 64) { paper = 64; prefs.paperWidth = 64 }
                        }
                        // ── Connection: System (AirPrint/dialog/share) vs raw ESC/POS over LAN ──
                        Text("Connection", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChoiceChip("System / AirPrint", conn == "system") { conn = "system"; prefs.printerConnection = "system" }
                            ChoiceChip("Network (LAN)", conn == "network") { conn = "network"; prefs.printerConnection = "network" }
                            ChoiceChip("Bluetooth", conn == "bluetooth") { conn = "bluetooth"; prefs.printerConnection = "bluetooth" }
                        }
                        if (conn == "network") {
                            OutlinedTextField(
                                value = printerIp,
                                onValueChange = { printerIp = it.trim(); prefs.printerIp = it.trim() },
                                label = { Text("Printer IP (e.g. 192.168.1.50)") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = printerPort,
                                onValueChange = { v -> printerPort = v.filter { it.isDigit() }; printerPort.toIntOrNull()?.let { prefs.printerPort = it } },
                                label = { Text("Port (TVS / Epson = 9100)") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Plug the printer into your router via LAN; print a self-test (hold FEED while powering on) to find its IP. Same network as this device.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (conn == "bluetooth") {
                            if (!btPrinter.isSupported) {
                                Text("Bluetooth printing isn't available on this device — use Network (LAN).",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    if (btName.isNotBlank()) "Selected: $btName" else "No printer selected yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                OutlinedButton(
                                    onClick = { showBtPicker = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Choose printer…") }
                                Text("Turn the printer on, tap Choose printer, then pick it. On iPad the printer must be a BLE printer; classic-only printers can't be reached from iOS.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        val sample = sampleReceipt(paper)
                                        printMsg = withContext(Dispatchers.Default) {
                                            when (conn) {
                                                "network" -> printToNetworkPrinter(printerIp, printerPort.toIntOrNull() ?: 9100, EscPos.encode(sample))
                                                "bluetooth" -> btPrinter.printBytes(btAddress, EscPos.encode(sample))
                                                else -> printReceipt("Test print", sample)
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        printMsg = "Print failed: ${e.message}"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Test print") }
                        printMsg?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        Text("Desktop prints via the system dialog (USB / network / PDF). On Android it opens the print/share sheet. Direct thermal (ESC/POS) printers are coming next.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Report & letterhead (A4 PDF lab reports) ──
            item { Text("Report & letterhead", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "A4 lab reports print as a styled PDF. The lab name on the letterhead is always \"$labName\" (set by your license).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Letterhead", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChoiceChip("Print letterhead", lhMode == "printed") {
                                lhMode = "printed"; reportPrefs.letterheadMode = "printed"
                            }
                            ChoiceChip("Pre-printed letterpad", lhMode == "preprinted") {
                                lhMode = "preprinted"; reportPrefs.letterheadMode = "preprinted"
                            }
                        }
                        Text(
                            if (lhMode == "preprinted")
                                "Nothing is drawn in the header/footer areas — the space below is reserved blank so results land under your letterpad's printed header."
                            else
                                "The app draws the letterhead (accent band, lab name, the lines below) inside the header space on every page.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = headerMm,
                                onValueChange = { v ->
                                    headerMm = v.filter { it.isDigit() }.take(3)
                                    headerMm.toIntOrNull()?.let { reportPrefs.headerMm = it }
                                },
                                label = { Text("Header space (mm)") }, singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = footerMm,
                                onValueChange = { v ->
                                    footerMm = v.filter { it.isDigit() }.take(3)
                                    footerMm.toIntOrNull()?.let { reportPrefs.footerMm = it }
                                },
                                label = { Text("Footer space (mm)") }, singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = lhAddress, onValueChange = { lhAddress = it; reportPrefs.addressLine = it },
                            label = { Text("Address line") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = lhPhone, onValueChange = { lhPhone = it; reportPrefs.phoneLine = it },
                                label = { Text("Phone") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = lhEmail, onValueChange = { lhEmail = it; reportPrefs.emailLine = it },
                                label = { Text("Email") }, singleLine = true, modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = lhExtra, onValueChange = { lhExtra = it; reportPrefs.extraLine = it },
                            label = { Text("Extra line (NABL / GSTIN / tagline)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Accent colour", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ReportPalette.presets.forEach { (name, rgb) ->
                                AccentSwatch(
                                    rgb = rgb, name = name, selected = accent == rgb,
                                    onClick = { accent = rgb; reportPrefs.accentRgb = rgb },
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    reportMsg = "Rendering sample…"
                                    reportMsg = try {
                                        withContext(Dispatchers.Default) {
                                            val path = writeLabReportPdf(
                                                sampleReportDoc(
                                                    labName = labName,
                                                    mode = reportPrefs.mode(),
                                                    headerMm = reportPrefs.headerMm.toFloat(),
                                                    footerMm = reportPrefs.footerMm.toFloat(),
                                                    accentRgb = reportPrefs.accentRgb,
                                                    letterheadLines = reportPrefs.letterheadLines(),
                                                )
                                            )
                                            if (path.isBlank()) "PDF reports arrive on iOS later" else openPdf(path)
                                        }
                                    } catch (e: Throwable) {
                                        "Preview failed: ${e.message}"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Preview sample report") }
                        reportMsg?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }

            // ── Barcode scanner ──
            item { Text("Barcode scanner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingRow("Enable barcode scanner", barcodeOn) { barcodeOn = it; prefs.barcodeEnabled = it }
                        Text("Scanner type", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChoiceChip("USB / Bluetooth", barcodeMode == "wedge") { barcodeMode = "wedge"; prefs.barcodeMode = "wedge" }
                            ChoiceChip("Camera", barcodeMode == "camera") { barcodeMode = "camera"; prefs.barcodeMode = "camera" }
                        }
                        Text("Scan a product barcode to add it to the open bill. USB/Bluetooth scanners act as a keyboard on every platform; camera scanning (Android/iOS) is being wired up.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Lab data sync (P3) ──
            if (labSync != null) {
                item { Text("Lab data sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                item {
                    val syncState by labSync.state.collectAsState()
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (syncState.disabled)
                                    "Sync is off — this license is standalone (not linked to a BNM business). Everything keeps working fully offline."
                                else
                                    "Backs up patients, orders and results to BNM, converges other seats, and exchanges EMR orders with partner clinics. The app never needs it to work.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Last synced: ${syncState.lastSyncAt?.replace('T', ' ')?.take(16) ?: "never"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(
                                onClick = { scope.launch { labSync.syncNow() } },
                                enabled = !syncState.syncing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (syncState.syncing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                                Text(if (syncState.syncing) "Syncing…" else "Sync now")
                            }
                            syncState.lastError?.let {
                                Text("Last attempt failed: $it", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // ── Data ──
            item { Text("Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Seeing old or already-deleted items after a data re-import? Clear this device's local cache and pull everything fresh from the server.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = { showClearConfirm = true },
                            enabled = !clearing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (clearing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                            Text(if (clearing) "Clearing & re-syncing…" else "Clear local data & re-sync")
                        }
                        clearMsg?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }

            // ── License ──
            if (onOpenLicense != null) {
                item { Text("License", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "See who this copy of BNM Diagnosis is licensed to and manage the devices using its seats.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onOpenLicense, modifier = Modifier.fillMaxWidth()) {
                                Text("License & devices")
                            }
                        }
                    }
                }
            }

            // ── Account / sign out ──
            item { Text("Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            item {
                OutlinedButton(
                    onClick = { showLogoutConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Sign out this device") }
            }
        }
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

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
        Text(name, style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sampleReceipt(width: Int): String = buildString {
    val w = width.coerceIn(24, 80)
    fun center(s: String) = appendLine(" ".repeat(((w - s.length) / 2).coerceAtLeast(0)) + s)
    fun rule() = appendLine("-".repeat(w))
    center("TEST PRINT")
    rule()
    appendLine("Sample item".padEnd(w - 7) + "₹100.00")
    appendLine("Another item".padEnd(w - 6) + "₹50.00")
    rule()
    appendLine("TOTAL".padEnd(w - 7) + "₹150.00")
    rule()
    center("Printer is working!")
}
