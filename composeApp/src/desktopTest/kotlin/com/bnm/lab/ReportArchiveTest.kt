package com.bnm.lab

import com.bnm.lab.report.ReportArchive
import com.bnm.lab.report.archiveReportFile
import com.bnm.lab.report.defaultReportsDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The reports folder: how a report is filed, and that filing never breaks a print. */
class ReportArchiveTest {

    @Test
    fun `a report is filed under its month, led by the accession`() {
        assertEquals(
            "2026-09/ACC-S1-00042 Kavitha Subramanian.pdf",
            ReportArchive.relativePath("2026-09-11T04:45:00Z", "ACC-S1-00042", "Kavitha Subramanian"),
        )
        // A plain date works as well as an instant.
        assertEquals("2026-01", ReportArchive.monthFolder("2026-01-05"))
        // Nothing usable → its own folder rather than a file loose at the top.
        assertEquals("undated", ReportArchive.monthFolder(null))
        assertEquals("undated", ReportArchive.monthFolder(""))
        assertEquals("undated", ReportArchive.monthFolder("11/09/2026"))
    }

    @Test
    fun `a filename survives every filesystem and stays readable`() {
        // Slashes and colons would break a path; an apostrophe closes up so a
        // name does not gain a word.
        assertEquals("ACC-1 Mary-Anne OBrien.pdf", ReportArchive.fileName("ACC-1", "Mary-Anne O'Brien"))
        assertEquals("ACC 1 R. S Kumar.pdf", ReportArchive.fileName("ACC/1", "R. S: Kumar"))
        // Runs of whitespace collapse; a name that is only punctuation drops out.
        assertEquals("ACC-S1-00042 Kavitha Subramanian.pdf", ReportArchive.fileName("ACC-S1-00042", "Kavitha   Subramanian"))
        assertEquals("ACC-S1-00042.pdf", ReportArchive.fileName("ACC-S1-00042", "***"))
        assertEquals("ACC-S1-00042.pdf", ReportArchive.fileName("ACC-S1-00042", null))
        assertEquals("report.pdf", ReportArchive.fileName("", null))
        // Windows caps a path component; a pathological name is trimmed, not rejected.
        val long = ReportArchive.fileName("ACC-1", "x".repeat(400))
        assertTrue(long.length <= 204, "${long.length}")
        assertTrue(long.endsWith(".pdf"))
    }

    @Test
    fun `filing copies the pdf, overwrites an older copy, and stays silent on failure`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "bnmlab-archive-test-${System.nanoTime()}")
        val src = File(tmp, "src/report.pdf").apply { parentFile.mkdirs(); writeText("first") }
        val dir = File(tmp, "Reports").absolutePath
        val rel = ReportArchive.relativePath("2026-09-11T04:45:00Z", "ACC-9", "Test Patient")

        val written = archiveReportFile(src.absolutePath, dir, rel)
        assertTrue(written.endsWith("2026-09/ACC-9 Test Patient.pdf"), written)
        assertEquals("first", File(written).readText())

        // A later release of the same order replaces the file, it does not pile up.
        src.writeText("second")
        assertEquals(written, archiveReportFile(src.absolutePath, dir, rel))
        assertEquals("second", File(written).readText())
        assertEquals(1, File(dir, "2026-09").listFiles()?.size)

        // Nothing to copy, or nowhere to put it: an empty answer, never a throw.
        assertEquals("", archiveReportFile(File(tmp, "gone.pdf").absolutePath, dir, rel))
        assertEquals("", archiveReportFile(src.absolutePath, "", rel))
        tmp.deleteRecursively()
    }

    @Test
    fun `the default folder is somewhere the operator already looks`() {
        val d = defaultReportsDir()
        assertTrue(d.isNotBlank())
        assertTrue(d.endsWith("BNM Lab Reports"), d)
        assertFalse("tmp" in d.lowercase(), "reports must not be filed in a temp directory: $d")
    }
}
