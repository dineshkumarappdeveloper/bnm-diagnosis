package com.bnm.lab.instruments

import com.bnm.lab.report.LetterheadMode
import com.bnm.lab.report.ReportDoc
import com.bnm.lab.report.ReportPagination
import com.bnm.lab.report.ReportPalette

/**
 * Machine-only mode — the frame plus whatever the operator chose to type,
 * assembled into the same [ReportDoc] the ordered path prints.
 *
 * The point of going through [ReportDoc] rather than inventing a second
 * renderer: the letterhead, margins, accent colour, pagination, printer
 * profile and the histogram panel beside the table are all print SETTINGS the
 * lab has already configured, and they are unchanged in this mode. A lab that
 * switches a bench PC to machine-only should recognise the paper that comes
 * out of it.
 *
 * WHAT THE ANALYZER CANNOT TELL US. HL7 carries a patient id, a name, a sex
 * and a birth date; it carries an operator (OBR-32) and, on the vet models, a
 * veterinarian (OBR-10). It does NOT carry the referring doctor a CBC sheet
 * names. That is a [Header] field the operator may fill in (or leave blank,
 * which prints nothing) — it is the only typing in this mode, and nothing is
 * mandatory.
 *
 * NO BILLING. Machine-only mode raises no invoice, prints no bill number and
 * touches nothing in the billing spine: a lab that runs this mode bills from
 * whatever system it already uses, and a second bill number on the sheet would
 * be a second source of truth for money. The identifier printed where an
 * accession normally goes is the ANALYZER's own specimen id — the only number
 * the bench and the report can agree on.
 */
object MachineReportDoc {

    /**
     * The operator's optional corrections, typed on the print screen. Every
     * field blank = print exactly what the analyzer sent.
     */
    data class Header(
        /** Overrides the analyzer's PID-5 when the bench keyed it wrongly. */
        val patientName: String = "",
        /** Free text as printed ("42 / F"); blank = derived from the frame. */
        val ageSex: String = "",
        /** Referring doctor. Never sent by the analyzer. */
        val referrer: String = "",
    ) {
        val isEmpty: Boolean
            get() = patientName.isBlank() && ageSex.isBlank() && referrer.isBlank()
    }

    /**
     * Build the printable document, or null when the driver has no
     * machine-only template.
     *
     * [generatedAt] and [reported] are passed in rather than read from a clock
     * so this stays pure and the tests can pin the whole document.
     */
    fun build(
        frame: StoredInstrumentFrame,
        labName: String,
        header: Header = Header(),
        defaultReferrer: String = "",
        letterheadLines: List<String> = emptyList(),
        mode: LetterheadMode = LetterheadMode.PRINTED,
        headerMm: Float = 40f,
        footerMm: Float = 20f,
        accentRgb: Int = ReportPalette.TEAL,
        pagination: ReportPagination = ReportPagination.CONTINUOUS,
        reported: String? = null,
        generatedAt: String = "",
    ): ReportDoc? {
        val built = MachineReport.build(frame) ?: return null
        return ReportDoc(
            mode = mode,
            headerMm = headerMm,
            footerMm = footerMm,
            accentRgb = accentRgb,
            labName = labName,
            letterheadLines = letterheadLines,
            patientName = patientName(frame, header),
            ageSex = ageSex(frame, header),
            phone = null,
            referrer = header.referrer.trim().ifBlank { defaultReferrer.trim() }.ifBlank { null },
            // The analyzer's own sample id IS the accession here: this mode
            // raises no order, so there is no BNM number to print and inventing
            // one would imply a record that does not exist.
            accession = accession(frame, header),
            registered = frame.date.orEmpty(),
            reported = reported,
            priority = null,
            sections = built.sections,
            pagination = pagination,
            // Nobody has verified or approved anything: this mode does not
            // raise an order, so there is no sign-off to claim. The renderer
            // draws the blank LAB TECHNICIAN / CHIEF LABORATORY rules, which is
            // exactly what the lab's existing pre-printed sheet carries.
            verifiedBy = null,
            approvedBy = null,
            approvedOn = null,
            signature = null,
            verifierSignature = null,
            qr = null,
            generatedAt = generatedAt,
        )
    }

    /** Typed correction wins; else the analyzer's PID-5; else a plain dash. */
    fun patientName(frame: StoredInstrumentFrame, header: Header): String =
        header.patientName.trim()
            .ifBlank { frame.patientName?.trim().orEmpty() }
            .ifBlank { "—" }

    /**
     * "42 / F" as the sheet prints it.
     *
     * Age is the field most likely to be missing: HL7 carries a BIRTH DATE
     * (PID-7), but a haematology bench keys an age, and the analyzer then
     * either sends an age OBX (which the driver already keeps in
     * [StoredInstrumentFrame.meta]) or nothing at all. So: the typed value,
     * then the analyzer's age, then nothing — never a computed age from a
     * birth date the bench never entered.
     */
    fun ageSex(frame: StoredInstrumentFrame, header: Header): String {
        header.ageSex.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val age = frame.meta["age"]?.trim().orEmpty()
        val sex = when (frame.patientSex?.trim()?.uppercase()) {
            "M" -> "M"
            "F" -> "F"
            "O" -> "O"
            else -> ""
        }
        return listOf(age, sex).filter { it.isNotEmpty() }.joinToString(" / ")
    }

    /**
     * What prints where an accession number normally goes: the ANALYZER's own
     * specimen id, falling back to its patient id. This mode raises no order
     * and no invoice, so there is no BNM number and no bill number to print —
     * inventing either would imply a record that does not exist.
     */
    fun accession(frame: StoredInstrumentFrame, header: Header): String =
        frame.specimenId?.trim().orEmpty()
            .ifBlank { frame.patientId?.trim().orEmpty() }
}
