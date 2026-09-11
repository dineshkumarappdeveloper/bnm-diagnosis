package com.bnm.lab.report

/**
 * Every finished report, filed on this computer as an ordinary PDF.
 *
 * THE POINT: a lab should not have to open the app to look at a report. The
 * archive is a plain folder tree the operator can browse, search, back up to a
 * pen drive, or attach to an email — the app writes into it and never needs to
 * be running for it to be useful.
 *
 * Laid out for a human, not a database:
 *
 *     BNM Lab Reports/
 *       2026-09/
 *         ACC-S1-00042 Kavitha Subramanian.pdf
 *         ACC-S1-00043 Muthu Kumar.pdf
 *
 * Month folders keep any one directory small and match how a lab thinks about
 * its filing; the accession leads the filename so a month sorts in the order
 * the work came in, and the patient's name follows so the file manager's own
 * search finds it.
 *
 * ONE FILE PER ORDER, rewritten each time the report is released: the archived
 * copy is always the complete report as it stands, never the slice that
 * happened to be printed last (see the per-test release feature).
 */
object ReportArchive {

    /** Folder name for a report finished at [iso]: "2026-09". Undated → "undated". */
    fun monthFolder(iso: String?): String {
        val d = iso?.trim().orEmpty()
        // ISO instants and plain dates both start yyyy-MM.
        if (d.length >= 7 && d[4] == '-' && d.take(4).all { it.isDigit() } && d.substring(5, 7).all { it.isDigit() }) {
            return d.take(7)
        }
        return "undated"
    }

    /** "ACC-S1-00042 Kavitha Subramanian.pdf" — safe on every filesystem. */
    fun fileName(accession: String, patientName: String?): String {
        val acc = sanitise(accession).ifBlank { "report" }
        val who = sanitise(patientName.orEmpty())
        val stem = if (who.isBlank()) acc else "$acc $who"
        // Windows caps a path component at 255; leave room for ".pdf".
        return stem.take(200).trimEnd() + ".pdf"
    }

    /**
     * Characters no filesystem should be asked to carry, plus the ones that
     * make a name awkward to type or script against. Runs of whitespace
     * collapse so "Kavitha   Subramanian" files as one space.
     */
    internal fun sanitise(s: String): String =
        s.map { c ->
            when {
                c.isLetterOrDigit() -> c
                c == '-' || c == '_' || c == '.' || c == '(' || c == ')' -> c
                // An apostrophe closes up rather than splitting a name:
                // O'Brien files as OBrien, not "O Brien".
                c == '\'' || c == '\u2019' -> '\u0000'
                c.isWhitespace() -> ' '
                else -> ' '
            }
        }.filterNot { it == '\u0000' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            .trim('.', ' ')

    /** Folder + file, as the archive stores it. */
    fun relativePath(finishedAtIso: String?, accession: String, patientName: String?): String =
        monthFolder(finishedAtIso) + "/" + fileName(accession, patientName)
}

/**
 * Where reports are filed by default: the operator's own Documents folder, so
 * the archive is somewhere they already look. Returns "" on platforms with no
 * user-visible filesystem to speak of.
 */
expect fun defaultReportsDir(): String

/**
 * Copy [sourcePath] into `[dir]/[relativePath]`, creating folders as needed and
 * overwriting an older copy of the same report. Returns the absolute path
 * written, or "" when it could not be (a read-only disk, a removed pen drive,
 * a path the lab has since deleted) — filing must never break printing.
 */
expect fun archiveReportFile(sourcePath: String, dir: String, relativePath: String): String

/** Show [path] in the file manager (Finder, Explorer). A short human status. */
expect fun revealInFileManager(path: String): String

/** Let the operator pick a folder; null when they cancelled or the platform has no picker. */
expect fun pickFolder(title: String): String?
