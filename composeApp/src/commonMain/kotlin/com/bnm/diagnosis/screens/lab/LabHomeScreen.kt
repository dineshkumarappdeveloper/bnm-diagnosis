package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.lab.CriticalResult
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.lab.WorklistEntry
import com.bnm.diagnosis.sync.LabSyncEngine
import com.bnm.diagnosis.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Desktop-first breakpoint: ≥ this width the home renders as a two-column
 *  dashboard; below it, the stacked phone layout. */
private val WIDE_BREAKPOINT = 900.dp

/**
 * LIMS home — the app's main surface. Desktop (primary target) gets a REAL
 * dashboard: header band with the lab identity + New order/New patient CTAs,
 * a KPI row of today's pipeline counters (deep-linked into the worklist), an
 * open-orders table, and a right rail with critical results, the EMR inbox,
 * license & sync, and shortcuts. Narrow screens (Android) keep the stacked
 * layout. 100% offline (LabRepository); sync/EMR/billing bits are additive.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LabHomeScreen(
    labName: String,
    licenseBlocked: Boolean,
    accessionNotice: String?,
    onNoticeShown: () -> Unit,
    onNewOrder: () -> Unit,
    onWorklist: (tab: String) -> Unit,
    onPatients: () -> Unit,
    onReferrers: () -> Unit,
    onCatalog: () -> Unit,
    onBills: () -> Unit,
    onSettings: () -> Unit,
    /** P3: opens the EMR inbox (badge shows only when something is pending). */
    onEmrInbox: () -> Unit = {},
    /** Opens one order's workbench (dashboard worklist rows + criticals). */
    onOpenOrder: (orderId: String) -> Unit = {},
    /** Registration shortcut — the Patients screen carries the add form. */
    onNewPatient: () -> Unit = {},
    /** License & devices management (also reachable via Settings). */
    onLicenseDevices: () -> Unit = {},
    /** License identity for the header chip + License & sync card. */
    licenseMode: String? = null,
    licenseSeats: Int = 0,
    /** Bills-today KPI counts this business's local invoice docs ("" = standalone). */
    businessId: String = "",
    /** P3: the lab sync spine — last-synced/Sync now; null hides all sync UI. */
    labSync: LabSyncEngine? = null,
) {
    val repo = LocalLabRepository.current
    val billingRepo = LocalBillingRepository.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val emrPending by remember(repo) { repo.emrPendingCountFlow() }.collectAsState(0L)
    val openOrders by remember(repo) { repo.openOrdersFlow(12) }.collectAsState(emptyList())
    val criticals by remember(repo) { repo.criticalsTodayFlow(6) }.collectAsState(emptyList())
    val syncState = if (labSync != null) labSync.state.collectAsState().value else null

    // Today's pipeline counters (orders CREATED today, per stage).
    var registered by remember { mutableStateOf(0L) }
    var inProgress by remember { mutableStateOf(0L) }
    var awaitingVerify by remember { mutableStateOf(0L) }
    var approved by remember { mutableStateOf(0L) }
    var reported by remember { mutableStateOf(0L) }
    var billsToday by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        runCatching {
            registered = repo.countByStatusToday(LabStatus.REGISTERED) + repo.countByStatusToday(LabStatus.COLLECTED)
            inProgress = repo.countByStatusToday(LabStatus.IN_PROGRESS)
            awaitingVerify = repo.countByStatusToday(LabStatus.ENTERED)
            approved = repo.countByStatusToday(LabStatus.APPROVED)
            reported = repo.countByStatusToday(LabStatus.REPORTED)
        }
        runCatching { billsToday = billingRepo.countInvoicesToday(businessId) }
    }

    // Post-registration confirmation: the accession number, prominent.
    LaunchedEffect(accessionNotice) {
        if (accessionNotice != null) {
            snackbar.showSnackbar("Order registered — accession $accessionNotice")
            onNoticeShown()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_BREAKPOINT
        Scaffold(
            topBar = {
                if (!wide) TopAppBar(title = {
                    Column {
                        Text("BNM Diagnosis", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(labName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                })
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { inner ->
            if (wide) Column(
                Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 1400.dp).fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // ── Header band: identity left, primary actions right ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("BNM DIAGNOSIS", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Text(labName, style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(todayLabel(), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                LicenseModeChip(licenseMode)
                            }
                        }
                        OutlinedButton(onClick = onNewPatient) {
                            Icon(Icons.Outlined.PersonAddAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  New patient")
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = onNewOrder,
                            enabled = !licenseBlocked,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text("  New order", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (licenseBlocked) Text(
                        "License deactivated — registering new orders is disabled. Everything else stays available.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )

                    // ── KPI row: today's pipeline → the matching worklist tab ──
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        KpiCard("Registered", registered, Icons.Outlined.PersonAddAlt, KpiTint.Neutral,
                            Modifier.weight(1f)) { onWorklist(LabStatus.REGISTERED) }
                        KpiCard("In progress", inProgress, Icons.Outlined.Science, KpiTint.Info,
                            Modifier.weight(1f)) { onWorklist(LabStatus.IN_PROGRESS) }
                        KpiCard("Awaiting verify", awaitingVerify, Icons.Outlined.PendingActions, KpiTint.Warning,
                            Modifier.weight(1f)) { onWorklist(LabStatus.ENTERED) }
                        KpiCard("Approved", approved, Icons.Outlined.TaskAlt, KpiTint.Success,
                            Modifier.weight(1f)) { onWorklist(LabStatus.APPROVED) }
                        KpiCard("Reported", reported, Icons.Outlined.Description, KpiTint.Teal,
                            Modifier.weight(1f)) { onWorklist(LabStatus.REPORTED) }
                        billsToday?.let { bills ->
                            KpiCard("Bills today", bills, Icons.AutoMirrored.Outlined.ReceiptLong, KpiTint.Neutral,
                                Modifier.weight(1f)) { onBills() }
                        }
                    }

                    // ── Main grid: worklist table left, action rail right ──
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(Modifier.weight(0.65f)) {
                            OpenOrdersTable(openOrders, onOpenOrder = onOpenOrder,
                                onViewAll = { onWorklist(LabStatus.REGISTERED) })
                        }
                        Column(Modifier.weight(0.35f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CriticalsCard(criticals, onOpenOrder)
                            if (syncState != null && !syncState.disabled) {
                                EmrCard(emrPending, onEmrInbox)
                            }
                            LicenseSyncCard(
                                labName = labName, licenseMode = licenseMode, licenseSeats = licenseSeats,
                                syncDisabled = syncState?.disabled,
                                lastSyncAt = syncState?.lastSyncAt,
                                syncing = syncState?.syncing == true,
                                lastError = syncState?.lastError,
                                onSyncNow = labSync?.let { s -> { scope.launch { s.syncNow() } } },
                                onLicenseDevices = onLicenseDevices,
                            )
                            ShortcutsCard(
                                onPatients = onPatients, onReferrers = onReferrers,
                                onCatalog = onCatalog, onBills = onBills, onSettings = onSettings,
                            )
                        }
                    }
                }
            }
            else Column(
                Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── Today counters → worklist tabs ──
                Text("Today", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatChip("Registered", registered) { onWorklist(LabStatus.REGISTERED) }
                    StatChip("In progress", inProgress) { onWorklist(LabStatus.IN_PROGRESS) }
                    StatChip("Awaiting verify", awaitingVerify) { onWorklist(LabStatus.ENTERED) }
                    StatChip("Approved", approved) { onWorklist(LabStatus.APPROVED) }
                    StatChip("Reported", reported) { onWorklist(LabStatus.REPORTED) }
                    billsToday?.let { bills -> StatChip("Bills today", bills) { onBills() } }
                }

                // ── P3: EMR bridge badge — clinic orders awaiting registration ──
                if (emrPending > 0) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).clickable(onClick = onEmrInbox),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Biotech, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(22.dp))
                            Text(
                                "  EMR orders: $emrPending pending — tap to review",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }

                // ── Primary action: register a new order (full-width is fine on phones) ──
                Button(
                    onClick = onNewOrder,
                    enabled = !licenseBlocked,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                    contentPadding = PaddingValues(vertical = 18.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  New order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (licenseBlocked) {
                    Text(
                        "License deactivated — registering new orders is disabled. Everything else stays available.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }

                // ── Open orders (compact list, cap 6) ──
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Open orders", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onWorklist(LabStatus.REGISTERED) }) { Text("View all") }
                }
                if (openOrders.isEmpty()) {
                    Text("No open orders — start with New order",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        openOrders.take(6).forEach { e -> CompactOrderRow(e) { onOpenOrder(e.order.id) } }
                    }
                }

                // ── Critical results today ──
                CriticalsCard(criticals, onOpenOrder)

                // ── Navigation cards ──
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeCard("Worklist", "Pipeline by stage", Icons.AutoMirrored.Outlined.ListAlt) { onWorklist(LabStatus.REGISTERED) }
                    HomeCard("Patients", "Search & manage", Icons.Outlined.PersonAddAlt) { onPatients() }
                    HomeCard("Referrers", "Doctors & clinics", Icons.Outlined.People) { onReferrers() }
                    HomeCard("Test catalog", "Tests, panels & prices", Icons.Outlined.Biotech) { onCatalog() }
                    HomeCard("Bills", "GST invoices", Icons.AutoMirrored.Outlined.ReceiptLong) { onBills() }
                    HomeCard("Settings", "Printer · License", Icons.Outlined.Settings) { onSettings() }
                }

                // ── P3: one-line sync note (standalone license → sync disabled) ──
                if (syncState?.disabled == true) {
                    Text("Sync off — standalone license (not linked to a BNM business).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Wide-layout building blocks
// ─────────────────────────────────────────────────────────────────────────────

/** KPI accent family — all resolved from AppTheme tokens (no raw hex). */
private enum class KpiTint { Neutral, Info, Warning, Success, Teal }

@Composable
private fun kpiColors(tint: KpiTint): Pair<Color, Color> {
    val c = AppTheme.colors
    val teal = lerp(c.info, c.success, 0.45f)
    return when (tint) {
        KpiTint.Neutral -> c.surfaceMuted to c.textSecondary
        KpiTint.Info -> c.infoSoft to c.info
        KpiTint.Warning -> c.warningSoft to c.warning
        KpiTint.Success -> c.successSoft to c.accentTextOnSoft
        KpiTint.Teal -> teal.copy(alpha = if (c.isDark) 0.25f else 0.14f) to teal
    }
}

@Composable
private fun KpiCard(
    label: String,
    count: Long,
    icon: ImageVector,
    tint: KpiTint,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val (bg, fg) = kpiColors(tint)
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(34.dp).background(bg, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(19.dp))
            }
            Text("$count", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Pipeline status chip. registered/collected = neutral, in_progress = blue,
 *  entered = amber, verified = teal (info→success blend), approved = green.
 *  An urgent/stat priority prepends a red dot. All colors are theme tokens. */
@Composable
private fun LabStatusChip(status: String, priority: String) {
    val c = AppTheme.colors
    val teal = lerp(c.info, c.success, 0.45f)
    val (bg, fg) = when (status) {
        LabStatus.IN_PROGRESS -> c.infoSoft to c.info
        LabStatus.ENTERED -> c.warningSoft to c.warning
        LabStatus.VERIFIED -> teal.copy(alpha = if (c.isDark) 0.25f else 0.14f) to teal
        LabStatus.APPROVED -> c.successSoft to c.accentTextOnSoft
        else -> c.surfaceMuted to c.textSecondary // registered / collected / anything else
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (!priority.equals("routine", ignoreCase = true)) {
            Box(Modifier.size(7.dp).background(c.danger, CircleShape))
        }
        Box(Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text(
                status.replace('_', ' ').replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg,
            )
        }
    }
}

/** Desktop worklist: a real table of the open (non-terminal) orders. */
@Composable
private fun OpenOrdersTable(
    entries: List<WorklistEntry>,
    onOpenOrder: (String) -> Unit,
    onViewAll: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Worklist — open orders", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onViewAll) { Text("View all") }
            }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp),
                    contentAlignment = Alignment.Center) {
                    Text("No open orders — start with New order",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Column header
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableHeadCell("ACCESSION", Modifier.width(150.dp))
                    TableHeadCell("PATIENT", Modifier.weight(1f))
                    TableHeadCell("TESTS", Modifier.width(52.dp))
                    TableHeadCell("STATUS", Modifier.width(120.dp))
                    TableHeadCell("REGISTERED", Modifier.width(92.dp))
                    Spacer(Modifier.width(64.dp))
                }
                entries.forEach { e ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenOrder(e.order.id) }
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            e.order.accessionNo,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(150.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(e.patientName, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ageSexLabel(e.patientDob, e.patientAgeYears, e.patientSex),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${e.testCount}", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(52.dp))
                        Box(Modifier.width(120.dp)) { LabStatusChip(e.order.status, e.order.priority) }
                        Text(shortTimeLabel(e.order.createdAt), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(92.dp))
                        TextButton(onClick = { onOpenOrder(e.order.id) }) { Text("Open") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeadCell(label: String, modifier: Modifier) {
    Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Attention card: today's CL/CH results — the phone-the-patient list. */
@Composable
private fun CriticalsCard(criticals: List<CriticalResult>, onOpenOrder: (String) -> Unit) {
    val c = AppTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, c.danger.copy(alpha = if (criticals.isEmpty()) 0.2f else 0.45f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).background(c.dangerSoft, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null,
                        tint = c.danger, modifier = Modifier.size(17.dp))
                }
                Text("Critical results today", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
            }
            if (criticals.isEmpty()) {
                Text("No critical results today", style = MaterialTheme.typography.bodySmall,
                    color = c.success, fontWeight = FontWeight.Medium)
            } else {
                criticals.forEach { r ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpenOrder(r.orderId) }.padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(r.patientName, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            FlagChip(r.flag)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${r.parameterKey} · ${r.value.orEmpty()}${r.unit?.let { " $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            r.patientPhone?.takeIf { it.isNotBlank() }?.let { phone ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(Icons.Outlined.Call, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp))
                                    Text(phone, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** EMR bridge card (wide layout): pending clinic orders + inbox entry. */
@Composable
private fun EmrCard(pending: Long, onEmrInbox: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEmrInbox),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).background(AppTheme.colors.infoSoft, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Biotech, contentDescription = null,
                        tint = AppTheme.colors.info, modifier = Modifier.size(17.dp))
                }
                Text("EMR orders", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onEmrInbox) { Text("Open inbox") }
            }
            Text(
                if (pending > 0) "$pending pending — clinic orders awaiting registration"
                else "No pending clinic orders",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (pending > 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (pending > 0) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** License identity + sync health (wide layout right rail). */
@Composable
private fun LicenseSyncCard(
    labName: String,
    licenseMode: String?,
    licenseSeats: Int,
    syncDisabled: Boolean?,
    lastSyncAt: String?,
    syncing: Boolean,
    lastError: String?,
    onSyncNow: (() -> Unit)?,
    onLicenseDevices: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.VerifiedUser, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(17.dp))
                }
                Text("License & sync", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text("Licensed to", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(labName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LicenseModeChip(licenseMode)
                if (licenseSeats > 0) Text(
                    "$licenseSeats seat${if (licenseSeats == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                syncDisabled == true -> Text(
                    "Sync off — standalone license (not linked to a BNM business).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onSyncNow != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Last synced: ${lastSyncAt?.replace('T', ' ')?.take(16) ?: "never"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onSyncNow, enabled = !syncing) {
                            if (syncing) CircularProgressIndicator(
                                Modifier.padding(end = 6.dp).size(14.dp), strokeWidth = 2.dp)
                            Text(if (syncing) "Syncing…" else "Sync now")
                        }
                    }
                    lastError?.let {
                        Text("Last attempt failed: $it", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = onLicenseDevices, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("License & devices")
            }
        }
    }
}

/** Small read-only pill naming the license mode. */
@Composable
private fun LicenseModeChip(mode: String?) {
    val label = when (mode?.lowercase()) {
        "perpetual" -> "Perpetual license"
        "subscription" -> "Subscription"
        null -> "Licensed"
        else -> mode.replaceFirstChar { it.uppercase() }
    }
    Box(
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Remaining navigation as compact icon+label tiles (wide layout right rail). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutsCard(
    onPatients: () -> Unit,
    onReferrers: () -> Unit,
    onCatalog: () -> Unit,
    onBills: () -> Unit,
    onSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Shortcuts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                ShortcutTile("Patients", Icons.Outlined.PersonAddAlt, Modifier.weight(1f), onPatients)
                ShortcutTile("Referrers", Icons.Outlined.People, Modifier.weight(1f), onReferrers)
                ShortcutTile("Test catalog", Icons.Outlined.Biotech, Modifier.weight(1f), onCatalog)
                ShortcutTile("Bills", Icons.AutoMirrored.Outlined.ReceiptLong, Modifier.weight(1f), onBills)
                ShortcutTile("Settings", Icons.Outlined.Settings, Modifier.weight(1f), onSettings)
            }
        }
    }
}

@Composable
private fun ShortcutTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Narrow-layout building blocks
// ─────────────────────────────────────────────────────────────────────────────

/** Compact open-order row for the phone layout. */
@Composable
private fun CompactOrderRow(e: WorklistEntry, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(e.order.accessionNo, style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                LabStatusChip(e.order.status, e.order.priority)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${e.patientName} · ${ageSexLabel(e.patientDob, e.patientAgeYears, e.patientSex)}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${e.testCount} test${if (e.testCount == 1L) "" else "s"} · ${shortTimeLabel(e.order.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, count: Long, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun HomeCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(170.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/** "Mon, 25 Aug 2026" for the header band. */
private fun todayLabel(): String {
    val d = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val dows = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val mons = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${dows[d.dayOfWeek.ordinal]}, ${d.day} ${mons[d.month.ordinal]} ${d.year}"
}
