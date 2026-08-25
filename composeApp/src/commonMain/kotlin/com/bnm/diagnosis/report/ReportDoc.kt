package com.bnm.diagnosis.report

import com.bnm.diagnosis.lab.LabOrder
import com.bnm.diagnosis.lab.LabOrderTest
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabResult
import com.bnm.diagnosis.lab.Patient
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Pure document model for the styled A4 lab report PDF. Built once in common
 * code ([buildReportDoc]) and rendered by the per-platform `writeLabReportPdf`
 * actuals — the actuals only DRAW, they never look at domain models or prefs.
 *
 * Results carry EXACTLY what was frozen at entry time (`value`/`unit`/`flag`/
 * `ref_display` from `lab_results`) — later catalog edits never shift a
 * printed report (same guarantee as the thermal `renderLabReport`).
 */

/** How the letterhead area is produced. */
enum class LetterheadMode {
    /** The app draws the letterhead (accent band, lab name, contact lines). */
    PRINTED,

    /** Lab prints on pre-printed letterpads: header/footer space is RESERVED
     *  but left completely blank. */
    PREPRINTED,
}

/** Small preset accent palette (letterhead band + section titles only). */
object ReportPalette {
    const val TEAL = 0x0E8C8C
    const val BLUE = 0x1467C8
    const val MAROON = 0x8C1D30
    const val GREEN = 0x1E7A3C

    /** name → 0xRRGGBB, in settings-swatch order. Teal is the default. */
    val presets: List<Pair<String, Int>> =
        listOf("Teal" to TEAL, "Blue" to BLUE, "Maroon" to MAROON, "Green" to GREEN)
}

/** Row-emphasis colours (0xRRGGBB) — the point of the styled report. */
object ReportColors {
    const val HIGH_RED = 0xC62828     // H / CH → bold red
    const val LOW_BLUE = 0x1565C0     // L / CL → bold blue
    const val ABNORMAL_AMBER = 0xB45309 // qualitative A → bold amber
}

/** Emphasis colour for a result flag, or null for a regular black row. */
fun flagEmphasisRgb(flag: String?): Int? = when (flag) {
    "H", "CH" -> ReportColors.HIGH_RED
    "L", "CL" -> ReportColors.LOW_BLUE
    "A" -> ReportColors.ABNORMAL_AMBER
    else -> null
}

/** Printed text for the Flag column; criticals spell it out. */
fun flagLabel(flag: String?): String = when (flag) {
    null, "" -> ""
    "CH" -> "H CRITICAL"
    "CL" -> "L CRITICAL"
    else -> flag // N / L / H / A
}

data class ReportRow(
    val param: String,
    val value: String,
    val unit: String,
    val ref: String,
    /** Raw flag as stored: N | L | H | CL | CH | A | null. */
    val flag: String?,
)

/** One test = one titled section with a Parameter/Result/Unit/Ref/Flag table. */
data class ReportSection(val title: String, val rows: List<ReportRow>)

data class ReportDoc(
    val mode: LetterheadMode,
    /** Reserved header band per page, in millimetres (drawn letterhead lives
     *  inside it in PRINTED mode; blank in PREPRINTED). */
    val headerMm: Float,
    /** Reserved footer band per page, in millimetres. */
    val footerMm: Float,
    /** Accent colour 0xRRGGBB (band, rules, section titles). */
    val accentRgb: Int,
    /** ALWAYS the LicenseManager lab name (read-only in-app). */
    val labName: String,
    /** Editable letterhead lines under the lab name: address / phone / email /
     *  extra (NABL, GSTIN…). Blank lines are already filtered out. */
    val letterheadLines: List<String>,
    // ── Patient / meta block ──
    val patientName: String,
    val ageSex: String,
    val phone: String?,
    val referrer: String?,
    val accession: String,
    val registered: String,
    val reported: String?,
    /** Uppercased priority, null when routine. */
    val priority: String?,
    val sections: List<ReportSection>,
    val verifiedBy: String?,
    val approvedBy: String?,
    val generatedAt: String,
)

/**
 * Assemble a [ReportDoc] from the live domain objects. Mirrors the field
 * derivation of the thermal `renderLabReport` (age label, frozen result rows,
 * verified/approved names) so both outputs always agree.
 */
fun buildReportDoc(
    labName: String,
    order: LabOrder,
    patient: Patient,
    tests: List<LabOrderTest>,
    results: List<LabResult>,
    referrerName: String? = null,
    mode: LetterheadMode = LetterheadMode.PRINTED,
    headerMm: Float = 40f,
    footerMm: Float = 20f,
    accentRgb: Int = ReportPalette.TEAL,
    letterheadLines: List<String> = emptyList(),
    /** Display name for a result row (catalog parameter name); defaults to the raw key. */
    paramName: (LabResult) -> String = { it.parameterKey },
): ReportDoc {
    val age = LabRepository.resolveAgeYears(patient.dob, patient.ageYears)
    val ageLabel = when {
        age == null -> "-"
        age < 1.0 -> "${(age * 12).toInt().coerceAtLeast(0)} mo"
        else -> "${age.toInt()} y"
    }
    val byTest = results.groupBy { it.testId }
    val sections = tests.map { t ->
        ReportSection(
            title = t.testName,
            rows = byTest[t.testId].orEmpty().map { r ->
                ReportRow(
                    param = paramName(r).replaceFirstChar { it.uppercase() },
                    value = r.value?.takeIf { it.isNotBlank() } ?: "-",
                    unit = r.unit.orEmpty(),
                    ref = r.refDisplay.orEmpty(),
                    flag = r.flag,
                )
            },
        )
    }
    return ReportDoc(
        mode = mode,
        headerMm = headerMm.coerceIn(0f, 120f),
        footerMm = footerMm.coerceIn(0f, 80f),
        accentRgb = accentRgb,
        labName = labName.trim().ifEmpty { "BNM Diagnosis" },
        letterheadLines = letterheadLines.map { it.trim() }.filter { it.isNotEmpty() },
        patientName = patient.name,
        ageSex = "$ageLabel / ${patient.sex.uppercase()}",
        phone = patient.phone?.takeIf { it.isNotBlank() },
        referrer = referrerName?.takeIf { it.isNotBlank() },
        accession = order.accessionNo,
        registered = reportStamp(order.createdAt) ?: order.createdAt.take(10),
        reported = order.reportedAt?.let { reportStamp(it) ?: it.take(10) },
        priority = order.priority.takeIf { !it.equals("routine", ignoreCase = true) }?.uppercase(),
        sections = sections,
        verifiedBy = results.firstNotNullOfOrNull { it.verifiedBy?.takeIf { v -> v.isNotBlank() } },
        approvedBy = results.firstNotNullOfOrNull { it.approvedBy?.takeIf { v -> v.isNotBlank() } },
        generatedAt = nowStamp(),
    )
}

/**
 * Deterministic sample report — the Settings "Preview sample report" button
 * and the desktop PDF tests both use it. Covers every row emphasis: N, H, L,
 * CH (critical), and a qualitative A.
 */
fun sampleReportDoc(
    labName: String = "BNM Diagnosis",
    mode: LetterheadMode = LetterheadMode.PRINTED,
    headerMm: Float = 40f,
    footerMm: Float = 20f,
    accentRgb: Int = ReportPalette.TEAL,
    letterheadLines: List<String> = listOf(
        "12 MG Road, Coimbatore 641001, Tamil Nadu",
        "Ph: 98765 43210 · lab@example.in",
        "NABL accredited · GSTIN 33ABCDE1234F1Z5",
    ),
): ReportDoc = ReportDoc(
    mode = mode,
    headerMm = headerMm,
    footerMm = footerMm,
    accentRgb = accentRgb,
    labName = labName,
    letterheadLines = letterheadLines.map { it.trim() }.filter { it.isNotEmpty() },
    patientName = "Kavitha Subramanian",
    ageSex = "42 y / F",
    phone = "98400 12345",
    referrer = "Dr. R. Menon, MD (Gen. Med.)",
    accession = "ACC-S1-00042",
    registered = "2026-08-25 09:12",
    reported = "2026-08-25 13:45",
    priority = null,
    sections = listOf(
        ReportSection(
            "Complete Blood Count (CBC)",
            listOf(
                ReportRow("Haemoglobin", "10.2", "g/dL", "12.0 - 15.0", "L"),
                ReportRow("Total leucocyte count", "11.8", "10^3/uL", "4.0 - 11.0", "H"),
                ReportRow("Neutrophils", "68", "%", "40 - 80", "N"),
                ReportRow("Lymphocytes", "24", "%", "20 - 40", "N"),
                ReportRow("Monocytes", "5", "%", "2 - 10", "N"),
                ReportRow("Eosinophils", "2", "%", "1 - 6", "N"),
                ReportRow("Basophils", "1", "%", "0 - 2", "N"),
                ReportRow("Platelet count", "88", "10^3/uL", "150 - 410", "CL"),
                ReportRow("RBC count", "4.1", "10^6/uL", "3.8 - 4.8", "N"),
                ReportRow("Haematocrit (PCV)", "32.4", "%", "36 - 46", "L"),
                ReportRow("MCV", "79", "fL", "83 - 101", "L"),
                ReportRow("MCH", "24.9", "pg", "27 - 32", "L"),
                ReportRow("MCHC", "31.5", "g/dL", "31.5 - 34.5", "N"),
            ),
        ),
        ReportSection(
            "Liver Function Test (LFT)",
            listOf(
                ReportRow("Bilirubin total", "1.1", "mg/dL", "0.3 - 1.2", "N"),
                ReportRow("Bilirubin direct", "0.3", "mg/dL", "0.0 - 0.4", "N"),
                ReportRow("SGOT (AST)", "142", "U/L", "5 - 40", "H"),
                ReportRow("SGPT (ALT)", "580", "U/L", "5 - 41", "CH"),
                ReportRow("Alkaline phosphatase", "112", "U/L", "35 - 129", "N"),
                ReportRow("Total protein", "6.9", "g/dL", "6.4 - 8.3", "N"),
                ReportRow("Albumin", "3.9", "g/dL", "3.5 - 5.2", "N"),
                ReportRow("A/G ratio", "1.3", "", "1.0 - 2.1", "N"),
            ),
        ),
        ReportSection(
            "Serology",
            listOf(
                ReportRow("HBsAg (rapid)", "Reactive", "", "Non-reactive", "A"),
                ReportRow("HIV I & II (rapid)", "Non-reactive", "", "Non-reactive", "N"),
                ReportRow("HCV (rapid)", "Non-reactive", "", "Non-reactive", "N"),
            ),
        ),
    ),
    verifiedBy = "Tech. S. Kumar",
    approvedBy = "Dr. A. Lakshmi, MD (Path.)",
    generatedAt = "2026-08-25 13:47",
)

/** ISO instant → "yyyy-MM-dd HH:mm" in the device timezone (null if unparseable). */
private fun reportStamp(iso: String): String? =
    runCatching { kotlin.time.Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault()) }
        .getOrNull()?.let {
            "${it.date} ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
        }

private fun nowStamp(): String {
    val dt = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.date} ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}
