package com.bnm.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.bnm.lab.instruments.CreateOrderContext
import com.bnm.lab.instruments.CreateOrderDraft
import com.bnm.lab.instruments.CreateOrderRefusal
import com.bnm.lab.instruments.CreatedFromResult
import com.bnm.lab.instruments.PatientMatch
import com.bnm.lab.instruments.QueuedFrame
import com.bnm.lab.instruments.StoredInstrumentFrame
import com.bnm.lab.instruments.rankTestsForFrame
import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.Referrer
import com.bnm.lab.lab.TestParameter
import com.bnm.lab.license.ReadOnlyReason
import com.bnm.lab.screens.settings.CreateOrderFromResultBody
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.ui.theme.ThemeChoice
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Draws the four faces of "Create order from this result" — the pre-filled
 * form, the duplicate-patient question, the lapsed-licence refusal and the
 * confirmation with its line about money — one ImageComposeScene per test, as
 * [ClaimQueueRenderTest] does.
 *
 * A layout-time crash never shows in a unit test and would close the
 * Instruments screen the moment a lab opened it. The PNGs in
 * build/create-order-render are for a human to look at afterwards: this dialog
 * is read under time pressure at a bench, and the four states have to be
 * legible, not merely non-crashing.
 */
class CreateOrderRenderTest {

    private val frame = StoredInstrumentFrame(
        driver = "mindray_hl7",
        specimenId = "BNMTEST-1",
        params = linkedMapOf(
            "WBC" to "12.40", "RBC" to "4.51", "HGB" to "138", "PLT" to "245",
            "LYM%" to "32.0", "NEU%" to "62.0", "MON%" to "6.0",
        ),
        units = mapOf("WBC" to "10*9/L", "RBC" to "10*12/L", "HGB" to "g/L", "PLT" to "10*9/L",
            "LYM%" to "%", "NEU%" to "%", "MON%" to "%"),
    )
    private val queued = QueuedFrame(
        id = "q1", instrumentId = "i1", specimenId = "BNMTEST-1",
        receivedAt = "2026-09-25T10:42:00Z", frame = frame,
    )

    private fun param(key: String, name: String, unit: String?) = TestParameter(key, name, unit, 1)

    /** A catalog with a haemogram that fits, a one-analyte test, and one that takes nothing. */
    private val catalog = listOf(
        LabTest(
            id = "t-cbc", code = "CBC", name = "Complete Blood Count", category = "Hematology", price = 350.0,
            parameters = listOf(
                param("hb", "Haemoglobin", "g/dL"), param("rbc", "RBC Count", "mill/cumm"),
                param("wbc", "Total WBC Count", "/cumm"), param("plt", "Platelet Count", "/cumm"),
                param("neut", "Neutrophils", "%"), param("lymph", "Lymphocytes", "%"),
                param("mono", "Monocytes", "%"), param("eos", "Eosinophils", "%"),
                param("baso", "Basophils", "%"), param("pcv", "PCV (Haematocrit)", "%"),
            ),
        ),
        LabTest(id = "t-hb", code = "HB", name = "Haemoglobin", category = "Hematology", price = 100.0,
            parameters = listOf(param("hb", "Haemoglobin", "g/dL"))),
        LabTest(id = "t-esr", code = "ESR", name = "Erythrocyte Sedimentation Rate", category = "Hematology",
            price = 100.0, parameters = listOf(param("esr", "ESR (1st hour)", "mm/hr"))),
    )

    private val context = CreateOrderContext(
        frame = frame,
        instrumentName = "BC-5130 bench 1",
        fits = rankTestsForFrame(frame.params.keys, catalog),
        referrers = listOf(Referrer(id = "r-1", name = "Dr S. Iyer", kind = "doctor")),
    )

    private val filled = CreateOrderDraft(
        patientName = "Asha Menon", sex = "F", ageText = "34", phone = "+91 98765 43210",
        testId = "t-cbc",
    )

    private fun render(name: String, width: Int = 640, height: Int = 900, content: @Composable () -> Unit) {
        val out = File("build/create-order-render").apply { mkdirs() }
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

    /** Everything the operator has to answer is either filled in or on screen. */
    @Composable
    private fun body(
        refusal: CreateOrderRefusal? = null,
        draft: CreateOrderDraft = filled,
        duplicates: List<PatientMatch>? = null,
        created: CreatedFromResult? = null,
        error: String? = null,
    ) = CreateOrderFromResultBody(
        queued = queued,
        instrumentName = "BC-5130 bench 1",
        accessionSeries = "ACC-S2-",
        refusal = refusal,
        context = if (refusal == null) context else null,
        draft = draft,
        onDraft = {},
        testQuery = "",
        onTestQuery = {},
        duplicates = duplicates,
        onUseExisting = {},
        onRegisterAnyway = {},
        onBackFromDuplicates = {},
        created = created,
        busy = false,
        error = error,
        onSubmit = {},
        onCancel = {},
        onBill = {},
        onDone = {},
    )

    @Test
    fun `the form, pre-filled - the best-fitting test chosen and saying how much of it this run fills`() {
        render("form-prefilled") { body() }
    }

    @Test
    fun `the duplicate-patient question - the record on file, with what it has been to this lab`() {
        val onFile = Patient(id = "p-asha", name = "Asha Menon", sex = "F", ageYears = 34,
            phone = "+91 98765 43210")
        val match = PatientMatch(
            patient = onFile,
            recentOrders = listOf(
                LabOrder(id = "o-2", accessionNo = "ACC-S2-00018", patientId = "p-asha",
                    status = LabStatus.APPROVED, createdAt = "2026-09-18T09:12:00Z"),
                LabOrder(id = "o-1", accessionNo = "ACC-S2-00004", patientId = "p-asha",
                    status = LabStatus.REPORTED, createdAt = "2026-08-30T11:40:00Z"),
            ),
            totalOrders = 2,
        )
        render("duplicate-patient", height = 620) { body(duplicates = listOf(match)) }
    }

    @Test
    fun `a lapsed licence - refused in the dialog, with the reason, not a dead button`() {
        render("refusal-lapsed", height = 420) {
            body(refusal = CreateOrderRefusal.Licence(ReadOnlyReason.EXPIRED))
        }
    }

    @Test
    fun `the confirmation - what landed, and the sentence about the bill nobody raised`() {
        val created = CreatedFromResult(
            order = LabOrder(id = "o-new", accessionNo = "ACC-S2-00019", patientId = "p-asha",
                status = LabStatus.ENTERED, createdAt = "2026-09-25T10:44:00Z"),
            patient = Patient(id = "p-asha", name = "Asha Menon", sex = "F", ageYears = 34),
            reusedPatient = false,
            testName = "Complete Blood Count",
            claimSummary = "Applied 10/18 params to ACC-S2-00019 · Complete Blood Count · 8 unmapped",
        )
        render("created-billing-note", height = 460) { body(created = created) }
    }
}
