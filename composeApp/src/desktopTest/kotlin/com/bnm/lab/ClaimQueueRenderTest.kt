package com.bnm.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.bnm.lab.instruments.ClaimCandidate
import com.bnm.lab.instruments.ClaimCandidates
import com.bnm.lab.instruments.QueuedFrame
import com.bnm.lab.instruments.StoredInstrumentFrame
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.print.buildAnalyzerWorksheet
import com.bnm.lab.screens.settings.ClaimPickerBody
import com.bnm.lab.screens.settings.ClaimQueueRow
import com.bnm.lab.screens.settings.WorksheetBody
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.ui.theme.ThemeChoice
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Draws the claim queue's three surfaces — the row, the picker and the
 * worksheet preview — one ImageComposeScene per test, as LinkCheckRenderTest
 * does. A layout-time crash never shows in a unit test and would close the
 * Instruments screen the moment a lab opened it; the PNGs in
 * build/claim-queue-render are for a human to look at afterwards.
 */
class ClaimQueueRenderTest {

    private val frame = StoredInstrumentFrame(
        driver = "mindray_hl7",
        specimenId = "77",
        params = linkedMapOf(
            "WBC" to "12.40", "RBC" to "4.51", "HGB" to "138", "PLT" to "245",
            "LYM%" to "32.0", "NEU%" to "62.0", "MON%" to "6.0",
        ),
        units = mapOf("WBC" to "10*9/L", "RBC" to "10*12/L", "HGB" to "g/L", "PLT" to "10*9/L",
            "LYM%" to "%", "NEU%" to "%", "MON%" to "%"),
        meta = mapOf("test_mode" to "CBC+5DIFF", "blood_mode" to "Whole Blood", "flags" to "WBC:H"),
    )
    private val queued = QueuedFrame(
        id = "q1", instrumentId = "i1", specimenId = "77",
        receivedAt = "2026-09-25T10:42:00Z", frame = frame,
    )

    private fun candidate(
        accession: String, name: String, ageSex: String, phone: String?, tests: String,
        matched: Int, status: String = LabStatus.REGISTERED, at: String = "2026-09-25T10:30:00Z",
        canTakeResults: Boolean = true,
    ) = ClaimCandidate(
        orderId = "o-$accession", accessionNo = accession, status = status, registeredAt = at,
        patientName = name, ageSex = ageSex, phone = phone, tests = tests,
        matched = matched, total = frame.params.size, canTakeResults = canTakeResults,
    )

    /** Held-back orders, as the engine hands them over: real rows, real statuses. */
    private fun locked(vararg statuses: String) = statuses.mapIndexed { i, s ->
        candidate("ACC-S1-0003${i}", "Signed Off $i", "40 y / M", null,
            "Complete Blood Count", matched = 7, status = s, canTakeResults = false)
    }

    private fun render(name: String, width: Int = 620, height: Int = 760, content: @Composable () -> Unit) {
        val out = File("build/claim-queue-render").apply { mkdirs() }
        ImageComposeScene(width, height, Density(1f)) {
            AppTheme(themeChoice = ThemeChoice.LIGHT) {
                Surface { Column(Modifier.padding(16.dp)) { content() } }
            }
        }.use { scene ->
            var img = scene.render(0)
            repeat(4) { i -> img = scene.render((i + 1) * 50_000_000L) }
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test
    fun `the picker with several orders, the likely one first and three held back`() {
        val candidates = ClaimCandidates(
            frame = frame,
            open = listOf(
                candidate("ACC-S1-00042", "Asha Menon", "34 y / F", "+91 98765 43210",
                    "Complete Blood Count · Erythrocyte Sedimentation Rate", matched = 7),
                candidate("ACC-S1-00041", "Ravi Kumar", "51 y / M", "90000 11111",
                    "Complete Blood Count", matched = 7, status = LabStatus.COLLECTED,
                    at = "2026-09-25T09:58:00Z"),
                candidate("ACC-S1-00040", "Latha Rao", "60 y / F", null,
                    "Liver Function Test", matched = 0, at = "2026-09-25T09:15:00Z"),
                candidate("ACC-S1-00039", "Suresh Iyer", "8 mo / M", "90000 22222",
                    "Urine Routine & Microscopy", matched = 0, at = "2026-09-24T18:02:00Z"),
            ),
            locked = locked(LabStatus.VERIFIED, LabStatus.APPROVED, LabStatus.REPORTED),
            windowFull = true,
        )
        render("picker-candidates") {
            ClaimPickerBody(queued, "BC-5130 bench 1", candidates, query = "",
                onQuery = {}, busy = false, error = null, onAssign = {})
        }
    }

    @Test
    fun `the picker with nothing to offer - the order was never registered`() {
        render("picker-empty", height = 420) {
            ClaimPickerBody(queued, "BC-5130 bench 1", ClaimCandidates(frame, emptyList(), locked = emptyList()),
                query = "", onQuery = {}, busy = false, error = null, onAssign = {},
                // The empty state's advice is now a button — the render has to show it.
                onCreateOrder = {})
        }
    }

    @Test
    fun `the picker when the read failed - the error, and no instruction to register again`() {
        // candidates stay null on a failed read; the body must draw the error and
        // nothing that asserts anything about the worklist.
        render("picker-error", height = 420) {
            ClaimPickerBody(queued, "BC-5130 bench 1", candidates = null, query = "ACC-S1-00042",
                onQuery = {}, busy = false, error = "That result is gone", onAssign = {})
        }
    }

    @Test
    fun `the picker searched with nothing matching - still a way to register`() {
        // The way an unregistered sample actually surfaces in a busy lab: the
        // worklist is NOT empty, so the blank-query empty state never shows —
        // the operator types the patient's name and matches nothing. The button
        // has to be here too, or this is the trip the feature exists to remove.
        val candidates = ClaimCandidates(
            frame = frame,
            open = listOf(candidate("ACC-S1-00041", "Ravi Kumar", "51 y / M", "90000 11111",
                "Complete Blood Count", matched = 7)),
            locked = emptyList(),
            query = "Asha",
        )
        render("picker-searched-empty", height = 460) {
            ClaimPickerBody(queued, "BC-5130 bench 1", candidates, query = "Asha",
                onQuery = {}, busy = false, error = null, onAssign = {}, onCreateOrder = {})
        }
    }

    @Test
    fun `the picker narrowed to one by name - the hint says which key assigns`() {
        val candidates = ClaimCandidates(
            frame = frame,
            open = listOf(candidate("ACC-S1-00042", "Asha Menon", "34 y / F", "+91 98765 43210",
                "Complete Blood Count", matched = 7)),
            locked = emptyList(),
            query = "Asha",
        )
        render("picker-narrowed", height = 420) {
            ClaimPickerBody(queued, "BC-5130 bench 1", candidates, query = "Asha",
                onQuery = {}, busy = false, error = null, onAssign = {})
        }
    }

    @Test
    fun `the queue row - a sample nobody keyed a barcode for, told apart by its numbers`() {
        // 792 = the screen's own 760dp page width plus its 16dp gutters, so the
        // row is measured at the width a lab actually sees.
        render("queue-rows", width = 792, height = 300) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column {
                    ClaimQueueRow(queued, "BC-5130 bench 1", enabled = true,
                        onAssign = {}, onCreateOrder = {}, onPrint = {}, onDiscard = {})
                    ClaimQueueRow(
                        queued.copy(id = "q2", specimenId = null, receivedAt = "2026-09-25T10:51:00Z"),
                        "Mispa Count X — bench 2", enabled = false,
                        onAssign = {}, onCreateOrder = {}, onPrint = {}, onDiscard = {},
                    )
                }
            }
        }
    }

    @Test
    fun `the worksheet preview - the warning first, the analyzer's own numbers under it`() {
        val ws = buildAnalyzerWorksheet("Sunrise Diagnostics", queued, "BC-5130 bench 1", now = "2026-09-25 11:05")
        render("worksheet-preview", height = 620) {
            WorksheetBody(ws, status = null)
        }
    }
}
