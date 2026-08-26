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

/**
 * Printed text for the Flag column: code + direction, criticals spelled out —
 * "N", "H ^", "L v", "A", "!! H ^ CRITICAL".
 *
 * ASCII ONLY, deliberately. The desktop renderer draws with PDFBox base-14
 * Helvetica in WinAnsi, which has no ↑ / ↓ / ⚠ glyph and would silently print
 * "?" on a medical report. Both PDF renderers share this one function, so the
 * desktop and Android outputs can never drift apart.
 *
 * Direction comes from the STORED flag code (frozen at entry), never from
 * re-judging the value against today's ranges. Criticals drop the redundant
 * "C" — "!!" is what marks them, and [flagLegend] says so on the paper.
 */
fun flagLabel(flag: String?): String {
    if (flag.isNullOrBlank()) return ""
    val arrow = when (LabRepository.flagDirection(flag)) {
        1 -> " ^"
        -1 -> " v"
        else -> ""
    }
    if (!LabRepository.isCriticalFlag(flag)) return flag + arrow // N / L / H / A
    return "!! " + flag.drop(1) + arrow + " CRITICAL"
}

/**
 * One-line key for the Flag column marks, printed under the results. A patient
 * or a referring doctor reads that paper without the app, so "^" and "!!" have
 * to explain themselves; only the marks actually present are listed.
 *
 * Empty when nothing on the report is abnormal — there is then nothing to
 * decode and the line is just noise. ASCII + Latin-1 only (see [flagLabel]).
 */
fun flagLegend(flags: Iterable<String?>): String {
    val present = flags.filterNotNull().filter { it.isNotBlank() }
    if (present.none { it != "N" }) return ""
    val parts = ArrayList<String>(5)
    if (present.any { it == "N" }) parts += "N within reference range"
    if (present.any { LabRepository.flagDirection(it) > 0 }) parts += "^ above range"
    if (present.any { LabRepository.flagDirection(it) < 0 }) parts += "v below range"
    if (present.any { it == "A" }) parts += "A abnormal"
    if (present.any { LabRepository.isCriticalFlag(it) }) parts += "!! CRITICAL - inform the physician"
    return "Flag key:  " + parts.joinToString("  ·  ")
}

/**
 * The approver's sign-off, drawn above the "Approved by" rule.
 *
 * NOT a data class on purpose: [imagePng] is a ByteArray, and a generated
 * `equals` over an array compares identity, which would quietly lie the first
 * time anyone diffed two docs.
 *
 * A lab with nothing on file simply has no [ReportSignature] and the block
 * prints exactly as it always did — an unsigned report must never be broken by
 * this feature.
 */
class ReportSignature(
    /** Decoded PNG bytes. The renderers hand these straight to their platform
     *  image decoder; null = print the name only, no image. */
    val imagePng: ByteArray?,
    /** "MD (Pathology)" — printed under the name. */
    val qualifications: String?,
    /** State medical-council number; an Indian lab report is expected to carry
     *  the signatory's registration. */
    val registrationNo: String?,
) {
    val hasImage: Boolean get() = imagePng != null && imagePng.isNotEmpty()
}

/**
 * The "scan to download this report" block. [matrix] is already encoded, so the
 * renderers only ever fill rectangles — no image codec, no resolution ceiling.
 *
 * A doc carries this ONLY when the report can actually resolve: standalone
 * licences get null and print nothing, because `admin-lab` refuses their
 * uploads (409 standalone_edition) and a dead QR on a medical report is worse
 * than no QR at all.
 */
class ReportQr(
    /** What the QR encodes — the public admin-lab resolver for this report. */
    val url: String,
    val matrix: QrMatrix,
    /** Printed under the code so a patient knows what they are looking at. */
    val caption: String,
    /** Second, smaller line: this link is personal to them. */
    val note: String,
)

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
    /** Key for the Flag-column marks ([flagLegend]); "" when nothing is flagged.
     *  Renderers draw it once, under the last section. */
    val flagLegendLine: String = "",
    val verifiedBy: String?,
    val approvedBy: String?,
    /** When the pathologist approved, "yyyy-MM-dd HH:mm". NABL expects the
     *  sign-off to be dated, and it is already stored — printing it costs
     *  nothing and a report without it is incomplete. */
    val approvedOn: String? = null,
    /** Approver's signature image + credentials; null = the old text-only block. */
    val signature: ReportSignature? = null,
    /** Report-download QR; null on standalone licences (see [ReportQr]). */
    val qr: ReportQr? = null,
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
    /** Approver sign-off image + credentials. Null keeps the pre-signature layout. */
    signature: ReportSignature? = null,
    /** Report-download QR. Null (the default) prints no QR block at all — which
     *  is what a standalone licence must get. Assembled by [ReportAssembler];
     *  this function stays pure and never mints a token of its own. */
    qr: ReportQr? = null,
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
        flagLegendLine = flagLegend(sections.flatMap { it.rows }.map { it.flag }),
        verifiedBy = results.firstNotNullOfOrNull { it.verifiedBy?.takeIf { v -> v.isNotBlank() } },
        approvedBy = results.firstNotNullOfOrNull { it.approvedBy?.takeIf { v -> v.isNotBlank() } },
        // Results carry the sign-off stamp; the order's own approved_at is the
        // fallback for rows written before results were stamped individually.
        approvedOn = (results.firstNotNullOfOrNull { it.approvedAt?.takeIf { a -> a.isNotBlank() } }
            ?: order.approvedAt)?.let { reportStamp(it) ?: it.take(10) },
        signature = signature,
        qr = qr,
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
    // The sample sections above deliberately cover every mark, so the legend is
    // stated literally rather than re-derived from the (inline) row list.
    flagLegendLine = flagLegend(listOf("N", "L", "H", "A", "CL")),
    verifiedBy = "Tech. S. Kumar",
    approvedBy = "Dr. A. Lakshmi, MD (Path.)",
    approvedOn = "2026-08-25 13:40",
    signature = sampleSignature(),
    // The preview exists to check the LAYOUT, and the QR block moves
    // "Verified by" across, so it has to be here. The token is deliberately
    // fake: this sheet is never handed to a patient, and scanning it 404s
    // exactly like any other unknown token (see admin-lab's resolver).
    qr = ReportShare.qrFor(SAMPLE_QR_TOKEN),
    generatedAt = "2026-08-25 13:47",
)

/** Obviously-not-real token for [sampleReportDoc]; 64 hex chars so it is the
 *  same size on the page as a live one. */
private const val SAMPLE_QR_TOKEN =
    "5a11e0000000000000000000000000000000000000000000000000000005a11e"

/**
 * A deterministic squiggle standing in for an inked signature, so the preview
 * shows the sign-off block at its real height instead of collapsing to the
 * text-only layout. Two sine strokes and a downstroke — enough ink to see the
 * spacing, no attempt to look like anybody's actual hand.
 */
private fun sampleSignature(): ReportSignature {
    val w = 720
    val h = 240
    val png = PngWriter.grayscale1Bit(w, h) { x, y ->
        val fx = x.toDouble() / w
        val fy = y.toDouble() / h
        val upper = 0.52 + kotlin.math.sin(fx * 10.0) * 0.20
        val lower = 0.58 + kotlin.math.sin(fx * 4.0 + 1.1) * 0.26
        kotlin.math.abs(fy - upper) < 0.05 ||
            kotlin.math.abs(fy - lower) < 0.04 ||
            (fx < 0.07 && kotlin.math.abs(fy - 0.5) < 0.32)
    }
    return ReportSignature(
        imagePng = png.takeIf { it.isNotEmpty() },
        qualifications = "MD (Pathology)",
        registrationNo = "TN/12345/2011",
    )
}

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
