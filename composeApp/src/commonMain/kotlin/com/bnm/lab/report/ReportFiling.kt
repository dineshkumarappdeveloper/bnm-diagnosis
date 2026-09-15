package com.bnm.lab.report

import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.staff.StaffRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writing an order's report into the lab's reports folder — one place, used on
 * approval/release and when a signer's name changes.
 */
object ReportFiling {
    /** App-lifetime: a re-file started from a screen must finish after it closes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * File the approved part of [orderId] as a PDF in the reports folder. Only
     * approved tests: the folder is the lab's cabinet, and a half-signed report on
     * file would be worse than none. Silent on failure by design (a full disk, an
     * unplugged drive) — filing must never break a print.
     */
    suspend fun fileOrder(repo: LabRepository, assembler: ReportAssembler, orderId: String, labName: String?) {
        val prefs = ReportPrefs()
        if (!prefs.archiveReports) return
        val dir = prefs.reportsDir
        if (dir.isBlank()) return
        val order = repo.orderById(orderId) ?: return
        val patient = repo.patientById(order.patientId) ?: return
        val approved = repo.approvedTestIds(orderId)
        if (approved.isEmpty()) return
        runCatching {
            val doc = assembler.assemble(orderId, labName, stampReportedNow = false, testIds = approved) ?: return
            withContext(Dispatchers.Default) {
                val path = writeLabReportPdf(doc)
                if (path.isNotBlank()) {
                    archiveReportFile(
                        sourcePath = path,
                        dir = dir,
                        relativePath = ReportArchive.relativePath(
                            finishedAtIso = order.reportedAt ?: order.approvedAt ?: order.createdAt,
                            accession = order.accessionNo,
                            patientName = patient.name,
                        ),
                    )
                }
            }
        }
    }

    /**
     * A signer's name changed — typically the seeded "Lab Owner" entering their
     * real name as the lab's pathologist. Their published reports are queued for
     * re-upload and their filed reports re-written, so no copy of a report keeps
     * the old name. Runs in the background; call once per rename.
     */
    fun refileForRenamedSigner(repo: LabRepository, staff: StaffRepository, staffId: String) {
        scope.launch {
            runCatching {
                val orders = repo.onSignatoryRenamed(staffId)
                val assembler = ReportAssembler(repo, staff)
                orders.forEach { fileOrder(repo, assembler, it, labName = null) }
                AppLog.i("Reports", "signer renamed: re-queued uploads and re-filed ${orders.size} report(s)")
            }.onFailure { AppLog.w("Reports", "re-filing after a rename failed: ${it::class.simpleName}") }
        }
    }
}
