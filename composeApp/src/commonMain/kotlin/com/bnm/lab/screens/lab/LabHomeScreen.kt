package com.bnm.lab.screens.lab

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.bnm.lab.chat.LocalBillingRepository
import com.bnm.lab.lab.CommissionRollup
import com.bnm.lab.lab.CriticalResult
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.lab.LocalLabRepository
import com.bnm.lab.lab.WorklistEntry
import com.bnm.lab.revenue.RevenueRange
import com.bnm.lab.revenue.RevenueReport
import com.bnm.lab.revenue.RevenueRepository
import com.bnm.lab.revenue.inr
import com.bnm.lab.staff.LabPermission
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.allows
import com.bnm.lab.sync.LabSyncEngine
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.util.formatDecimal1
import com.bnm.lab.util.formatDecimal2
import com.bnm.lab.chat.InvoiceBalance
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import com.bnm.lab.lab.DiagnosisPrefs
import com.bnm.lab.lab.TenantRowCounts
import com.bnm.lab.license.EditionSwitch
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.license.editionNoticeBody
import com.bnm.lab.license.editionNoticeTitle
import com.bnm.lab.license.editionSwitch

/** Desktop-first breakpoint: ≥ this width the home renders as a two-column
 *  dashboard; below it, the stacked phone layout. */
private val WIDE_BREAKPOINT = 900.dp

/** One home-worklist tab: label + the statuses it aggregates. `statuses = null`
 *  is the "Open" pipeline view (openOrdersFlow: registered → approved).
 *  "Registered" folds in `collected` so sample-collected orders never vanish
 *  between tabs. */
private data class HomeWorklistTab(val label: String, val statuses: List<String>?)

private val HOME_TABS = listOf(
    HomeWorklistTab("Open", null),
    HomeWorklistTab("Registered", listOf(LabStatus.REGISTERED, LabStatus.COLLECTED)),
    HomeWorklistTab("In progress", listOf(LabStatus.IN_PROGRESS)),
    HomeWorklistTab("Entered", listOf(LabStatus.ENTERED)),
    HomeWorklistTab("Verified", listOf(LabStatus.VERIFIED)),
    HomeWorklistTab("Approved", listOf(LabStatus.APPROVED)),
    HomeWorklistTab("Reported", listOf(LabStatus.REPORTED)),
)

// The attention bar's "awaiting verification" card jumps straight to this tab.
private const val TAB_ENTERED = 3

/** Statuses the "Open" pipeline tab aggregates (mirrors openOrders in LabOrders.sq). */
private val OPEN_STATUSES = listOf(
    LabStatus.REGISTERED, LabStatus.COLLECTED, LabStatus.IN_PROGRESS,
    LabStatus.ENTERED, LabStatus.VERIFIED, LabStatus.APPROVED,
)

/** One tab's badge, derived from the single statusCountsFlow map. */
private fun tabCount(tab: HomeWorklistTab, counts: Map<String, Long>): Long =
    (tab.statuses ?: OPEN_STATUSES).sumOf { counts[it] ?: 0L }

/**
 * LIMS home — the app's main surface. Desktop (primary target) gets a REAL
 * dashboard: header band with the lab identity + New order/New patient CTAs,
 * an ATTENTION bar (criticals / awaiting-verification / EMR-pending / sync-off
 * cards, each shown only when actionable), THE worklist itself — a
 * status-tabbed table filling the viewport whose tabs carry live counts (the
 * KPI card row is retired; one statusCountsFlow feeds every badge) — and a
 * right rail with critical results, the EMR inbox, license & sync, and
 * shortcuts. Narrow screens (Android) keep the stacked layout with the same
 * counted tab chips over a compact list. 100% offline (LabRepository);
 * sync/EMR/billing bits are additive.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LabHomeScreen(
    labName: String,
    /** Why this seat may not register new orders (licence lapsed or blocked);
     *  null = allowed. Shown beside the disabled New order button. */
    newWorkLockedNote: String?,
    accessionNotice: String?,
    onNoticeShown: () -> Unit,
    onNewOrder: () -> Unit,
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
    /** Subscription runway notice (null = perpetual or comfortably active). */
    subscriptionNotice: String? = null,
    /** True once the subscription is in grace/expired — renders as danger. */
    subscriptionUrgent: Boolean = false,
    /** License mode for the header chip (the full License & sync card lives in Settings). */
    licenseMode: String? = null,
    /** Bills-shortcut "N today" caption counts this business's local invoice docs ("" = standalone). */
    businessId: String = "",
    /** P3: the lab sync spine — last-synced/Sync now; null hides all sync UI. */
    labSync: LabSyncEngine? = null,
    /** P4: who is signed in on this seat — header chip only; null hides it. */
    signedInStaff: Staff? = null,
    /** Header chip ▸ "Switch user" — drops the session, back to the sign-in grid. */
    onSwitchUser: () -> Unit = {},
    /** Header chip ▸ "Sign out" — same session drop, different intent. */
    onSignOut: () -> Unit = {},
    /** Revenue dashboard source; null hides the revenue tile and shortcut. */
    revenue: RevenueRepository? = null,
    /** Opens the revenue dashboard (owner-only route). */
    onRevenue: () -> Unit = {},
) {
    val repo = LocalLabRepository.current
    // A lab that has just been moved between editions is told once, in plain
    // words, what changes about where its records live. Nothing to say on an
    // ordinary start: the stored edition already matches the licence.
    val diagPrefs = remember { DiagnosisPrefs() }
    val currentEdition = remember(licenseMode) { LicenseManager().state.value.edition }
    var editionNotice by remember { mutableStateOf(EditionSwitch.NONE) }
    var editionCounts by remember { mutableStateOf<TenantRowCounts?>(null) }
    LaunchedEffect(currentEdition) {
        val switch = editionSwitch(diagPrefs.lastSeenEdition, currentEdition)
        if (switch != EditionSwitch.NONE) {
            editionCounts = runCatching { repo.tenantRowCounts() }.getOrNull()
            editionNotice = switch
        }
        // Record it either way, including the very first run, so the notice
        // fires on a real change and never on an upgrade of the app itself.
        diagPrefs.lastSeenEdition = currentEdition
    }
    val billingRepo = LocalBillingRepository.current
    // invoiceId → balance, so every order row can show whether money is still
    // owed. Worklist rows come from SQL while invoices are JSON docs in another
    // table, so the join happens here rather than in a query.
    val balances by billingRepo.invoiceBalancesFlow(businessId).collectAsState(emptyList())
    val balanceByInvoice = remember(balances) { balances.associateBy { it.invoice.id } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val emrPending by remember(repo) { repo.emrPendingCountFlow() }.collectAsState(0L)
    val criticals by remember(repo) { repo.criticalsTodayFlow(6) }.collectAsState(emptyList())
    val syncState = if (labSync != null) labSync.state.collectAsState().value else null

    // The worklist IS the home's main panel: one selected tab, fully reactive.
    // "Open" rides openOrdersFlow (uncapped — the panel scrolls internally);
    // status tabs merge their worklistFlow(s) (Registered folds in collected).
    var worklistTab by remember { mutableStateOf(0) }
    val tabEntries by remember(repo, worklistTab) {
        val statuses = HOME_TABS[worklistTab].statuses
        if (statuses == null) repo.openOrdersFlow(Long.MAX_VALUE)
        else statuses.map { repo.worklistFlow(it) }
            .reduce { acc, f -> acc.combine(f) { a, b -> a + b } }
    }.collectAsState(emptyList())
    // distinctBy guards the brief double-emission while an order hops status.
    val worklist = remember(tabEntries) {
        tabEntries.distinctBy { it.order.id }.sortedByDescending { it.order.createdAt }
    }

    // ONE reactive per-status rollup feeds every tab badge + the attention bar.
    val statusCounts by remember(repo) { repo.statusCountsFlow() }.collectAsState(emptyMap())

    // ── Commission, month to date (feedback item 7: it belongs on the dashboard) ──
    // Gated by the SHARED rule, not a local one: LabPermission.MONEY is the same
    // check RouteGuard puts in front of the Referrers route this tile links to,
    // so the tile can never offer a door the person cannot walk through.
    val showMoney = signedInStaff.allows(LabPermission.MONEY)
    // Deliberately NOT remembered: recomputing is trivial and it means the range
    // rolls over on its own in a session left open across month end.
    val monthFrom = firstOfMonth(todayLocal()).toString()
    val monthTo = todayLocal().toString()
    var commission by remember { mutableStateOf<CommissionRollup?>(null) }
    // Re-read whenever the pipeline moves — registering an order changes both the
    // status counts and what the lab owes.
    LaunchedEffect(repo, showMoney, monthFrom, monthTo, statusCounts) {
        commission = if (!showMoney) null
        else runCatching { repo.commissionRollup(monthFrom, monthTo) }.getOrNull()
    }

    // ── Revenue today (owner-only, same gate as the Revenue route) ──
    // Reactive: a bill saved or a balance collected at the desk moves the tile
    // without a refresh. Today's date is re-read each composition so the tile
    // rolls over at midnight in a session left open.
    val showRevenue = revenue != null && signedInStaff.allows(LabPermission.REVENUE)
    val todayRange = todayLocal().let { RevenueRange(it, it) }
    val revenueToday by remember(revenue, showRevenue, businessId, todayRange) {
        if (revenue == null || !showRevenue) kotlinx.coroutines.flow.flowOf(null)
        else revenue.reportFlow(businessId, todayRange)
    }.collectAsState(null)

    // Bills-shortcut live caption ("N today"); null/0 falls back to the static label.
    var billsToday by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        runCatching { billsToday = billingRepo.countInvoicesToday(businessId) }
    }
    val billsCaption = billsToday?.takeIf { it > 0 }?.let { "$it today" } ?: "GST invoices"

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
                if (!wide) TopAppBar(
                    title = {
                        Column {
                            Text("BNM Lab", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(labName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    actions = {
                        StaffHeaderChip(
                            staff = signedInStaff,
                            onSwitchUser = onSwitchUser,
                            onSignOut = onSignOut,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { inner ->
            if (wide) Column(
                Modifier.padding(inner).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 1400.dp).fillMaxWidth().weight(1f)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // ── Header band: identity left, primary actions right ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("BNM LAB", style = MaterialTheme.typography.labelMedium,
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
                        StaffHeaderChip(
                            staff = signedInStaff,
                            onSwitchUser = onSwitchUser,
                            onSignOut = onSignOut,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        OutlinedButton(onClick = onNewPatient) {
                            Icon(Icons.Outlined.PersonAddAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  New patient")
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = onNewOrder,
                            enabled = newWorkLockedNote == null,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text("  New order", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (newWorkLockedNote != null) Text(
                        newWorkLockedNote,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )

                    // ── Attention bar: only what needs a human right now (tabs
                    // carry the pipeline numbers; the KPI card row is retired) ──
                    AttentionBar(
                        criticalCount = criticals.size,
                        subscriptionNotice = subscriptionNotice,
                        subscriptionUrgent = subscriptionUrgent,
                        enteredCount = statusCounts[LabStatus.ENTERED] ?: 0L,
                        emrPending = emrPending,
                        syncEnabled = syncState != null && !syncState.disabled,
                        syncDisabled = syncState?.disabled == true,
                        onAwaitingVerify = { worklistTab = TAB_ENTERED },
                        onEmrInbox = onEmrInbox,
                    )

                    // ── Main grid: THE worklist left (fills the viewport, scrolls
                    // internally), action rail right (scrolls on short windows) ──
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Box(Modifier.weight(0.65f).fillMaxHeight()) {
                            WorklistPanel(
                                tabIndex = worklistTab,
                                onTabChange = { worklistTab = it },
                                entries = worklist,
                                counts = statusCounts,
                                onOpenOrder = onOpenOrder,
                                balanceByInvoice = balanceByInvoice,
                            )
                        }
                        Column(Modifier.weight(0.35f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CriticalsCard(criticals, onOpenOrder)
                            if (showRevenue) RevenueTodayCard(revenueToday, onRevenue)
                            if (showMoney) CommissionCard(commission, onReferrers)
                            if (syncState != null && !syncState.disabled) {
                                EmrCard(emrPending, onEmrInbox)
                            }
                            // License & sync lives in Settings — not repeated here.
                            ShortcutsCard(
                                billsCaption = billsCaption,
                                showReferrers = showMoney,
                                showRevenue = showRevenue,
                                onRevenue = onRevenue,
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
                // ── Attention bar: only what needs a human right now (the tab
                // chips below carry the pipeline numbers) ──
                AttentionBar(
                    criticalCount = criticals.size,
                    subscriptionNotice = subscriptionNotice,
                    subscriptionUrgent = subscriptionUrgent,
                    enteredCount = statusCounts[LabStatus.ENTERED] ?: 0L,
                    emrPending = emrPending,
                    syncEnabled = syncState != null && !syncState.disabled,
                    syncDisabled = syncState?.disabled == true,
                    onAwaitingVerify = { worklistTab = TAB_ENTERED },
                    onEmrInbox = onEmrInbox,
                )

                // ── Primary action: register a new order (full-width is fine on phones) ──
                Button(
                    onClick = onNewOrder,
                    enabled = newWorkLockedNote == null,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                    contentPadding = PaddingValues(vertical = 18.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  New order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (newWorkLockedNote != null) {
                    Text(
                        newWorkLockedNote,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }

                // ── Worklist: same tabbed panel, scaled down (chips + list) ──
                Text("Worklist", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                // Narrow chips only badge non-zero tabs (width is precious).
                WorklistTabChips(selected = worklistTab, onSelect = { worklistTab = it },
                    counts = statusCounts, showZeroCounts = false)
                if (worklist.isEmpty()) {
                    Text(emptyWorklistLabel(worklistTab),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        worklist.forEach { e ->
                            CompactOrderRow(
                                e,
                                onOpen = { onOpenOrder(e.order.id) },
                                balance = e.order.invoiceId?.let { balanceByInvoice[it] },
                            )
                        }
                    }
                }

                // ── Critical results today ──
                CriticalsCard(criticals, onOpenOrder)

                // ── Revenue today ──
                if (showRevenue) RevenueTodayCard(revenueToday, onRevenue)

                // ── Commission, month to date ──
                if (showMoney) CommissionCard(commission, onReferrers)

                // ── Navigation cards ──
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeCard("Patients", "Search & manage", Icons.Outlined.PersonAddAlt) { onPatients() }
                    // Owner-only money surface (RouteGuard refuses everyone else): no
                    // door for people who cannot walk through it.
                    if (showMoney) HomeCard("Referrers", "Doctors & clinics", Icons.Outlined.People) { onReferrers() }
                    if (showRevenue) HomeCard("Revenue", "Billed · collected · due", Icons.Outlined.BarChart) { onRevenue() }
                    HomeCard("Test catalog", "Tests, panels & prices", Icons.Outlined.Biotech) { onCatalog() }
                    HomeCard("Bills", billsCaption, Icons.AutoMirrored.Outlined.ReceiptLong) { onBills() }
                    HomeCard("Settings", "Printer · License", Icons.Outlined.Settings) { onSettings() }
                }
                // (The standalone "Sync off" footer note now lives in the attention bar.)
            }
        }
    }

    // ── Edition changed: say it once, then never again ──
    editionNoticeTitle(editionNotice)?.let { title ->
        AlertDialog(
            onDismissRequest = { editionNotice = EditionSwitch.NONE },
            title = { Text(title) },
            text = { Text(editionNoticeBody(editionNotice, editionCounts), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = { editionNotice = EditionSwitch.NONE }) { Text("Got it") }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Wide-layout building blocks
// ─────────────────────────────────────────────────────────────────────────────

/** Important-notifications band (both layouts): compact alert cards, each
 *  rendered ONLY when actionable — sourced from flows the screen already
 *  collects (no extra polling). When nothing applies, a single slim all-clear
 *  line keeps the band from looking broken. All colors are AppTheme tokens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttentionBar(
    criticalCount: Int,
    subscriptionNotice: String? = null,
    subscriptionUrgent: Boolean = false,
    enteredCount: Long,
    emrPending: Long,
    syncEnabled: Boolean,
    syncDisabled: Boolean,
    onAwaitingVerify: () -> Unit,
    onEmrInbox: () -> Unit,
) {
    val c = AppTheme.colors
    val anyAlert = criticalCount > 0 || enteredCount > 0 ||
        (emrPending > 0 && syncEnabled) || syncDisabled || subscriptionNotice != null
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (subscriptionNotice != null) AttentionCard(
            icon = Icons.Outlined.WarningAmber,
            bg = if (subscriptionUrgent) c.dangerSoft else c.warningSoft,
            fg = if (subscriptionUrgent) c.danger else c.warning,
            label = subscriptionNotice,
        )
        if (criticalCount > 0) AttentionCard(
            icon = Icons.Outlined.WarningAmber, bg = c.dangerSoft, fg = c.danger,
            count = criticalCount.toLong(),
            label = if (criticalCount == 1) "critical result today — call the patient"
            else "critical results today — call the patients",
            caption = "see panel →",
        )
        if (enteredCount > 0) AttentionCard(
            icon = Icons.Outlined.PendingActions, bg = c.warningSoft, fg = c.warning,
            count = enteredCount, label = "awaiting verification",
            onClick = onAwaitingVerify,
        )
        if (emrPending > 0 && syncEnabled) AttentionCard(
            icon = Icons.Outlined.Biotech, bg = c.infoSoft, fg = c.info,
            count = emrPending,
            label = if (emrPending == 1L) "EMR order pending" else "EMR orders pending",
            onClick = onEmrInbox,
        )
        if (syncDisabled) AttentionCard(
            icon = Icons.Outlined.CloudOff, bg = c.surfaceMuted, fg = c.textSecondary,
            label = "Sync off — standalone license",
        )
        if (!anyAlert) AttentionCard(
            icon = Icons.Outlined.TaskAlt, bg = c.successSoft, fg = c.accentTextOnSoft,
            label = "All clear — nothing needs attention",
        )
    }
}

/** One attention card: icon + bold count + short label (+ optional caption). */
@Composable
private fun AttentionCard(
    icon: ImageVector,
    bg: Color,
    fg: Color,
    label: String,
    count: Long? = null,
    caption: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            if (count != null) Text("$count", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = fg)
            Text(label, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium, color = fg)
            caption?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
            }
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

/** Friendly per-tab empty state (shared by both layouts). */
private fun emptyWorklistLabel(tabIndex: Int): String =
    if (HOME_TABS[tabIndex].statuses == null) "No open orders — start with New order"
    else "No ${HOME_TABS[tabIndex].label.lowercase()} orders yet"

/** The worklist's status filter chips (shared by both layouts). Every chip
 *  carries its live count from statusCountsFlow — "Label · N". The wide panel
 *  shows 0s too (scanability); narrow chips badge only non-zero tabs
 *  (`showZeroCounts = false`) to save width. */
@Composable
private fun WorklistTabChips(
    selected: Int,
    onSelect: (Int) -> Unit,
    counts: Map<String, Long>,
    showZeroCounts: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HOME_TABS.forEachIndexed { i, t ->
            val n = tabCount(t, counts)
            val label = if (showZeroCounts || n > 0) "${t.label} · $n" else t.label
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(label) })
        }
    }
}

/** Desktop main panel: THE worklist — status filter chips over a real table.
 *  Fills the remaining viewport height; long lists scroll inside the card. */
@Composable
private fun WorklistPanel(
    tabIndex: Int,
    onTabChange: (Int) -> Unit,
    entries: List<WorklistEntry>,
    counts: Map<String, Long>,
    onOpenOrder: (String) -> Unit,
    /** invoiceId → balance; empty map simply shows no payment chips. */
    balanceByInvoice: Map<String, InvoiceBalance> = emptyMap(),
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text("Worklist", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
            WorklistTabChips(
                selected = tabIndex, onSelect = onTabChange,
                counts = counts, showZeroCounts = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center) {
                    Text(emptyWorklistLabel(tabIndex),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Reported time (feedback item 3) earns a column only where it is
                // meaningful — every row on the Reported tab has one, and no row
                // on any earlier tab does.
                val showReported = HOME_TABS[tabIndex].statuses?.contains(LabStatus.REPORTED) == true
                // Column header
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableHeadCell("ACCESSION", Modifier.width(150.dp))
                    TableHeadCell("PATIENT", Modifier.weight(1f))
                    TableHeadCell("PROGRESS", Modifier.width(96.dp))
                    TableHeadCell("STATUS", Modifier.width(120.dp))
                    TableHeadCell("REGISTERED", Modifier.width(92.dp))
                    if (showReported) TableHeadCell("REPORTED", Modifier.width(92.dp))
                    Spacer(Modifier.width(64.dp))
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(entries, key = { it.order.id }) { e ->
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
                            TestProgressCell(e.doneCount, e.testCount, Modifier.width(96.dp), reported = e.reportedCount)
                            Box(Modifier.width(120.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    LabStatusChip(e.order.status, e.order.priority)
                                    // Only renders when money is actually owed.
                                    PaymentPendingChip(
                                        e.order.invoiceId?.let { balanceByInvoice[it] },
                                        compact = true,
                                    )
                                }
                            }
                            Text(shortTimeLabel(e.order.createdAt), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(92.dp))
                            if (showReported) Text(
                                // "—" rather than blank: an approved order that
                                // was never marked reported is worth noticing.
                                e.order.reportedAt?.let { shortTimeLabel(it) } ?: "—",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(92.dp),
                            )
                            TextButton(onClick = { onOpenOrder(e.order.id) }) { Text("Open") }
                        }
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

/**
 * Today's takings at a glance — billed, collected, still due — from the same
 * report the Revenue screen builds, so the tile and the screen can never quote
 * different numbers. Tapping it opens the full dashboard.
 */
@Composable
private fun RevenueTodayCard(report: RevenueReport?, onOpen: () -> Unit) {
    val c = AppTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).background(c.accentSoft, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.BarChart, contentDescription = null,
                        tint = c.accent, modifier = Modifier.size(17.dp))
                }
                Text("Revenue", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Today", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val t = report?.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RevenueFigure("Billed", t?.let { inr(it.billed) } ?: "—", Modifier.weight(1f))
                RevenueFigure("Collected", t?.let { inr(it.collected) } ?: "—", Modifier.weight(1f))
                RevenueFigure("Due", t?.let { inr(it.due) } ?: "—", Modifier.weight(1f),
                    tone = if ((t?.due ?: 0.0) > 0.005) c.warning else null)
            }
            report?.let { r ->
                Text(
                    "${r.current.orders} order${if (r.current.orders == 1) "" else "s"} today" +
                        if (r.outstandingAll > 0.005) " · ${inr(r.outstandingAll)} to collect overall" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RevenueFigure(label: String, value: String, modifier: Modifier, tone: androidx.compose.ui.graphics.Color? = null) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FittedAmount(value, MaterialTheme.typography.titleSmall, tone ?: MaterialTheme.colorScheme.onSurface, minSize = 10.sp)
    }
}

/**
 * Referrer commission, month to date (feedback item 7 asked for commission on
 * the dashboard). Every figure is summed from the frozen order lines — the same
 * arithmetic as the statement, through the same repository rollup, so the tile
 * and the Referrers screen can never quote different numbers.
 *
 * "Outstanding" is what makes this actionable rather than decorative: payable
 * minus what has actually been settled inside the month.
 */
@Composable
private fun CommissionCard(rollup: CommissionRollup?, onReferrers: () -> Unit) {
    val c = AppTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onReferrers),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).background(c.infoSoft, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Payments, contentDescription = null,
                        tint = c.info, modifier = Modifier.size(17.dp))
                }
                Text("Referral commission", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("This month", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rollup == null) {
                Text("Loading…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (rollup.ordersCount == 0L) {
                Text("No referred orders yet this month", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Referred revenue", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₹ ${formatDecimal2(rollup.gross)}", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            "${rollup.ordersCount} order${if (rollup.ordersCount == 1L) "" else "s"} · " +
                                "${formatDecimal1(rollup.effectivePct)}% avg",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Commission payable", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₹ ${formatDecimal2(rollup.payable)}", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(
                            if (rollup.paid > 0.005)
                                "paid ₹ ${formatDecimal2(rollup.paid)} · due ₹ ${formatDecimal2(rollup.outstanding)}"
                            else "nothing settled yet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = onReferrers, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("Statements & payouts")
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

/** Remaining navigation as one full-width row per destination (wide layout
 *  right rail). [showReferrers]: the referrer hub is owner-only (rate lists,
 *  payouts) and the route guard refuses everyone else, so the row is only
 *  offered to someone it will open for. */
@Composable
private fun ShortcutsCard(
    billsCaption: String,
    showReferrers: Boolean,
    showRevenue: Boolean,
    onRevenue: () -> Unit,
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShortcutTile("Patients", Icons.Outlined.PersonAddAlt, Modifier.fillMaxWidth(), onClick = onPatients)
                if (showReferrers) {
                    ShortcutTile("Referrers", Icons.Outlined.People, Modifier.fillMaxWidth(), onClick = onReferrers)
                }
                if (showRevenue) {
                    ShortcutTile("Revenue", Icons.Outlined.BarChart, Modifier.fillMaxWidth(), onClick = onRevenue)
                }
                ShortcutTile("Test catalog", Icons.Outlined.Biotech, Modifier.fillMaxWidth(), onClick = onCatalog)
                ShortcutTile("Bills", Icons.AutoMirrored.Outlined.ReceiptLong, Modifier.fillMaxWidth(),
                    caption = billsCaption, onClick = onBills)
                ShortcutTile("Settings", Icons.Outlined.Settings, Modifier.fillMaxWidth(), onClick = onSettings)
            }
        }
    }
}

@Composable
private fun ShortcutTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    /** Optional live second line (e.g. Bills' "N today"). */
    caption: String? = null,
    onClick: () -> Unit,
) {
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
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                caption?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Narrow-layout building blocks
// ─────────────────────────────────────────────────────────────────────────────

/** Compact open-order row for the phone layout. */
@Composable
private fun CompactOrderRow(
    e: WorklistEntry,
    onOpen: () -> Unit,
    balance: InvoiceBalance? = null,
) {
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
                PaymentPendingChip(balance, Modifier.padding(end = 6.dp), compact = true)
                LabStatusChip(e.order.status, e.order.priority)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${e.patientName} · ${ageSexLabel(e.patientDob, e.patientAgeYears, e.patientSex)}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${e.doneCount}/${e.testCount} test${if (e.testCount == 1L) "" else "s"} done · ${shortTimeLabel(e.order.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Reported time (feedback item 3). Self-gating: only a reported order
            // carries the stamp, so no tab plumbing is needed down here.
            e.order.reportedAt?.let {
                Text("Reported ${shortTimeLabel(it)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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

/**
 * Worklist progress: how many of an order's tests have every parameter filled
 * ("3/7"), with a hairline bar. A bare test count told you the size of the job
 * but not how far along it was — this is what the bench actually scans for.
 */
@Composable
private fun TestProgressCell(done: Long, total: Long, modifier: Modifier = Modifier, reported: Long = 0) {
    val c = AppTheme.colors
    val complete = total > 0 && done >= total
    Column(modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "$done/$total",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (complete) FontWeight.SemiBold else FontWeight.Normal,
            color = if (complete) c.success else MaterialTheme.colorScheme.onSurface,
        )
        LinearProgressIndicator(
            progress = { if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = if (complete) c.success else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        // Per-test release: some of this order has already gone out.
        if (reported in 1 until total) {
            Text("$reported reported", style = MaterialTheme.typography.labelSmall, color = c.success, maxLines = 1)
        }
    }
}
