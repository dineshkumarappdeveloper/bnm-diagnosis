package com.bnm.diagnosis.print

import com.bnm.diagnosis.lab.LabOrder
import com.bnm.diagnosis.lab.LabOrderTest
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabResult
import com.bnm.diagnosis.lab.Patient
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Render a LABORATORY REPORT as monospace text (same helper style as
 * [renderReceiptText]): lab-name header, patient identity block, per-test
 * sections with aligned `param  value  unit  ref-range  flag` rows (H/L/A
 * marked with `*`, criticals CL/CH with `**`), and the verified/approved
 * sign-off footer. Default width 64 chars ≈ A4 via the system print dialog;
 * pass the thermal paper width for LAN/BT ESC-POS printers (narrow widths
 * fall back to a stacked two-line row layout).
 *
 * Results print EXACTLY as frozen at entry time (`value`/`unit`/`flag`/
 * `ref_display` from `lab_results`) — later catalog edits never shift a
 * printed report.
 */
fun renderLabReport(
    labName: String,
    order: LabOrder,
    patient: Patient,
    tests: List<LabOrderTest>,
    results: List<LabResult>,
    referrerName: String? = null,
    widthChars: Int = 64,
    /** Display name for a result row (catalog parameter name); defaults to the raw key. */
    paramName: (LabResult) -> String = { it.parameterKey },
): String {
    val w = widthChars.coerceIn(32, 80)
    val sb = StringBuilder()
    fun ln(s: String = "") { sb.append(s).append('\n') }
    fun rule(ch: Char = '-') = ln(ch.toString().repeat(w))
    fun center(s: String) = ln(" ".repeat(((w - s.length) / 2).coerceAtLeast(0)) + s)
    fun wrap(s: String) { var t = s.trim(); if (t.isEmpty()) return; while (t.length > w) { ln(t.take(w)); t = t.drop(w).trim() }; ln(t) }

    // ── Header ──
    center(labName.trim().ifEmpty { "BNM Diagnosis" }.uppercase())
    rule('=')
    center("LABORATORY REPORT")
    rule('=')

    // ── Patient block ──
    val age = LabRepository.resolveAgeYears(patient.dob, patient.ageYears)
    val ageLabel = when {
        age == null -> "-"
        age < 1.0 -> "${(age * 12).toInt().coerceAtLeast(0)} mo"
        else -> "${age.toInt()} y"
    }
    wrap("Patient   : ${patient.name}")
    ln("Age / Sex : $ageLabel / ${patient.sex.uppercase()}")
    patient.phone?.takeIf { it.isNotBlank() }?.let { ln("Phone     : $it") }
    ln("Accession : ${order.accessionNo}")
    referrerName?.takeIf { it.isNotBlank() }?.let { wrap("Referred  : $it") }
    if (!order.priority.equals("routine", ignoreCase = true)) ln("Priority  : ${order.priority.uppercase()}")
    ln("Registered: ${localStamp(order.createdAt) ?: order.createdAt.take(10)}")
    order.reportedAt?.let { ln("Reported  : ${localStamp(it) ?: it.take(10)}") }
    rule()

    // ── Per-test result sections ──
    val byTest = results.groupBy { it.testId }
    val tabular = w >= 56
    // Column plan (tabular): param | value | unit | ref-range | flag.
    val flagW = 4; val unitW = 8; val refW = 15; val valueW = 9
    val paramW = w - (flagW + unitW + refW + valueW + 4)

    fun flagCol(r: LabResult): String = when (r.flag) {
        null -> ""
        "CL", "CH" -> "${r.flag}**"
        "N" -> "N"
        else -> "${r.flag}*" // L / H / A
    }

    fun cell(s: String, width: Int) = if (s.length > width) s.take(width) else s.padEnd(width)

    tests.forEachIndexed { i, t ->
        if (i > 0) ln()
        wrap(t.testName.uppercase())
        rule()
        if (tabular) {
            ln(cell("Parameter", paramW) + " " + cell("Result", valueW) + " " + cell("Unit", unitW) + " " + cell("Ref. range", refW) + " " + "Flag")
        }
        for (r in byTest[t.testId].orEmpty()) {
            val name = paramName(r).replaceFirstChar { it.uppercase() }
            val value = r.value?.takeIf { it.isNotBlank() } ?: "-"
            val unit = r.unit.orEmpty()
            val ref = r.refDisplay.orEmpty()
            val flag = flagCol(r)
            if (tabular) {
                if (name.length <= paramW) {
                    ln(cell(name, paramW) + " " + cell(value, valueW) + " " + cell(unit, unitW) + " " + cell(ref, refW) + " " + flag)
                } else {
                    wrap(name)
                    ln(cell("", paramW) + " " + cell(value, valueW) + " " + cell(unit, unitW) + " " + cell(ref, refW) + " " + flag)
                }
            } else {
                wrap(name)
                val refPart = if (ref.isNotBlank()) "  [$ref]" else ""
                wrap("    $value ${unit.trim()}$refPart  $flag".trimEnd())
            }
        }
        rule()
    }

    // ── Sign-off footer ──
    val verifiedBy = results.firstNotNullOfOrNull { it.verifiedBy?.takeIf { v -> v.isNotBlank() } }
    val approvedBy = results.firstNotNullOfOrNull { it.approvedBy?.takeIf { v -> v.isNotBlank() } }
    ln("Verified by : ${verifiedBy ?: "-"}")
    ln("Approved by : ${approvedBy ?: "-"}")
    ln(); ln(); ln() // signature gap
    center(approvedBy ?: "Authorised Signatory")
    rule()
    center("This is a computer-generated report.")
    return sb.toString()
}

/** ISO instant → "yyyy-MM-dd HH:mm" in the device's timezone (null if unparseable). */
private fun localStamp(iso: String): String? =
    runCatching { kotlin.time.Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault()) }
        .getOrNull()?.let {
            "${it.date} ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
        }
