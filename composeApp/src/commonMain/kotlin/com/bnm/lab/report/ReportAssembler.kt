package com.bnm.lab.report

import com.bnm.lab.api.LabApi
import com.bnm.lab.api.LabSyncDisabledException
import com.bnm.lab.lab.LabReportShare
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.lab.TestStage
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffRepository
import com.bnm.lab.sync.SyncPrefs
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Builds the PRINTABLE report document: the pure [buildReportDoc] plus the two
 * things that need the database — the approver's signature block and the
 * report-download QR.
 *
 * This exists so that [buildReportDoc] can stay what its KDoc promises: a pure
 * function of the domain objects. Minting a share token or reading a staff row
 * from inside it would make "build a document" a side-effecting database call.
 *
 * EVERYTHING HERE IS OFFLINE. The token is local randomness, the signature is a
 * local column, and the QR is encoded in-process. Nothing on this path talks to
 * a server — [ReportUploader] does that later, on its own schedule.
 */
class ReportAssembler(
    private val repo: LabRepository,
    private val staff: StaffRepository,
    private val license: LicenseManager = LicenseManager(),
    private val prefs: ReportPrefs = ReportPrefs(),
) {

    /**
     * Assemble the document for [orderId], or null when the order (or its
     * patient) has gone. [labName] overrides the licence's lab name — pass the
     * one the screen is already showing so a report can never disagree with the
     * header above it.
     *
     * [stampReportedNow]: THIS print is the reporting event, so an approved
     * order that has never been printed carries "now" as its reported time.
     * The status change itself still only happens after a successful print —
     * rendering alone commits nothing.
     */
    suspend fun assemble(
        orderId: String,
        labName: String? = null,
        stampReportedNow: Boolean = true,
        /** Per-test release: only these tests go on the paper, and the order's
         *  other unreported tests are named as "to follow". Null = the whole
         *  order, as before. */
        testIds: Set<String>? = null,
    ): ReportDoc? = withContext(Dispatchers.Default) {
        val order0 = repo.orderById(orderId) ?: return@withContext null
        val patient = repo.patientById(order0.patientId) ?: return@withContext null
        val allTests = repo.orderTests(orderId)
        val allResults = repo.resultsForOrder(orderId)
        val tests = if (testIds == null) allTests else allTests.filter { it.testId in testIds }
        val included = tests.map { it.testId }.toSet()
        val results = if (testIds == null) allResults else allResults.filter { it.testId in included }
        val byTest = allResults.groupBy { it.testId }
        val toFollow = allTests.filter { t ->
            t.testId !in included && TestStage.of(byTest[t.testId].orEmpty()) != TestStage.REPORTED
        }.map { it.testName }
        // "Reported" on the paper: THIS print is the reporting event for any
        // included test that has not gone out yet; otherwise the latest time an
        // included row went out (per-test release), else the order's own stamp.
        val includedApproved = results.isNotEmpty() && results.all { it.approvedAt != null }
        val now = kotlin.time.Clock.System.now().toString()
        val reportedAt = when {
            stampReportedNow && includedApproved && results.any { it.reportedAt == null } -> now
            stampReportedNow && testIds == null && order0.reportedAt == null && order0.status == LabStatus.APPROVED -> now
            else -> results.mapNotNull { it.reportedAt }.maxOrNull() ?: order0.reportedAt
        }
        val order = order0.copy(reportedAt = reportedAt)
        val catalog = tests.mapNotNull { t -> repo.testById(t.testId)?.let { t.testId to it } }.toMap()
        // Analyzer curves / scattergram, per test — the histogram panel.
        val graphs = repo.graphsForOrder(orderId).groupBy { it.testId }
        val approvedBy = results.firstNotNullOfOrNull { it.approvedBy?.takeIf { n -> n.isNotBlank() } }
        val verifiedBy = results.firstNotNullOfOrNull { it.verifiedBy?.takeIf { n -> n.isNotBlank() } }
        val approvedById = results.firstNotNullOfOrNull { it.approvedById?.takeIf { n -> n.isNotBlank() } }
        val verifiedById = results.firstNotNullOfOrNull { it.verifiedById?.takeIf { n -> n.isNotBlank() } }

        buildReportDoc(
            labName = labName?.takeIf { it.isNotBlank() }
                ?: license.state.value.labName.orEmpty(),
            order = order,
            patient = patient,
            tests = tests,
            results = results,
            referrerName = order.referrerId?.let { repo.referrerById(it) }?.name,
            mode = prefs.mode(),
            headerMm = prefs.headerMm.toFloat(),
            footerMm = prefs.footerMm.toFloat(),
            accentRgb = prefs.accentRgb,
            letterheadLines = prefs.letterheadLines(),
            paramName = { r ->
                catalog[r.testId]?.parameters?.firstOrNull { it.key == r.parameterKey }?.name
                    ?: r.parameterKey
            },
            // The NAME follows the person too: someone who corrected their name
            // (the seeded "Lab Owner" placeholder above all) reprints under the
            // current one. Rows without an id keep their snapshot.
            verifiedByName = currentName(verifiedBy, verifiedById),
            approvedByName = currentName(approvedBy, approvedById),
            signature = signatureFor(approvedBy, approvedById),
            // Same lookup for the technician who verified: whoever was signed
            // in when Verify was pressed is the name (and id) on the row, and
            // their stored signature is what prints on the left.
            verifierSignature = signatureFor(verifiedBy, verifiedById),
            // A QR needs a report that may be published: the whole order at
            // approved+, or — per-test release — every test on THIS paper approved.
            qr = qrFor(order.id, order.accessionNo, publishable = order.status in PUBLISHABLE_STATUSES || includedApproved),
            pagination = prefs.pagination(),
            // The department is the catalog category; the order line only
            // snapshots the test NAME, so it comes from the catalog lookup the
            // parameter names already use.
            department = { t -> catalog[t.testId]?.category },
            sampleType = { t -> catalog[t.testId]?.sampleType },
            graphsFor = { t -> toReportGraphs(graphs[t.testId].orEmpty()) },
            toFollow = toFollow,
        )
    }

    /** The current staff name for a stamped id, else the stored snapshot. */
    internal suspend fun currentName(stored: String?, staffId: String?): String? =
        staffId?.takeIf { it.isNotBlank() }?.let { staff.byId(it)?.name?.trim()?.takeIf { n -> n.isNotEmpty() } } ?: stored

    /**
     * A signatory's signature block (approver or verifier), or null when the
     * lab has nothing on file for that person (in which case that side of the
     * sign-off prints as name only, exactly as it always did).
     *
     * MATCHED BY STAFF ID when the row carries one — stamped at verify/approve
     * since 2026-09-07 — so the ink is that PERSON's even after a namesake is
     * hired or the original retires; an id that resolves to someone with no
     * signature is final (name only), never a fall-through to a namesake. Rows
     * from before ids were stamped carry only the display name, which falls
     * back to the case-insensitive name match (active rows first) — for those
     * a mismatch can only ever mean "no image", since the name under the
     * image and the name looked up are the same string.
     */
    internal suspend fun signatureFor(signatory: String?, staffId: String? = null): ReportSignature? {
        val byId = staffId?.takeIf { it.isNotBlank() }?.let { staff.byId(it) }
        val person = byId ?: run {
            val name = signatory?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            staff.listAll().firstOrNull { it.name.trim().equals(name, ignoreCase = true) } ?: return null
        }
        val png = decodeSignaturePng(person)
        val quals = person.qualifications?.takeIf { it.isNotBlank() }
        val reg = person.registrationNo?.takeIf { it.isNotBlank() }
        if (png == null && quals == null && reg == null) return null
        return ReportSignature(imagePng = png, qualifications = quals, registrationNo = reg)
    }

    /**
     * The QR block, or null when this report must not carry one:
     *  - the order has not been approved yet (there is no report to download),
     *  - or the licence is STANDALONE. `admin-lab` refuses a standalone upload
     *    (409 standalone_edition), so the code would resolve to nothing for the
     *    life of the paper. A permanently dead QR on a medical report is worse
     *    than no QR, so those labs get none.
     *
     * Minting is idempotent per order — a reprint reuses the token that is
     * already on the copies the patient was handed.
     *
     * "At approval" in practice means the first time an approved report is
     * rendered, which is where the edition is known: minting inside
     * `approveOrder` would give a standalone lab a `pending` row that can never
     * drain. Either way the token exists before any byte reaches the server, so
     * the printed code is correct with zero network — which is the property
     * that actually matters.
     */
    private suspend fun qrFor(orderId: String, accessionNo: String, publishable: Boolean): ReportQr? {
        if (!publishable) return null
        if (license.state.value.isStandalone) return null
        val token = runCatching { repo.reportShareToken(orderId, accessionNo) }.getOrNull() ?: return null
        // Which link goes on the paper is the server's call, not this build's:
        // the page link is printed only once a heartbeat has said the page is
        // live. Everything else prints the permanent resolver, which forwards to
        // the page the day it ships. See ReportShare.resolveUrl.
        return ReportShare.qrFor(token, pageLive = license.state.value.reportPageLive)
    }

    companion object {
        /** Statuses at which a report exists and may be published. */
        val PUBLISHABLE_STATUSES = setOf(LabStatus.APPROVED, LabStatus.REPORTED, LabStatus.DELIVERED)

        /**
         * `staff.signature_png` → raw PNG bytes, or null for anything unusable.
         *
         * Tolerant on purpose: the column may hold a bare base64 payload (what
         * the signature pad writes) or a `data:image/png;base64,…` URL (what a
         * paste from a browser produces), and either may carry line breaks.
         * Anything that will not decode is treated as "no signature" — a report
         * must print regardless.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun decodeSignaturePng(person: Staff): ByteArray? {
            val raw = person.signaturePng?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val payload = raw.substringAfter("base64,", raw).filterNot { it.isWhitespace() }
            return runCatching { Base64.Default.decode(payload) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * Drains `lab_reports` rows that were minted at approval but never published.
 *
 * THE POINT: printing must never wait on a server. The QR on the paper is
 * already correct — it points at a token the lab minted itself — and this class
 * is what eventually makes that link resolve. A lab can print for a week
 * offline and every one of those QRs starts working the moment the drain runs.
 *
 * Publishes the report as DATA ([buildReportSnapshot]), not a PDF: the
 * app.bnmapp.com page draws the report — and its PDF — in the patient's
 * browser, so the server keeps a few kilobytes and no document. The snapshot is
 * rebuilt from the (frozen) results rather than kept from the print: results
 * are immutable after approval, so it carries the same content. The
 * signatories are resolved again by the staff ID stamped on the rows, so it is
 * the same PEOPLE — with whatever signature they have on file at upload.
 *
 * A row that fails (server down, refused, cannot be built) is counted and
 * backed off ([retryDelay]) and sinks below fresh rows, so a stuck report can
 * never hold up the ones behind it.
 */
class ReportUploader(
    private val repo: LabRepository,
    private val api: LabApi,
    private val assembler: ReportAssembler,
    private val license: LicenseManager = LicenseManager(),
    private val prefs: SyncPrefs = SyncPrefs(),
    /** The publish call — [LabApi.publishReport] with the snapshot. Injectable
     *  so a test can drain without a server. */
    private val publish: suspend (row: LabReportShare, snapshot: JsonObject) -> Result<Unit> = { row, snapshot ->
        api.publishReport(token = row.token, orderId = row.orderId, accessionNo = row.accessionNo, report = snapshot)
    },
    private val clock: () -> Instant = { kotlin.time.Clock.System.now() },
) {

    /**
     * Publish up to [limit] queued reports that are due. Returns how many now
     * resolve (an unchanged report that needed no upload counts).
     *
     * Never throws: a drain is background work behind an offline-first app, and
     * a failed upload simply stays queued, backed off, for a later run. A
     * standalone licence stops the whole run on the first 409 rather than
     * hammering an endpoint that will always refuse it.
     */
    suspend fun drain(limit: Int = 5): Int = withContext(Dispatchers.Default) {
        if (license.state.value.isStandalone) return@withContext 0
        // Once per install: reports this seat published as PDFs go back in the
        // queue and republish as snapshots — the page reads nothing else.
        if (!prefs.reportsRequeuedForSnapshot) {
            runCatching { repo.requeueUploadedReports() }.onSuccess { prefs.reportsRequeuedForSnapshot = true }
        }
        val now = clock()
        var done = 0
        for (row in repo.pendingReportUploads().filter { it.isDue(now) }.take(limit)) {
            val defer = suspend {
                repo.deferReportUpload(row.orderId, row.token, (now + retryDelay(row.attempts + 1)).toString())
            }
            // Corrupt row: never publish junk, and never let it block the queue.
            if (!ReportShare.isWellFormed(row.token)) { defer(); continue }
            // Per-test release: the link resolves to every test signed off so
            // far (re-queued as more are released); nothing unapproved leaves.
            val approved = repo.approvedTestIds(row.orderId)
            if (approved.isEmpty()) { defer(); continue }
            val doc = assembler.assemble(row.orderId, stampReportedNow = false, testIds = approved)
            if (doc == null) { defer(); continue }
            val snapshot = runCatching { buildReportSnapshot(doc, generatedAt = now.toString()) }.getOrNull()
            if (snapshot == null) { defer(); continue }
            val sha = ReportSnapshot.contentSha256(snapshot)
            // Requeued, but the rebuilt report is what the server already has
            // (a reprint, a released test that was already on it): no upload.
            if (sha == row.sha256) {
                repo.markReportUnchanged(row.orderId, row.token)
                done++
                continue
            }
            val result = publish(row, snapshot)
            when {
                result.isSuccess -> {
                    repo.markReportUploaded(row.orderId, row.token, sha)
                    done++
                }
                // Standalone / unlinked licence — nothing here will ever publish.
                result.exceptionOrNull() is LabSyncDisabledException -> return@withContext done
                else -> defer() // stays `pending`, retried after the backoff
            }
        }
        done
    }

    companion object {
        /**
         * How long after the [attempt]th failure the row is tried again: the
         * sweep interval (5 min), doubling, capped at 6 h. Never gives up — a
         * report that cannot publish today (a server not deployed yet, a
         * licence re-linked) must still publish once it can.
         */
        fun retryDelay(attempt: Int): Duration {
            val steps = (attempt - 1).coerceIn(0, 10)
            return (5.minutes * (1 shl steps)).coerceAtMost(6.hours)
        }
    }
}
