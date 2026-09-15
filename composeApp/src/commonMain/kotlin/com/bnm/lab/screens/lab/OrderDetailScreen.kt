package com.bnm.lab.screens.lab

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnm.lab.api.LocalLabApi
import com.bnm.lab.billing.BillingPrefs
import com.bnm.lab.billing.GstLine
import com.bnm.lab.billing.ensureLabBillingSeries
import com.bnm.lab.chat.LocalBillingRepository
import com.bnm.lab.chat.LocalOutboxSender
import com.bnm.lab.components.StatusBadge
import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabOrderTest
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabResult
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.LocalLabRepository
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.RefRange
import com.bnm.lab.lab.Referrer
import com.bnm.lab.lab.TestParameter
import com.bnm.lab.lab.TestStage
import com.bnm.lab.print.BtPrinter
import com.bnm.lab.print.EscPos
import com.bnm.lab.print.printToNetworkPrinter
import com.bnm.lab.print.renderLabReport
import com.bnm.lab.report.ReportDoc
import com.bnm.lab.report.sampleTypeDisplay
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.license.OfflinePolicy
import com.bnm.lab.report.ReportShare
import com.bnm.lab.report.WaShareMode
import com.bnm.lab.report.openUrl
import com.bnm.lab.report.waDeepLink
import com.bnm.lab.report.shareFile
import com.bnm.lab.report.waHandedOver
import com.bnm.lab.report.waPhone
import com.bnm.lab.report.waReportCaption
import com.bnm.lab.report.waReportFilename
import com.bnm.lab.report.waReportMessage
import androidx.compose.material3.RadioButton
import com.bnm.lab.report.ReportArchive
import com.bnm.lab.report.archiveReportFile
import com.bnm.lab.report.ReportPrefs
import com.bnm.lab.report.buildReportDoc
import com.bnm.lab.report.openPdf
import com.bnm.lab.report.printPdf
import com.bnm.lab.report.writeLabReportPdf
import com.bnm.lab.staff.LocalStaffSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bnm.lab.report.ReportAssembler
import com.bnm.lab.staff.LocalStaffRepository
import com.bnm.lab.util.formatDecimal2
import com.bnm.lab.screens.billing.CollectPaymentDialog
import com.bnm.lab.chat.InvoiceBalance
import com.bnm.lab.billing.PrintProfiles
import com.bnm.lab.print.buildSampleStickers
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import com.bnm.lab.lab.ResultGraph
import com.bnm.lab.ui.theme.AppTheme
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin

/** Statuses in which result entry is still open (mirrors the repo's guard). */
private val ENTRY_OPEN = setOf(LabStatus.REGISTERED, LabStatus.COLLECTED, LabStatus.IN_PROGRESS, LabStatus.ENTERED)

/** Below this the table collapses to two-line rows (phone / split desktop pane). */
private const val WIDE_MIN_DP = 900

// Grid geometry — the header row and every data row read the SAME numbers, so
// the columns line up exactly like the printed report they mirror.
private const val W_PARAM = 2.4f
private const val W_RANGE = 1.5f
private val COL_RESULT = 148.dp
private val COL_UNIT = 78.dp
private val COL_FLAG = 124.dp   // widened in round 1: "⚠ CH↑" + CRITICAL measured ~107dp and Text overflow is Clip, so it would have truncated silently

/**
 * One order's workbench: a compact patient header + entry progress, then the
 * order's tests as a RAIL (or chips when narrow) with ONE test's dense results
 * table at a time (Parameter | Result | Unit | Ref. range | Flag — the printed
 * report's shape, one test per sheet), and a compact stage action bar. The
 * rail says who filled each test and when, and a test an analyzer filled
 * reads blue, with the analyzer's own alerts above its table.
 *
 * Three things make it a bench tool rather than a form:
 *  - **ranges are shown BEFORE entry** — computed live from the catalog against
 *    the patient's age/sex ([LabRepository.refDisplayFor]); the frozen
 *    `ref_display` still wins for rows already entered (historical truth);
 *  - **flags are live** — recomputed client-side as you type so an out-of-range
 *    value is obvious immediately; the authoritative flag is still whatever
 *    `enterResult` freezes on commit;
 *  - **keyboard-first** — Tab/Shift-Tab walk the rows, Enter jumps to the next
 *    EMPTY row of the test in hand, then on to the next test that has a gap
 *    (a whole order without touching the mouse — the tile follows). Every move
 *    blurs the field, and blur is what commits.
 *
 * Entry locks once the order is verified (repo enforces; UI reflects).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    orderId: String,
    businessId: String,
    labName: String,
    onBack: () -> Unit,
    onOpenInvoice: (String) -> Unit,
) {
    val repo = LocalLabRepository.current
    val staffRepo = LocalStaffRepository.current
    val billing = LocalBillingRepository.current
    val labApi = LocalLabApi.current
    val outbox = LocalOutboxSender.current
    val billSettings by billing.invoiceSettingsFlow(businessId).collectAsState(null)
    // One per screen: assembling a report mints the QR token on first print, so it
    // must be a stable instance rather than rebuilt per recomposition.
    val assembler = remember(repo, staffRepo) { ReportAssembler(repo, staffRepo) }
    val scope = rememberCoroutineScope()
    val prefs = remember { LimsPrefs() }
    // P4: attribution + RBAC ride the SIGNED-IN staff member. LimsPrefs stays the
    // device-name/printing holder — it is no longer who did the work.
    val session = LocalStaffSession.current
    val me by session.current.collectAsState()
    // Graceful fallback: a somehow-empty session degrades to the station name
    // rather than stamping a blank `entered_by`/`verified_by`.
    val actor = me?.name?.takeIf { it.isNotBlank() } ?: prefs.deviceName
    // Approval is the pathologist's signature — technicians verify, they don't
    // approve. A null session falls back to the old free-text dialog.
    val canApprove = (me?.canApprove == true)  // null session ⇒ DENIED (matches Staff?.allows)
    // Verification is attributed AND printed under "Verified by", so it takes a
    // role that verifies (technician / pathologist / owner). A receptionist's
    // name must not land there with no way to ever carry a signature.
    val canVerify = (me?.canVerify == true)
    // The owner claiming "I'm the lab's pathologist" — asked for the name that prints.
    var claimingOwner by remember { mutableStateOf<com.bnm.lab.staff.Staff?>(null) }
    // Whether anyone at all can approve — drives the "nobody can approve" notice.
    var approversInLab by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(me?.id, me?.canApprove) { approversInLab = runCatching { staffRepo.countApprovers() }.getOrNull() }
    // Per-test release (Settings ▸ Printing ▸ Report): each test can be
    // verified, approved and printed on its own — an outsourced test no longer
    // holds back the rest of the order. Read once per screen; the setting is
    // a device preference like the letterhead.
    val releasePerTest = remember { ReportPrefs().releasePerTest }
    // Sending the report to the patient on WhatsApp — off, deep link, or the
    // lab's own WhatsApp Business number (Settings ▸ Printing ▸ Report).
    val reportPrefs = remember { ReportPrefs() }
    // An offline licence never sends through the Business API (the server
    // refuses it anyway) — it falls back to opening WhatsApp with the file.
    val waMode = remember {
        val m = reportPrefs.waShareMode()
        if (m == WaShareMode.API && !OfflinePolicy.allowsWhatsappApi(LicenseManager().state.value.isStandalone)) {
            WaShareMode.LINK
        } else m
    }
    val waCountry = remember { reportPrefs.waCountryCode }
    val waToReferrer = remember { reportPrefs.waSendToReferrer }
    val waSendPdf = remember { reportPrefs.waSendPdf }
    val standalone = remember { LicenseManager().state.value.isStandalone }

    var order by remember { mutableStateOf<LabOrder?>(null) }
    var patient by remember { mutableStateOf<Patient?>(null) }
    var referrer by remember { mutableStateOf<Referrer?>(null) }
    var tests by remember { mutableStateOf<List<LabOrderTest>>(emptyList()) }
    var showStickers by remember { mutableStateOf(false) }   // sample-tube labels (reprint / extra tube)
    var results by remember { mutableStateOf<Map<String, LabResult>>(emptyMap()) } // "testId|paramKey"
    var catalog by remember { mutableStateOf<Map<String, LabTest>>(emptyMap()) }
    var reloadTick by remember { mutableStateOf(0) }
    // Which test the tile shows. Null until the grid is known; then the first
    // test still missing a value, and after that only the operator moves it.
    var selectedTestId by remember(orderId) { mutableStateOf<String?>(null) }
    var instrumentNames by remember { mutableStateOf(emptySet<String>()) }
    var graphs by remember { mutableStateOf<List<ResultGraph>>(emptyList()) }
    // The first load is several reads; until it is through, the body is a
    // spinner — not "No tests on this order" for a frame.
    var loaded by remember(orderId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showApprove by remember { mutableStateOf(false) }
    /** Per-test approval: the test the Approve dialog signs, null = the whole order. */
    var approveTarget by remember { mutableStateOf<String?>(null) }
    /** Per-test print: the tests the print chooser prints, null = the whole order. */
    var printTestIds by remember { mutableStateOf<Set<String>?>(null) }
    var showWhatsapp by remember { mutableStateOf(false) }
    var showCancel by remember { mutableStateOf(false) }
    var showPrintChooser by remember { mutableStateOf(false) }
    // Payment gate on RELEASE. A lab hands the report over when the bill is
    // settled, so an outstanding balance blocks print/share — but never blocks
    // result entry, verification or approval, which are clinical acts.
    var showPaymentDue by remember { mutableStateOf(false) }
    var showCollect by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var billBusy by remember { mutableStateOf(false) }
    // Null when the order has no bill at all (nothing to owe) — the gate then
    // stays out of the way rather than blocking a report that was never billed.
    //
    // Null means "still looking"; a lookup that finished and found nothing is the
    // other case this gate has to handle. An order can link a bill this computer
    // does not hold — issued on another seat and not pulled yet, or issued on a
    // computer that ran the offline edition, whose bills never leave it. Reading
    // that as "nothing owed" released reports for bills nobody here could see.
    val linkedInvoiceId = order?.invoiceId
    val billLookup by remember(billing, businessId, linkedInvoiceId) {
        if (linkedInvoiceId == null) flowOf(BillLookup(null, null))
        else billing.invoiceBalanceFlow(businessId, linkedInvoiceId).map { BillLookup(linkedInvoiceId, it) }
    }.collectAsState(null)
    // collectAsState keeps its last value when the key changes (an order loading
    // its bill id, or "Create bill" linking a new one), so a lookup only counts
    // once it is about THIS bill id. Until then the bill is still being checked.
    val lookupCurrent = billLookup?.takeIf { it.invoiceId == linkedInvoiceId }
    val bill = lookupCurrent?.balance
    val billLoading = linkedInvoiceId != null && lookupCurrent == null
    val billElsewhere = linkedInvoiceId != null && lookupCurrent != null && bill == null
    // Someone at this desk has confirmed the bill on the other computer is settled.
    var releaseWithoutLocalBill by remember(orderId) { mutableStateOf(false) }
    val amountDue: Double = bill?.takeIf { !it.isSettled }?.balance ?: 0.0
    val paymentBlocksRelease: Boolean = amountDue > 0.005 || billLoading || (billElsewhere && !releaseWithoutLocalBill)
    val releaseBlockedMessage: String =
        if (billLoading) "Checking this order's bill — try again in a moment."
        else if (billElsewhere && amountDue <= 0.005) "This order's bill is on another computer — confirm it is paid to release the report."
        else "Balance of ₹ ${formatDecimal2(amountDue)} due — settle the bill to release this report."

    /** Retro-bill an order registered without one (e.g. billing wasn't set up
     *  yet at registration): same snapshot lines as at registration — names and
     *  prices frozen on the order's tests; diagnostic services are NIL-GST. */
    fun createBillNow() {
        val o = order ?: return
        val p = patient ?: return
        if (billBusy || o.invoiceId != null || tests.isEmpty()) return
        billBusy = true
        scope.launch {
            if (!ensureLabBillingSeries(labApi, billing, businessId)) {
                message = "Billing isn't set up on this device yet — connect to the internet once, then retry."
                billBusy = false
                return@launch
            }
            billing.createInvoiceLocal(
                businessId = businessId,
                supplierStateCode = billSettings?.taxId?.trim()?.take(2),
                placeOfSupply = null,
                customerName = p.name,
                customerPhone = p.phone?.trim()?.ifBlank { null },
                customerGstin = null,
                lines = tests.map { GstLine(description = it.testName, hsn = null, quantity = 1.0, rate = it.price, gstRate = 0.0, productId = catalog[it.testId]?.platformProductId) },
                dueDays = billSettings?.dueDays ?: 7,
                notes = "Lab order ${o.accessionNo}",
            ).onSuccess { inv ->
                runCatching { repo.linkInvoice(o.id, inv.id) }
                outbox.kick()
                message = inv.invoiceNumber?.let { n -> "Bill $n created" } ?: "Bill created"
                billBusy = false
                reloadTick++
            }.onFailure {
                message = it.message ?: "Bill could not be created"
                billBusy = false
            }
        }
    }

    // What is IN the boxes right now (may be ahead of the committed result).
    // Single source of truth for the inputs: lets us find the next EMPTY field
    // and flag a value live without asking each row for its private state.
    val draft = remember { mutableStateMapOf<String, String>() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    // Keys the operator has typed into since they were last committed, and keys
    // whose commit is in flight — the two things the live refresh must not
    // overwrite, and (for dirty) the only thing blur may commit.
    val dirty = remember { mutableSetOf<String>() }
    val pending = remember { mutableSetOf<String>() }

    LaunchedEffect(orderId, reloadTick) {
        val o = repo.orderById(orderId) ?: return@LaunchedEffect
        order = o
        patient = repo.patientById(o.patientId)
        referrer = o.referrerId?.let { repo.referrerById(it) }
        val ts = repo.orderTests(orderId)
        tests = ts
        val fresh = repo.resultsForOrder(orderId).associateBy { "${it.testId}|${it.parameterKey}" }
        results = fresh
        catalog = ts.mapNotNull { t -> repo.testById(t.testId)?.let { t.testId to it } }.toMap()
        // Re-seed the boxes from the DB — except a box being typed in or one
        // whose commit is still on its way (a status walk can reload mid-entry).
        fresh.forEach { (k, r) ->
            if (reseedable(k, focusedKey, dirty, pending) && draft[k] != r.value.orEmpty()) draft[k] = r.value.orEmpty()
        }
        loaded = true
    }

    // LIVE: an analyzer frame (or a sync pull) landing while this order is open
    // shows up at once, and the status it may have walked is re-read on the same
    // tick. Boxes follow the same reseed rule as the load above.
    LaunchedEffect(orderId) {
        repo.resultsForOrderFlow(orderId).collect { list ->
            val fresh = list.associateBy { "${it.testId}|${it.parameterKey}" }
            if (fresh == results) return@collect
            results = fresh
            fresh.forEach { (k, r) ->
                if (reseedable(k, focusedKey, dirty, pending) && draft[k] != r.value.orEmpty()) draft[k] = r.value.orEmpty()
            }
            repo.orderById(orderId)?.let { f ->
                if (f.status != order?.status) { order = f; tests = repo.orderTests(orderId) }
            }
        }
    }
    // The engine writes graphs AFTER the results (own table, own notification),
    // and an analyzer can be added or renamed while an order is open.
    LaunchedEffect(orderId) { repo.graphsForOrderFlow(orderId).collect { graphs = it } }
    LaunchedEffect(Unit) { repo.instrumentNamesFlow().collect { instrumentNames = it } }

    val o = order
    val locked = o == null || o.status !in ENTRY_OPEN
    val enteredCount = results.values.count { it.isEntered }
    val totalCount = results.size
    // Per-test release: tests approved but not yet out on a report — what the
    // bottom bar offers to print while the order as a whole is still open.
    val readyTestIds = remember(results) {
        results.values.groupBy { it.testId }
            .filterValues { rows -> rows.all { it.approvedAt != null } && rows.any { it.reportedAt == null } }
            .keys
    }
    // A test verified but not yet approved — per-test release waits for approval too.
    val hasVerifiedTest = remember(results) {
        results.values.groupBy { it.testId }.values.any { rows ->
            rows.all { it.verifiedAt != null } && rows.any { it.approvedAt == null }
        }
    }
    val perTestHint: String? = remember(results, tests, releasePerTest, o?.status) {
        if (!releasePerTest || o == null || o.status !in ENTRY_OPEN) null
        else {
            val stages = tests.map { t -> TestStage.of(results.values.filter { it.testId == t.testId }) }
            val reported = stages.count { it == TestStage.REPORTED }
            val ready = stages.count { it == TestStage.APPROVED }
            val awaiting = stages.count { it == TestStage.VERIFIED }
            if (reported + ready + awaiting == 0) null
            else listOfNotNull(
                "$reported of ${tests.size} tests reported",
                ready.takeIf { it > 0 }?.let { "$it ready to print" },
                awaiting.takeIf { it > 0 }?.let { "$it awaiting approval" },
            ).joinToString(" · ")
        }
    }

    /** Catalog parameter display name for a result row (raw key fallback). */
    val nameOf: (LabResult) -> String = { r ->
        catalog[r.testId]?.parameters?.firstOrNull { it.key == r.parameterKey }?.name ?: r.parameterKey
    }

    /**
     * File this order's report into the lab's reports folder, so it can be
     * read from the file manager without the app.
     *
     * Always the COMPLETE report as it stands — every approved test — not the
     * slice that happened to be printed: the folder is the lab's filing
     * cabinet, and a half report on file would be worse than none. Silent on
     * failure by design (a full disk, an unplugged drive): filing must never
     * break a print.
     */
    suspend fun archiveReport() {
        val ord = order ?: return
        val pat = patient ?: return
        val prefs0 = ReportPrefs()
        if (!prefs0.archiveReports) return
        val dir = prefs0.reportsDir
        if (dir.isBlank()) return
        val approved = repo.approvedTestIds(ord.id)
        if (approved.isEmpty()) return
        runCatching {
            val doc = assembler.assemble(ord.id, labName, stampReportedNow = false, testIds = approved) ?: return
            withContext(Dispatchers.Default) {
                val path = writeLabReportPdf(doc)
                if (path.isNotBlank()) {
                    archiveReportFile(
                        sourcePath = path,
                        dir = dir,
                        relativePath = ReportArchive.relativePath(
                            finishedAtIso = ord.reportedAt ?: ord.approvedAt ?: ord.createdAt,
                            accession = ord.accessionNo,
                            patientName = pat.name,
                        ),
                    )
                }
            }
        }
    }

    /**
     * A successful print/open is the reporting event for the tests it carried:
     * their rows are stamped (a reprint is a no-op) and the order rolls up to
     * `reported` once its last test is out. Only approved tests are stamped —
     * nothing reaches here unapproved, but the repository refuses anyway.
     */
    suspend fun markReported(testIds: Set<String>?) {
        val ord = order ?: return
        val ids = (testIds ?: tests.map { it.testId }.toSet()).intersect(repo.approvedTestIds(ord.id))
        if (ids.isEmpty()) return
        repo.markReported(ord.id, ids).onSuccess { reloadTick++ }.onFailure { message = it.message }
        archiveReport()
    }

    /** Assemble the styled-A4 document from the frozen results + this device's
     *  letterhead prefs (lab name ALWAYS the license-bound one). */
    /**
     * Delegates to [ReportAssembler], which owns the impure half of building a
     * report: stamping the reporting time on a first print, minting/reusing the
     * QR share token, and attaching the approver's signature. `buildReportDoc`
     * itself stays a pure function of the domain objects.
     *
     * Suspending because assembling now reads the staff row and may mint a token.
     */
    suspend fun buildDoc(testIds: Set<String>? = null): ReportDoc? =
        assembler.assemble(order?.id ?: return null, labName, testIds = testIds)

    /** Styled A4 PDF path: write, then open in the viewer or send to the OS
     *  print pipeline. Success (not cancelled/failed) marks approved → reported. */
    suspend fun pdfReport(print: Boolean, testIds: Set<String>? = null): Boolean {
        // Authoritative gate. The button that opens the chooser already checks
        // this, but a UI-only guard is not a guard: the flow could re-emit
        // unsettled between opening the chooser and tapping, and a future caller
        // might not know to check. Refusing here is what actually holds.
        if (paymentBlocksRelease) {
            message = releaseBlockedMessage
            showPaymentDue = true
            return false
        }

        val doc = buildDoc(testIds) ?: return false
        val status = withContext(Dispatchers.Default) {
            val path = writeLabReportPdf(doc)
            when {
                path.isBlank() -> "PDF reports arrive on iOS later"
                print -> printPdf(path)
                else -> openPdf(path)
            }
        }
        message = status
        val ok = !status.contains("failed", ignoreCase = true) &&
            !status.contains("cancelled", ignoreCase = true) &&
            !status.contains("not found", ignoreCase = true) &&
            !status.contains("later", ignoreCase = true)
        if (ok) markReported(testIds)
        return ok
    }

    /** Legacy monospace slip on the configured LAN/BT thermal printer (the
     *  renderLabReport text path — kept for sample-tube counter slips). */
    suspend fun printThermalSlip(testIds: Set<String>? = null): Boolean {
        // Authoritative gate. The button that opens the chooser already checks
        // this, but a UI-only guard is not a guard: the flow could re-emit
        // unsettled between opening the chooser and tapping, and a future caller
        // might not know to check. Refusing here is what actually holds.
        if (paymentBlocksRelease) {
            message = releaseBlockedMessage
            showPaymentDue = true
            return false
        }

        val ord = order ?: return false
        val pat = patient ?: return false
        // The REPORT profile — a lab's report printer is often not its counter
        // receipt printer. Before profiles existed both read one shared config.
        val bp = PrintProfiles.report
        // Honour the profile's own switch, or it is a control that does nothing.
        if (!bp.enabled) {
            message = "Report printing is turned off in Settings ▸ Printing."
            return false
        }
        // Per-test release: the slip carries the chosen tests; the order's
        // other unreported tests are named on it.
        val slipTests = if (testIds == null) tests else tests.filter { it.testId in testIds }
        val slipIds = slipTests.map { it.testId }.toSet()
        val toFollow = tests.filter { t ->
            t.testId !in slipIds && TestStage.of(results.values.filter { it.testId == t.testId }) != TestStage.REPORTED
        }.map { it.testName }
        val result = withContext(Dispatchers.Default) {
            val slipRows = results.values.filter { it.testId in slipIds }
            suspend fun currentName(id: String?) = id?.takeIf { it.isNotBlank() }?.let { staffRepo.byId(it)?.name }
            val body = renderLabReport(
                verifiedByName = currentName(slipRows.firstNotNullOfOrNull { it.verifiedById }),
                approvedByName = currentName(slipRows.firstNotNullOfOrNull { it.approvedById }),
                labName = labName, order = ord, patient = pat, tests = slipTests,
                results = slipRows, referrerName = referrer?.name,
                widthChars = bp.paperWidth, paramName = nameOf,
                sampleType = { t -> sampleTypeDisplay(catalog[t.testId]?.sampleType) },
                toFollow = toFollow,
            )
            // Extra copies are best-effort: the first one succeeding is what
            // counts as "reported", so a failed duplicate must not undo that.
            suspend fun sendReport(): String = when (bp.connection) {
                "network" -> printToNetworkPrinter(bp.ip, bp.port, EscPos.encode(body))
                "bluetooth" -> BtPrinter.getInstance().printBytes(bp.btAddress, EscPos.encode(body))
                else -> "No thermal printer configured"
            }
            val first = sendReport()
            if (first.startsWith("Sent to")) {
                repeat((bp.copies - 1).coerceAtLeast(0)) { runCatching { sendReport() } }
            }
            first
        }
        val ok = result.startsWith("Sent to")
        message = if (ok) "Report sent to printer" else result
        if (ok) markReported(testIds)
        return ok
    }

    /** Commit one cell. Flags + ref_display are frozen server-side by the repo —
     *  the live chip is only a preview of what it will decide. */
    fun commit(row: GridRow, text: String) {
        val ord = order ?: return
        session.touch()
        // In flight: the live flow must not reseed this box from the OLD stored
        // value while the write is on its way (another cell's commit, or an
        // analyzer frame, can make the flow emit in between).
        pending += row.key
        scope.launch {
            try {
                repo.enterResult(ord.id, row.testId, row.paramKey, text, enteredBy = actor)
                    .onSuccess { updated ->
                        results = results + (row.key to updated)
                        // Entry can walk the order status (in_progress/entered).
                        repo.orderById(ord.id)?.let { fresh -> if (fresh.status != ord.status) reloadTick++ }
                    }
                    .onFailure { message = it.message }
            } finally {
                pending -= row.key
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        o?.accessionNo ?: "Order",
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    // Tube labels are needed from registration until the sample is
                    // on the bench — a torn label or a second tube means a reprint.
                    if (o != null && o.status != LabStatus.CANCELLED && tests.isNotEmpty()) {
                        TextButton(onClick = { showStickers = true }) { Text("Stickers") }
                    }
                    o?.invoiceId?.let { inv -> TextButton(onClick = { onOpenInvoice(inv) }) { Text("Bill") } }
                    if (o != null && o.invoiceId == null && o.status != LabStatus.CANCELLED && tests.isNotEmpty()) {
                        TextButton(enabled = !billBusy, onClick = { createBillNow() }) { Text("Create bill") }
                    }
                    if (o != null && o.status != LabStatus.DELIVERED && o.status != LabStatus.CANCELLED) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Cancel order") }, onClick = { menuOpen = false; showCancel = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (o != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            message?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            stageHint(o.status, enteredCount, totalCount, canApprove)?.let { hint ->
                                Text(
                                    hint, style = MaterialTheme.typography.bodySmall,
                                    color = if (o.status == LabStatus.CANCELLED) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Nobody in the lab can approve — typically a lab upgraded from
                            // when owners approved by default. Say so plainly, and let the
                            // owner who IS the pathologist claim it in one tap. Never granted
                            // silently: owning the lab is not being its pathologist.
                            if (!canApprove && approversInLab == 0L &&
                                (o.status == LabStatus.VERIFIED || (hasVerifiedTest && o.status in ENTRY_OPEN))
                            ) {
                                NoApproverNotice(
                                    me = me,
                                    onClaim = { owner -> claimingOwner = owner },
                                )
                            }
                            perTestHint?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // Per-test release: what is approved can go out now, while
                        // the rest of the order is still open. Same release gate.
                        if (releasePerTest && readyTestIds.isNotEmpty() && o.status in ENTRY_OPEN) {
                            OutlinedButton(
                                onClick = {
                                    if (busy) return@OutlinedButton
                                    printTestIds = readyTestIds
                                    if (paymentBlocksRelease) showPaymentDue = true else showPrintChooser = true
                                },
                                enabled = !busy,
                            ) { Text("Print released (${readyTestIds.size})") }
                            if (waMode != WaShareMode.OFF) {
                                OutlinedButton(onClick = { if (!busy) showWhatsapp = true }, enabled = !busy) {
                                    Text("WhatsApp")
                                }
                            }
                        }
                        ActionBar(
                            status = o.status,
                            busy = busy,
                            onForward = { next ->
                                if (busy) return@ActionBar
                                busy = true; message = null
                                scope.launch {
                                    repo.setOrderStatus(o.id, next)
                                        .onSuccess { reloadTick++ }
                                        .onFailure { message = it.message }
                                    busy = false
                                }
                            },
                            onVerify = {
                                if (busy) return@ActionBar
                                busy = true; message = null; session.touch()
                                scope.launch {
                                    repo.verifyOrder(o.id, actor, me?.id)
                                        .onSuccess { message = "Verified by $actor"; reloadTick++ }
                                        .onFailure { message = it.message }
                                    busy = false
                                }
                            },
                            canVerify = canVerify,
                            canApprove = canApprove,
                            onApprove = { session.touch(); showApprove = true },
                            onPrint = {
                                if (!busy) {
                                    // Release is the commercial gate: an unpaid
                                    // balance stops the report leaving the lab.
                                    if (paymentBlocksRelease) showPaymentDue = true
                                    else showPrintChooser = true
                                }
                            },
                            // Beside Print, because handing the report over on
                            // WhatsApp is the same act by another route.
                            canWhatsapp = waMode != WaShareMode.OFF,
                            onWhatsapp = { if (!busy) showWhatsapp = true },
                        )
                    }
                }
            }
        },
    ) { inner ->
        if (o == null || patient == null || !loaded) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val pat = patient!!
        val rows = remember(tests, catalog, results, pat) { buildGrid(tests, catalog, results, pat) }
        val requesters = remember(rows.size) { List(rows.size) { FocusRequester() } }
        val listState = rememberLazyListState()
        val focusManager = LocalFocusManager.current
        val showLockNote = locked && o.status != LabStatus.CANCELLED
        val groups = remember(rows, tests, catalog, results, instrumentNames, graphs) {
            buildEntryGroups(
                tests = tests,
                codes = catalog.mapValues { it.value.code },
                rowTestIds = rows.map { it.testId },
                rowKeys = rows.map { it.key },
                results = results,
                instrumentNames = instrumentNames,
                graphs = graphs,
            )
        }
        // Open on the first test still missing a value; from then on the tile
        // only changes when the operator changes it (click, Tab, or Enter).
        LaunchedEffect(groups) {
            if (groups.none { it.testId == selectedTestId }) selectedTestId = initialTestId(groups)
        }
        val selected = groups.firstOrNull { it.testId == selectedTestId }
            ?: initialTestId(groups)?.let { id -> groups.first { it.testId == id } }

        BoxWithConstraints(Modifier.padding(inner).fillMaxSize()) {
            val wide = maxWidth >= WIDE_MIN_DP.dp
            // The rail is the order's table of contents; an order with ONE test
            // has nothing to list, and a pane too narrow to hold the rail beside
            // a five-column table shows the same tests as chips above it.
            val showRail = maxWidth >= RAIL_MIN_DP.dp && groups.size > 1

            /** Focus row [target] (a GLOBAL index): switch the tile to its test
             *  if needed, scroll it into view, then focus. A requester that is
             *  not composed yet throws, so after a switch we give it a frame or two. */
            fun focusRow(target: Int) {
                if (target !in rows.indices) { focusManager.clearFocus(); return }
                val g = groups.firstOrNull { target in it.range }
                if (g == null) { focusManager.clearFocus(); return }
                scope.launch {
                    if (selectedTestId != g.testId) {
                        selectedTestId = g.testId
                        withFrameNanos { }
                    }
                    val local = target - g.first
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == local }) {
                        runCatching { listState.scrollToItem(local) }
                    }
                    // requestFocus() answers false (or throws, older runtimes) when
                    // the row is not composed yet — give it a few frames.
                    repeat(5) {
                        if (runCatching { requesters[target].requestFocus() }.getOrDefault(false)) return@launch
                        withFrameNanos { }
                    }
                }
            }

            /** Enter = the next thing that still needs a value — this test first,
             *  then the next test with a gap; nothing left → drop focus (commits). */
            fun focusNextEmpty(from: Int) {
                val target = nextEmptyIndex(groups, from, rows.size) { i -> draft[rows[i].key].isNullOrBlank() }
                if (target == null) focusManager.clearFocus() else focusRow(target)
            }

            fun selectTest(testId: String) {
                focusManager.clearFocus()          // blur commits the cell being left
                selectedTestId = testId
                scope.launch { runCatching { listState.scrollToItem(0) } }
            }

            // Per-test release: the tile's own Verify / Approve / Print. Same
            // actor, same RBAC, same release gate as the order-wide bar.
            val release = if (!releasePerTest) null else TestRelease(
                canVerify = canVerify, canApprove = canApprove, busy = busy,
                onVerify = { testId ->
                    if (!busy) {
                        focusManager.clearFocus()
                        busy = true; message = null; session.touch()
                        scope.launch {
                            repo.verifyTest(o.id, testId, actor, me?.id)
                                .onSuccess { message = "Verified by $actor" }
                                .onFailure { message = it.message }
                            busy = false
                        }
                    }
                },
                onApprove = { testId -> session.touch(); approveTarget = testId; showApprove = true },
                onPrint = { testId ->
                    if (!busy) {
                        printTestIds = setOf(testId)
                        if (paymentBlocksRelease) showPaymentDue = true else showPrintChooser = true
                    }
                },
            )

            Column(Modifier.fillMaxSize()) {
                // PINNED patient header — who you are typing results for must
                // never scroll away mid-entry (it reads as part of the app bar).
                Surface(tonalElevation = 2.dp, shadowElevation = 2.dp) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OrderHeader(
                            o, pat, referrer, bill,
                            samples = tests.mapNotNull { sampleTypeDisplay(catalog[it.testId]?.sampleType) }.distinct(),
                        )
                        // L3: outsourced lines get an indicator + a lightweight
                        // "Sent to partner" stamp BEFORE result entry. The order's
                        // status machine is untouched — entering the partner's
                        // reported values works exactly like bench results.
                        OutsourcedStrip(
                            tests = tests,
                            catalog = catalog,
                            canMark = o.status in ENTRY_OPEN && !busy,
                            onMarkSent = { testId ->
                                scope.launch {
                                    repo.markSentToPartner(o.id, testId)
                                        .onSuccess { reloadTick++ }
                                        .onFailure { message = it.message }
                                }
                            },
                        )
                        if (totalCount > 0) EntryProgress(enteredCount, totalCount)
                        if (showLockNote) {
                            Text(
                                "Results are locked (order is ${o.status.replace('_', ' ')}).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (selected == null) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No tests on this order", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    return@Column
                }
                val typingIn = focusedKey?.let { k -> rows.firstOrNull { it.key == k }?.testId }

                /** The selected test's rows — the slice of the flat grid it owns. */
                val tileRows: LazyListScope.() -> Unit = {
                    itemsIndexed(selected.range.toList(), key = { _, i -> rows[i].key }) { _, i ->
                        val row = rows[i]
                        Column(Modifier.fillMaxWidth()) {
                            ResultGridRow(
                                row = row,
                                value = draft[row.key].orEmpty(),
                                wide = wide,
                                // A test signed off on its own is locked while the rest stays open.
                                locked = locked || row.result.verifiedAt != null,
                                focused = focusedKey == row.key,
                                last = i == rows.lastIndex,
                                focusRequester = requesters[i],
                                onValueChange = { draft[row.key] = it; dirty += row.key },
                                onFocus = { focusedKey = row.key },
                                onBlur = {
                                    if (focusedKey == row.key) focusedKey = null
                                    val wasDirty = dirty.remove(row.key)
                                    val text = draft[row.key].orEmpty()
                                    if (shouldCommitOnBlur(wasDirty, text, row.result.value)) commit(row, text)
                                },
                                onNext = { focusRow(i + 1) },
                                onPrevious = { if (i == 0) focusManager.clearFocus() else focusRow(i - 1) },
                                onEnter = { focusNextEmpty(i) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        }
                    }
                }

                if (showRail) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        TestRail(
                            groups = groups, selectedId = selected.testId, typingIn = typingIn,
                            perTest = releasePerTest,
                            onSelect = ::selectTest,
                            modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight(),
                        )
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TestTile(
                            group = selected, test = catalog[selected.testId], wide = wide,
                            listState = listState, release = release,
                            modifier = Modifier.weight(1f).fillMaxHeight(), rows = tileRows,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxWidth().weight(1f)) {
                        if (groups.size > 1) TestChips(groups, selected.testId, ::selectTest)
                        TestTile(
                            group = selected, test = catalog[selected.testId], wide = wide,
                            listState = listState, release = release,
                            modifier = Modifier.fillMaxWidth().weight(1f), rows = tileRows,
                        )
                    }
                }
            }
        }
    }

    // ── Approve dialog (P4): the SIGNED-IN pathologist signs — no free-text
    // "approved by" any more. The old typed-name field survives only as the
    // fallback for a somehow-null session (never expected once the sign-in gate
    // is in front of the app, but approval must never become unreachable). ──
    if (showStickers) {
        val o0 = order
        val p0 = patient
        if (o0 != null && p0 != null) {
            StickerPrintDialog(
                accession = o0.accessionNo,
                // A test since removed from the catalog still had a tube; it gets
                // a plain "SAMPLE" label rather than no label.
                stickers = buildSampleStickers(
                    o0, p0,
                    tests.map { catalog[it.testId] ?: LabTest(id = it.testId, code = "", name = it.testName, sampleType = "other") },
                    labName,
                ),
                onDismiss = { showStickers = false },
            )
        } else {
            showStickers = false
        }
    }

    claimingOwner?.let { owner ->
        PathologistDetailsDialog(
            owner = owner,
            onDismiss = { claimingOwner = null },
            onSave = { name, qualifications, registrationNo ->
                scope.launch {
                    // Save onto the stored row, not the sign-in copy, so a PIN or
                    // signature changed since sign-in is not written back over.
                    val current = staffRepo.byId(owner.id) ?: owner
                    staffRepo.save(current.copy(
                        name = name, qualifications = qualifications, registrationNo = registrationNo,
                        alsoPathologist = true,
                    )).onSuccess { saved ->
                        // A rename is exactly when old "Lab Owner" stamps need the id
                        // filled in, so reprints follow the person.
                        runCatching { repo.backfillSignatoryIds(staffRepo.listAll(), com.bnm.lab.staff.StaffRepository.DEFAULT_OWNER_ID) }
                        session.refresh(saved)
                        approversInLab = staffRepo.countApprovers()
                        claimingOwner = null
                        message = "${saved.name} is now the lab's pathologist and can approve"
                    }.onFailure { message = it.message }
                }
            },
        )
    }

    if (showApprove && o != null && canApprove) {
        val signer = me
        val target = approveTarget
        val targetName = target?.let { id -> tests.firstOrNull { it.testId == id }?.testName }
        var name by remember(signer) { mutableStateOf(signer?.name ?: prefs.approvedBy) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showApprove = false; approveTarget = null },
            title = { Text(if (targetName != null) "Approve $targetName" else "Approve results") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (targetName != null) "Only this test is signed off; the rest of the order stays open. The approving pathologist's name prints on the report."
                        else "The approving pathologist's name prints on every report.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Surface the balance HERE too, so the pathologist signing off
                    // knows the report will not go out yet — but approval itself is
                    // never blocked. Sign-off is a clinical act: withholding it on a
                    // money question would strand an abnormal result unverified and
                    // delay the critical-value call the lab is obliged to make.
                    if (paymentBlocksRelease) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    if (billLoading) "Checking the bill…"
                                    else if (billElsewhere && amountDue <= 0.005) "Bill is on another computer"
                                    else "Balance due ₹ ${formatDecimal2(amountDue)}",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    "You can approve now, but the report cannot be printed " +
                                        "or shared until the bill is confirmed settled.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                    if (signer != null) {
                        Text(signer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Signing as the pathologist — switch user from the home header to sign as someone else.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            label = { Text("Approved by (pathologist)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val n = (signer?.name ?: name).trim()
                    if (n.isEmpty()) { err = "Name is required"; return@Button }
                    if (signer == null) prefs.approvedBy = n
                    showApprove = false; approveTarget = null
                    busy = true; message = null; session.touch()
                    scope.launch {
                        (if (target != null) repo.approveTest(o.id, target, n, signer?.id) else repo.approveOrder(o.id, n, signer?.id))
                            .onSuccess {
                                message = "Approved by $n"; reloadTick++
                                // Finished is finished: the folder holds every
                                // signed report, printed or not.
                                archiveReport()
                            }
                            .onFailure { message = it.message }
                        busy = false
                    }
                }) { Text("Approve") }
            },
            dismissButton = { TextButton(onClick = { showApprove = false; approveTarget = null }) { Text("Cancel") } },
        )
    }

    // ── Print chooser: styled A4 PDF (open / print) + optional thermal slip ──
    // ── Payment due: blocks RELEASE, offers to settle right here ──────────────
    if (showPaymentDue && billLoading) {
        // Nothing to decide yet: the message line says the bill is being checked.
        LaunchedEffect(Unit) {
            message = releaseBlockedMessage
            showPaymentDue = false
        }
    } else if (showPaymentDue && o != null && billElsewhere && amountDue <= 0.005) {
        AlertDialog(
            onDismissRequest = { showPaymentDue = false },
            title = { Text("Bill is not on this computer") },
            text = {
                Text(
                    "This order was billed on another computer" +
                        ", or its bill has not synced here yet, so this computer cannot " +
                        "check whether it has been paid.\n\nRelease the report only if you know the bill is settled.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = {
                    releaseWithoutLocalBill = true
                    showPaymentDue = false
                    message = "Confirmed as paid — you can print or share the report now."
                }) { Text("It is paid — release") }
            },
            dismissButton = { TextButton(onClick = { showPaymentDue = false }) { Text("Not now") } },
        )
    } else if (showPaymentDue && o != null) {
        val due = amountDue
        AlertDialog(
            onDismissRequest = { showPaymentDue = false },
            title = { Text("Balance due — report not released") },
            text = {
                Column {
                    Text(
                        "This order still has an outstanding balance. The report can " +
                            "be printed or shared once the bill is settled.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    bill?.let { b ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Bill total", style = MaterialTheme.typography.bodySmall)
                            Text("₹ " + formatDecimal2(b.invoice.total), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Collected", style = MaterialTheme.typography.bodySmall)
                            Text("₹ " + formatDecimal2(b.collected), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Balance due", fontWeight = FontWeight.Bold)
                            Text("₹ " + formatDecimal2(due), fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error)
                        }
                        if (b.hasQueuedPayment) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "A payment taken on this device hasn't reached the server " +
                                    "yet — it is already counted above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showPaymentDue = false; showCollect = true }) {
                    Text("Collect ₹ ${formatDecimal2(due)}")
                }
            },
            dismissButton = { TextButton(onClick = { showPaymentDue = false }) { Text("Not now") } },
        )
    }

    // Reuses the billing screen's collector so a balance taken here behaves
    // exactly like one taken on the bill: queued through the outbox, idempotent,
    // and correct with no network.
    if (showCollect) {
        bill?.let { b ->
            CollectPaymentDialog(
                businessId = businessId,
                bill = b,
                onDismiss = { showCollect = false },
                onCollected = {
                    showCollect = false
                    // The flow re-emits with the queued tender included, so by the
                    // time the chooser opens the gate has already re-evaluated.
                    showPrintChooser = true
                },
            )
        }
    }

    if (showPrintChooser && o != null) {
        val thermalAvailable = remember {
            // Report profile, not the counter's.
            PrintProfiles.report.isDirectlyConnected
        }
        val ids = printTestIds
        val subtitle = ids?.let { s -> tests.filter { it.testId in s }.joinToString(", ") { it.testName } }
        fun run(block: suspend (Set<String>?) -> Unit) {
            showPrintChooser = false
            if (busy) return
            busy = true; message = "Preparing report…"
            scope.launch {
                try { block(ids) } catch (e: Throwable) { message = "Report failed: ${e.message}" }
                printTestIds = null
                busy = false
            }
        }
        AlertDialog(
            onDismissRequest = { showPrintChooser = false; printTestIds = null },
            title = { Text("Report ${o.accessionNo}" + (subtitle?.let { " · $it" } ?: "")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The A4 report uses this device's letterhead settings (Settings → Report & letterhead).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (subtitle != null) {
                        Text(
                            "Only the tests named above go on this report; the rest of the order is listed on it as still to follow.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { run { t -> pdfReport(print = false, testIds = t) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open PDF")
                    }
                    Button(onClick = { run { t -> pdfReport(print = true, testIds = t) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("Print")
                    }
                    if (thermalAvailable) {
                        OutlinedButton(onClick = { run { t -> printThermalSlip(t) } }, modifier = Modifier.fillMaxWidth()) {
                            Text("Thermal slip")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPrintChooser = false; printTestIds = null }) { Text("Close") } },
        )
    }

    // ── Send the report on WhatsApp ────────────────────────────────────────
    // Two ways, chosen in Settings: open WhatsApp with the message ready (the
    // operator presses send, works anywhere), or let the lab's own WhatsApp
    // Business number send the PDF itself. Either way the message carries the
    // name, the accession and the private link — never a result.
    if (showWhatsapp && o != null && patient != null) {
        val pat = patient!!
        val patPhone = waPhone(pat.phone, waCountry)
        val refPhone = referrer?.phone?.let { waPhone(it, waCountry) }.takeIf { waToReferrer }
        var toDoctor by remember(showWhatsapp) { mutableStateOf(patPhone == null && refPhone != null) }
        var typed by remember(showWhatsapp) { mutableStateOf("") }
        val chosen = when {
            typed.isNotBlank() -> waPhone(typed, waCountry)
            toDoctor -> refPhone
            else -> patPhone
        }
        val recipientName = if (toDoctor && typed.isBlank()) referrer?.name.orEmpty() else pat.name
        var sending by remember(showWhatsapp) { mutableStateOf(false) }
        var note by remember(showWhatsapp) { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!sending) { showWhatsapp = false } },
            title = { Text("Send report on WhatsApp") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            waMode == WaShareMode.API -> "The lab's WhatsApp Business number sends the report PDF."
                            waSendPdf -> "WhatsApp opens with the report PDF attached — send it there."
                            else -> "WhatsApp opens with the message and the download link — press send there."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (patPhone != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = !toDoctor && typed.isBlank(), onClick = { toDoctor = false; typed = "" })
                            Text("${pat.name} · ${pat.phone.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text("This patient has no phone number on file.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (refPhone != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = toDoctor && typed.isBlank(), onClick = { toDoctor = true; typed = "" })
                            Text("${referrer?.name.orEmpty()} (referring doctor)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it },
                        label = { Text("Or another number") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (standalone && !waSendPdf) {
                        Text(
                            "Offline edition: the message says the report is ready, without a download link.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    note?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("Sent") || it.startsWith("Opened")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = chosen != null && !sending,
                    onClick = {
                        val phone = chosen ?: return@Button
                        if (paymentBlocksRelease) { showWhatsapp = false; showPaymentDue = true; return@Button }
                        sending = true; note = null
                        scope.launch {
                            val token = if (standalone) null
                            else runCatching { repo.reportShareToken(o.id, o.accessionNo) }.getOrNull()
                            val url = token?.let { ReportShare.resolveUrl(it) }
                            val doctor = toDoctor && typed.isBlank()
                            when (waMode) {
                                WaShareMode.API -> {
                                    if (token == null) {
                                        note = "This edition cannot send the file — use the link option."
                                    } else {
                                        labApi.sendReportWhatsapp(
                                            token = token, to = phone,
                                            filename = waReportFilename(o.accessionNo),
                                            caption = waReportCaption(recipientName, labName, o.accessionNo, doctor),
                                            idempotencyKey = "labrep-$token-$phone",
                                        ).onSuccess {
                                            note = "Sent to $phone"
                                            markReported(null)
                                        }.onFailure { note = it.message ?: "WhatsApp send failed" }
                                    }
                                }
                                else -> {
                                    // The file beats a link: WhatsApp cannot attach one
                                    // from a wa.me link, but the platform share can, and
                                    // the PDF we already render for printing is the same
                                    // document. Falls back to the link when there is no
                                    // file (iOS) or the lab chose links.
                                    val released = repo.approvedTestIds(o.id)
                                    val doc = if (!waSendPdf) null
                                    else assembler.assemble(o.id, labName, testIds = released.ifEmpty { null })
                                    val pdf = doc?.let { withContext(Dispatchers.Default) { writeLabReportPdf(it) } }
                                        ?.takeIf { it.isNotBlank() }
                                    note = if (pdf != null) {
                                        // No link in the text — the report is attached.
                                        shareFile(pdf, "application/pdf",
                                            waReportMessage(recipientName, labName, o.accessionNo, null, doctor), phone)
                                    } else {
                                        openUrl(waDeepLink(phone,
                                            waReportMessage(recipientName, labName, o.accessionNo, url, doctor)))
                                    }
                                    if (waHandedOver(note)) markReported(null)
                                }
                            }
                            sending = false
                        }
                    },
                ) { Text(if (waMode == WaShareMode.API) "Send now" else "Open WhatsApp") }
            },
            dismissButton = { TextButton(enabled = !sending, onClick = { showWhatsapp = false }) { Text("Close") } },
        )
    }

    // ── Cancel confirm ──
    if (showCancel && o != null) {
        AlertDialog(
            onDismissRequest = { showCancel = false },
            title = { Text("Cancel order ${o.accessionNo}?") },
            text = { Text("The order is marked cancelled and leaves the worklist. The linked bill (if any) is NOT voided automatically.") },
            confirmButton = {
                Button(onClick = {
                    showCancel = false
                    scope.launch {
                        repo.setOrderStatus(o.id, LabStatus.CANCELLED)
                            .onSuccess { reloadTick++ }
                            .onFailure { message = it.message }
                    }
                }) { Text("Cancel order") }
            },
            dismissButton = { TextButton(onClick = { showCancel = false }) { Text("Keep") } },
        )
    }
}

// ── Grid model ───────────────────────────────────────────────────────────────

/** One printable line of the results table. Everything the row needs is
 *  resolved once, at load: no per-frame catalog lookups, no age math. */
private data class GridRow(
    val key: String,                  // "testId|paramKey" — matches the results map
    val testId: String,
    val paramKey: String,
    val label: String,
    val unit: String,
    val refDisplay: String,
    val numeric: Boolean,
    val range: RefRange?,             // for the LIVE flag while typing
    val result: LabResult,
)

/**
 * Flatten the order into table rows, test by test (the tile shows one test's
 * slice; the keyboard walks them all). The row label is the catalog parameter
 * name — the test's own name is on the tile and the rail. A test whose
 * catalog entry has vanished still renders from its frozen result rows so
 * nothing is ever hidden from the technician.
 */
private fun buildGrid(
    tests: List<LabOrderTest>,
    catalog: Map<String, LabTest>,
    results: Map<String, LabResult>,
    patient: Patient,
): List<GridRow> {
    val rows = mutableListOf<GridRow>()
    for (t in tests) {
        val test = catalog[t.testId]
        val fromCatalog = if (test == null) emptyList() else test.parameters.mapNotNull { p ->
            val res = results["${t.testId}|${p.key}"] ?: return@mapNotNull null
            GridRow(
                key = "${t.testId}|${p.key}",
                testId = t.testId,
                paramKey = p.key,
                label = p.name,
                unit = (res.unit ?: p.unit).orEmpty(),
                // Frozen range wins once a value is in (that is what printed);
                // otherwise show what WILL be frozen for this patient.
                refDisplay = res.refDisplay?.takeIf { res.isEntered && it.isNotBlank() }
                    ?: LabRepository.refDisplayFor(test, p, patient),
                numeric = p.isNumeric(),
                range = LabRepository.rangeFor(test, p, patient),
                result = res,
            )
        }
        if (fromCatalog.isNotEmpty()) {
            rows += fromCatalog
            continue
        }
        // No catalog entry, or one whose parameter keys no longer match the
        // order's frozen rows (a re-imported platform test): show the rows we
        // have, labelled by their key, so the test never vanishes from the rail
        // while the header still counts its cells.
        for (res in results.values.filter { it.testId == t.testId }) {
            rows += GridRow(
                key = "${t.testId}|${res.parameterKey}",
                testId = t.testId,
                paramKey = res.parameterKey,
                label = res.parameterKey,
                unit = res.unit.orEmpty(),
                refDisplay = res.refDisplay?.takeIf { it.isNotBlank() } ?: LabRepository.NO_RANGE,
                numeric = true,
                range = null,
                result = res,
            )
        }
    }
    return rows
}

/** Numeric unless every defined range is qualitative (text-only). */
private fun TestParameter?.isNumeric(): Boolean {
    if (this == null) return false
    if (ranges.isEmpty()) return true
    return ranges.any { it.text == null }
}

/**
 * "I'm the lab's pathologist": the name, qualifications and registration no. the
 * report prints under "Approved by (Pathologist)". The seeded "Lab Owner" is not
 * accepted as that name.
 */
@Composable
private fun PathologistDetailsDialog(
    owner: com.bnm.lab.staff.Staff,
    onDismiss: () -> Unit,
    onSave: (name: String, qualifications: String?, registrationNo: String?) -> Unit,
) {
    var name by remember { mutableStateOf(com.bnm.lab.report.Signatory.printable(owner.name).orEmpty()) }
    var qualifications by remember { mutableStateOf(owner.qualifications.orEmpty()) }
    var registrationNo by remember { mutableStateOf(owner.registrationNo.orEmpty()) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("You as the lab's pathologist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This is how your sign-off prints on reports.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it; err = null }, label = { Text("Name (e.g. Dr. Meena Iyer)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), isError = err != null)
                OutlinedTextField(qualifications, { qualifications = it }, label = { Text("Qualifications (e.g. MD Pathology)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(registrationNo, { registrationNo = it }, label = { Text("Medical council registration no.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val n = name.trim()
                com.bnm.lab.report.Signatory.pathologistNameProblem(n)?.let { err = it; return@Button }
                onSave(n, qualifications.trim().ifBlank { null }, registrationNo.trim().ifBlank { null })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Shown when an order is waiting for approval and nobody in the lab may give it.
 * The owner gets a one-tap "I'm the pathologist"; anyone else is told who to ask.
 */
@Composable
private fun NoApproverNotice(me: com.bnm.lab.staff.Staff?, onClaim: (com.bnm.lab.staff.Staff) -> Unit) {
    val owner = me?.takeIf { it.role == com.bnm.lab.staff.StaffRole.OWNER && it.active }
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Nobody in this lab can approve results yet",
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                if (owner != null) "Approval is the pathologist's sign-off. If you are this lab's pathologist, " +
                    "mark yourself as one; otherwise add your pathologist in Settings ▸ Staff & roles."
                else "Approval is the pathologist's sign-off. Ask the lab owner to add a pathologist in Staff & roles.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (owner != null) {
                Button(onClick = { onClaim(owner) }) { Text("I'm the lab's pathologist") }
            }
        }
    }
}

/** The one line under the buttons that says what this stage is waiting for. */
private fun stageHint(status: String, entered: Int, total: Int, canApprove: Boolean): String? = when (status) {
    LabStatus.REGISTERED -> "Collect the sample to start"
    LabStatus.COLLECTED, LabStatus.IN_PROGRESS -> "$entered of $total results entered — verify unlocks when all are in"
    LabStatus.ENTERED -> "All $total results in — ready to verify"
    LabStatus.VERIFIED -> if (canApprove) "Verified — awaiting the pathologist's approval"
    else "Verified — only a pathologist can approve it"
    LabStatus.APPROVED -> "Approved — printing marks it reported"
    LabStatus.REPORTED, LabStatus.DELIVERED -> "Report issued — reprints stay open"
    LabStatus.CANCELLED -> "Order cancelled"
    else -> null
}

// ── Pieces ───────────────────────────────────────────────────────────────────

/** Compact identity strip: who, how old, reachable where, which accession. */
@Composable
private fun OrderHeader(
    order: LabOrder,
    patient: Patient,
    referrer: Referrer?,
    /** Null = no bill, or settled — the chip then renders nothing. */
    bill: InvoiceBalance? = null,
    /** Distinct specimens the order's tests need ("Blood", "Serum") — what the
     *  desk must collect, said where the order is identified. */
    samples: List<String> = emptyList(),
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    patient.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    ageSexLabel(patient.dob, patient.ageYears, patient.sex) +
                        (patient.phone?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (!order.priority.equals("routine", ignoreCase = true)) StatusBadge(order.priority)
                StatusBadge(order.status)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.accessionNo, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
                // Same chip the worklist uses, so "payment pending" looks identical
                // wherever the operator meets this order.
                PaymentPendingChip(bill)
                Text(
                    // Reported is the clinically meaningful stamp — it is what the
                    // patient and the referring doctor quote back. Shown next to
                    // Registered whenever the report has actually gone out.
                    "· Registered ${shortTimeLabel(order.createdAt)}" +
                        (order.reportedAt?.let { " · Reported ${shortTimeLabel(it)}" } ?: "") +
                        (referrer?.let { " · Ref: ${it.name}" } ?: "") +
                        (samples.takeIf { it.isNotEmpty() }?.let { " · Sample${if (it.size == 1) "" else "s"}: ${it.joinToString(", ")}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * L3: one line per OUTSOURCED test on the order — "Outsourced · <partner>" plus
 * the lightweight "Sent to partner" step: a button until stamped, then the
 * stamp itself. Renders nothing when no line is outsourced (the overwhelmingly
 * common case pays zero pixels). Outsourced-ness is read from the live catalog
 * row (imported tests carry fulfillment/partner from the platform's lab_config).
 */
@Composable
private fun OutsourcedStrip(
    tests: List<LabOrderTest>,
    catalog: Map<String, LabTest>,
    canMark: Boolean,
    onMarkSent: (testId: String) -> Unit,
) {
    val outsourced = tests.mapNotNull { line ->
        val test = catalog[line.testId] ?: return@mapNotNull null
        if (test.isOutsourced) line to test else null
    }
    if (outsourced.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((line, test) in outsourced) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${line.testName} — Outsourced · ${test.outsourcePartner?.takeIf { it.isNotBlank() } ?: "partner lab"}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val sentAt = line.sentToPartnerAt
                    if (sentAt != null) {
                        Text(
                            "Sent to partner ${shortTimeLabel(sentAt)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    } else if (canMark) {
                        TextButton(onClick = { onMarkSent(line.testId) }) { Text("Mark sent to partner") }
                    } else {
                        Text(
                            "Not sent to partner",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/** "N of M results entered" + the thin bar that makes a half-done order obvious. */
@Composable
private fun EntryProgress(entered: Int, total: Int) {
    val fraction = if (total <= 0) 0f else entered.toFloat() / total.toFloat()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "$entered of $total results entered",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** The table's column strip — same widths as every data row. */
@Composable
private fun TableHeader() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeadCell("Parameter", Modifier.weight(W_PARAM))
            HeadCell("Result", Modifier.width(COL_RESULT))
            HeadCell("Unit", Modifier.width(COL_UNIT))
            HeadCell("Ref. range", Modifier.weight(W_RANGE))
            HeadCell("Flag", Modifier.width(COL_FLAG))
        }
    }
}

@Composable
private fun HeadCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier, style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
    )
}

/** The rail's fixed width — room for "Erythrocyte Sedimentation Rate" on two lines. */
private val RAIL_WIDTH = 236.dp

/** Rail + five-column table need this much; below it the tests become chips
 *  and the table keeps its full width (the table itself needs [WIDE_MIN_DP]). */
private const val RAIL_MIN_DP = 1120

/** Provenance text for an analyzer-filled test: the theme's info blue is too
 *  light for 11 sp on a white surface (AA needs 4.5:1), so light mode deepens it. */
@Composable
private fun analyzerInk(): Color = if (AppTheme.colors.isDark) AppTheme.colors.info else Color(0xFF1D4ED8)

/**
 * The order's table of contents: one line per test with a progress dot, who
 * entered it and when, and a count. Selecting a test swaps the tile; the
 * cell being typed in commits on the way out (blur).
 */
@Composable
private fun TestRail(
    groups: List<EntryGroup>,
    selectedId: String,
    /** The test whose cell has keyboard focus — shows "typing…" on its line. */
    typingIn: String?,
    /** Per-test release on: the sub-line prefers the sign-off stage over who typed. */
    perTest: Boolean = false,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = groups.count { it.done }
    val scroll = rememberScrollState()
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .verticalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "TESTS · $done OF ${groups.size} DONE",
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
        for (g in groups) {
            val isSelected = g.testId == selectedId
            // Keyboard navigation selects tests without touching the rail: bring
            // the selected line into view so the rail always shows where you are.
            val bring = remember { BringIntoViewRequester() }
            LaunchedEffect(isSelected) { if (isSelected) bring.bringIntoView() }
            Row(
                Modifier.fillMaxWidth()
                    .bringIntoViewRequester(bring)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .then(if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)) else Modifier)
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(g.testId) }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProgressDot(g)
                Column(Modifier.weight(1f)) {
                    Text(
                        g.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val sub = when {
                        typingIn == g.testId -> "typing…"
                        perTest && g.signedOff -> g.stageLine(::shortTimeLabel)
                        g.provenance != null -> g.provenance.short(::shortTimeLabel)
                        else -> null
                    }
                    sub?.let {
                        Text(
                            it, style = MaterialTheme.typography.labelSmall,
                            color = if (g.analyzer) analyzerInk() else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "${g.entered}/${g.total}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Under 900 dp the rail is a chip strip: same dots, catalog codes for names. */
@Composable
private fun TestChips(groups: List<EntryGroup>, selectedId: String, onSelect: (String) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selectedId) {
        val i = groups.indexOfFirst { it.testId == selectedId }
        if (i >= 0) runCatching { state.animateScrollToItem(i) }
    }
    LazyRow(
        Modifier.fillMaxWidth(),
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(groups, key = { it.testId }) { g ->
            FilterChip(
                selected = g.testId == selectedId,
                onClick = { onSelect(g.testId) },
                leadingIcon = { ProgressDot(g) },
                label = { Text("${g.code.ifBlank { g.name }} ${g.entered}/${g.total}", maxLines = 1) },
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Empty ring → part-filled → a green disc with a white tick when every value
 *  is in; blue instead of green when an analyzer filled it. */
@Composable
private fun ProgressDot(g: EntryGroup) {
    val color = when {
        g.analyzer -> analyzerInk()
        g.started -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val fraction = if (g.total == 0) 0f else g.entered.toFloat() / g.total.toFloat()
    Canvas(Modifier.size(14.dp)) {
        when {
            fraction >= 1f -> {
                drawCircle(color = color)
                val w = size.width
                val tick = Path().apply {
                    moveTo(w * 0.28f, w * 0.53f)
                    lineTo(w * 0.44f, w * 0.69f)
                    lineTo(w * 0.73f, w * 0.36f)
                }
                drawPath(tick, color = Color.White, style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            fraction > 0f -> {
                drawCircle(color = color, style = Stroke(width = 2.dp.toPx()))
                drawArc(color = color, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = true)
            }
            else -> drawCircle(color = color, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/**
 * One test: its name and catalog line, who filled it, the analyzer's alerts
 * when it left any, the column strip (pinned — it no longer scrolls away), and
 * the rows the caller emits.
 */
/** Per-test release controls the tile shows when the setting is on. */
private class TestRelease(
    val canVerify: Boolean,
    val canApprove: Boolean,
    val busy: Boolean,
    val onVerify: (testId: String) -> Unit,
    val onApprove: (testId: String) -> Unit,
    val onPrint: (testId: String) -> Unit,
)

@Composable
private fun TestTile(
    group: EntryGroup,
    test: LabTest?,
    wide: Boolean,
    listState: LazyListState,
    release: TestRelease? = null,
    modifier: Modifier = Modifier,
    rows: LazyListScope.() -> Unit,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    // The specimen as a chip — the same word the tube sticker carries.
                    sampleTypeDisplay(test?.sampleType)?.let { SampleChip(it) }
                }
                val about = listOfNotNull(
                    test?.category?.takeIf { it.isNotBlank() },
                    test?.method?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (about.isNotEmpty()) {
                    Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                group.provenance?.let { p ->
                    Text(
                        (if (group.analyzer) "⚡ " else "") + p.long(asAnalyzer = group.analyzer, time = ::shortTimeLabel),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (group.analyzer) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (group.analyzer) analyzerInk() else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${group.entered} of ${group.total} entered",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                release?.let { r -> TestStageActions(group, r) }
            }
        }
        if (group.alerts.isNotEmpty()) AlertsStrip(group.alerts)
        if (wide) TableHeader()
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = 28.dp),
            content = rows,
        )
    }
}

/**
 * One test's own stage button — the same ladder as the order-wide bar
 * (Verify → Approve → Print → Print again), plus the sign-off stamp line.
 * A test that is not fully entered shows nothing: entry is what it needs.
 */
@Composable
private fun TestStageActions(group: EntryGroup, r: TestRelease) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
        group.stageLine(::shortTimeLabel)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.success, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        when (group.stage) {
            TestStage.ENTERED ->
                if (r.canVerify) Button(onClick = { r.onVerify(group.testId) }, enabled = !r.busy) { Text("Verify test") }
                else Text("Awaiting verification", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TestStage.VERIFIED ->
                if (r.canApprove) Button(onClick = { r.onApprove(group.testId) }, enabled = !r.busy) { Text("Approve test") }
                else Text("Awaiting the pathologist's approval", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TestStage.APPROVED ->
                Button(onClick = { r.onPrint(group.testId) }, enabled = !r.busy) { Text("Print report") }
            TestStage.REPORTED ->
                OutlinedButton(onClick = { r.onPrint(group.testId) }, enabled = !r.busy) { Text("Print again") }
        }
    }
}

/** "SERUM" / "URINE" beside the test name — what this test was run on. */
@Composable
private fun SampleChip(sample: String) {
    Box(
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            sample.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

/** The analyzer's own alerts and abnormal flags — the cue to look at the
 *  scattergram (on the report) before verifying. */
@Composable
private fun AlertsStrip(alerts: List<String>) {
    val c = AppTheme.colors
    Surface(
        color = c.warningSoft, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            "Analyzer alerts · " + alerts.joinToString(" · ") + " — review before verifying",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.warning,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One parameter line. Wide → the five-column table row; narrow → two lines
 * (name + range above, input + unit + flag below). Never a card either way.
 */
@Composable
private fun ResultGridRow(
    row: GridRow,
    value: String,
    wide: Boolean,
    locked: Boolean,
    focused: Boolean,
    last: Boolean,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onEnter: () -> Unit,
) {
    // Live preview of the flag enterResult will freeze — same range brain.
    val liveFlag = when {
        value.isBlank() -> null
        else -> LabRepository.computeFlag(value, row.range) ?: row.result.flag
    }
    val bg = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else Color.Transparent

    val field: @Composable (Modifier) -> Unit = { m ->
        ResultField(
            value = value, enabled = !locked, numeric = row.numeric, last = last,
            focusRequester = focusRequester, onValueChange = onValueChange,
            onFocus = onFocus, onBlur = onBlur, onNext = onNext, onPrevious = onPrevious,
            onEnter = onEnter, modifier = m,
        )
    }

    if (wide) {
        Row(
            Modifier.fillMaxWidth().background(bg).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                row.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(W_PARAM),
            )
            field(Modifier.width(COL_RESULT))
            Text(
                row.unit, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(COL_UNIT),
            )
            Text(
                row.refDisplay, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(W_RANGE),
            )
            Box(Modifier.width(COL_FLAG), contentAlignment = Alignment.CenterStart) { FlagCell(liveFlag) }
        }
    } else {
        Column(
            Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(
                    row.refDisplay, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                field(Modifier.weight(1f))
                Text(
                    row.unit, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(COL_UNIT),
                )
                FlagCell(liveFlag)
            }
        }
    }
}

/**
 * The value box: a 34dp bordered field (a Material text field is 56dp tall and
 * would blow the row height apart). Blur commits — Tab/Shift-Tab step, Enter
 * jumps to the next empty cell, and both blur on the way out.
 */
@Composable
private fun ResultField(
    value: String,
    enabled: Boolean,
    numeric: Boolean,
    last: Boolean,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onNext = { onEnter() }, onDone = { onEnter() }),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { st ->
                if (st.isFocused) {
                    if (!focused) { focused = true; onFocus() }
                } else if (focused) {
                    focused = false; onBlur()
                }
            }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Tab -> { if (ev.isShiftPressed) onPrevious() else onNext(); true }
                    Key.Enter, Key.NumPadEnter -> { onEnter(); true }
                    else -> false
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(34.dp)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.surface else Color.Transparent,
                        RoundedCornerShape(7.dp),
                    )
                    .border(if (focused) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

/** Stage-appropriate primary actions — normal-width buttons, never a slab. */
@Composable
private fun ActionBar(
    status: String,
    busy: Boolean,
    /** P4 RBAC: verifying is for roles that verify (not the front desk). */
    canVerify: Boolean,
    /** P4 RBAC: only a pathologist (or the owner) may sign results off. */
    canApprove: Boolean,
    onForward: (String) -> Unit,
    onVerify: () -> Unit,
    onApprove: () -> Unit,
    onPrint: () -> Unit,
    /** WhatsApp sending is switched on in Settings — show it beside Print. */
    canWhatsapp: Boolean = false,
    onWhatsapp: () -> Unit = {},
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            LabStatus.REGISTERED ->
                Button(onClick = { onForward(LabStatus.COLLECTED) }, enabled = !busy) { Text("Mark collected") }
            LabStatus.COLLECTED ->
                Button(onClick = { onForward(LabStatus.IN_PROGRESS) }, enabled = !busy) { Text("Start processing") }
            LabStatus.ENTERED ->
                Button(onClick = onVerify, enabled = !busy && canVerify) { Text("Verify results") }
            LabStatus.VERIFIED ->
                Button(onClick = onApprove, enabled = !busy && canApprove) { Text("Approve") }
            LabStatus.APPROVED ->
                Button(onClick = onPrint, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text("Print report")
                }
            LabStatus.REPORTED, LabStatus.DELIVERED ->
                OutlinedButton(onClick = onPrint, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text("Print report again")
                }
        }
        // Only where a report exists to send: the same stages that offer Print.
        if (canWhatsapp && status in REPORT_READY) {
            OutlinedButton(onClick = onWhatsapp, enabled = !busy) { Text("WhatsApp") }
        }
    }
}

/** Stages at which the report exists and may be handed over. */
private val REPORT_READY = setOf(LabStatus.APPROVED, LabStatus.REPORTED, LabStatus.DELIVERED)

/** A finished bill lookup for [invoiceId]: [balance] null means this computer does not hold that bill. */
private data class BillLookup(val invoiceId: String?, val balance: com.bnm.lab.chat.InvoiceBalance?)
