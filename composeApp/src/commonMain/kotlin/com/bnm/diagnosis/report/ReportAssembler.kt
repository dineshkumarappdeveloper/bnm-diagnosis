package com.bnm.diagnosis.report

import com.bnm.diagnosis.api.LabApi
import com.bnm.diagnosis.api.LabSyncDisabledException
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.license.LicenseManager
import com.bnm.diagnosis.staff.Staff
import com.bnm.diagnosis.staff.StaffRepository
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    ): ReportDoc? = withContext(Dispatchers.Default) {
        val order0 = repo.orderById(orderId) ?: return@withContext null
        val patient = repo.patientById(order0.patientId) ?: return@withContext null
        val order = if (stampReportedNow && order0.reportedAt == null && order0.status == LabStatus.APPROVED) {
            order0.copy(reportedAt = kotlin.time.Clock.System.now().toString())
        } else {
            order0
        }
        val tests = repo.orderTests(orderId)
        val results = repo.resultsForOrder(orderId)
        val catalog = tests.mapNotNull { t -> repo.testById(t.testId)?.let { t.testId to it } }.toMap()
        val approvedBy = results.firstNotNullOfOrNull { it.approvedBy?.takeIf { n -> n.isNotBlank() } }

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
            signature = signatureFor(approvedBy),
            qr = qrFor(order.id, order.accessionNo, order.status),
        )
    }

    /**
     * The approver's signature block, or null when the lab has nothing on file
     * (in which case the report prints exactly as it always did).
     *
     * MATCHED BY NAME, because a name is all the result row stores —
     * `lab_results.approved_by` is the display name the sign-off dialog stamped.
     * That is also what the report prints, so a mismatch here can only ever mean
     * "no image", never "the wrong doctor's signature": the name under the image
     * and the name we looked up are the same string.
     */
    private suspend fun signatureFor(approvedBy: String?): ReportSignature? {
        val name = approvedBy?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val person = staff.listAll().firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
            ?: return null
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
    private suspend fun qrFor(orderId: String, accessionNo: String, status: String): ReportQr? {
        if (status !in PUBLISHABLE_STATUSES) return null
        if (license.state.value.isStandalone) return null
        val token = runCatching { repo.reportShareToken(orderId, accessionNo) }.getOrNull() ?: return null
        return ReportShare.qrFor(token)
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
 * Drains `lab_reports` rows that were minted at approval but never uploaded.
 *
 * THE POINT: printing must never wait on a server. The QR on the paper is
 * already correct — it points at a token the lab minted itself — and this class
 * is what eventually makes that link resolve. A lab can print for a week
 * offline and every one of those QRs starts working the moment the drain runs.
 *
 * Re-renders the PDF from the (frozen) results rather than keeping the printed
 * file around: the temp file is long gone by the time connectivity returns, and
 * results are immutable after approval, so the bytes carry the same content.
 */
class ReportUploader(
    private val repo: LabRepository,
    private val api: LabApi,
    private val assembler: ReportAssembler,
    private val license: LicenseManager = LicenseManager(),
    /** Renders the doc and returns the file path — the platform PDF writer.
     *  Injectable so a test can drain without a real PDF stack. */
    private val render: (ReportDoc) -> String = { doc -> writeLabReportPdf(doc) },
) {

    /**
     * Upload up to [limit] queued reports. Returns how many now resolve.
     *
     * Never throws: a drain is background work behind an offline-first app, and
     * a failed upload simply stays queued for the next run. A standalone licence
     * stops the whole run on the first 409 rather than hammering an endpoint
     * that will always refuse it.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun drain(limit: Int = 5): Int = withContext(Dispatchers.Default) {
        if (license.state.value.isStandalone) return@withContext 0
        var done = 0
        for (row in repo.pendingReportUploads().take(limit)) {
            if (!ReportShare.isWellFormed(row.token)) continue // corrupt row: leave it, don't publish junk
            val doc = assembler.assemble(row.orderId, stampReportedNow = false) ?: continue
            val path = runCatching { render(doc) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val bytes = readReportBytes(path) ?: continue
            val result = api.publishReport(
                token = row.token,
                orderId = row.orderId,
                accessionNo = row.accessionNo,
                pdfBase64 = Base64.Default.encode(bytes),
            )
            when {
                result.isSuccess -> {
                    // sha256 stays null: the column is a re-upload optimisation,
                    // and there is no commonMain byte hasher to fill it with yet.
                    repo.markReportUploaded(row.orderId, null)
                    done++
                }
                // Standalone / unlinked licence — nothing here will ever publish.
                result.exceptionOrNull() is LabSyncDisabledException -> return@withContext done
                else -> Unit // transient: stays `pending`, retried next run
            }
        }
        done
    }
}
