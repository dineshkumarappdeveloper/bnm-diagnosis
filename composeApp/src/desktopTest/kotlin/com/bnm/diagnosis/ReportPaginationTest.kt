package com.bnm.diagnosis

import com.bnm.diagnosis.report.ReportPagination
import com.bnm.diagnosis.report.ReportRow
import com.bnm.diagnosis.report.ReportSection
import com.bnm.diagnosis.report.pageGroups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The page splitter is what both PDF renderers draw from, so the grouping
 * rules are pinned here once rather than inferred from two renderers' output.
 */
class ReportPaginationTest {

    private fun sec(title: String, dept: String?, vararg flags: String?) = ReportSection(
        title = title,
        rows = flags.map { ReportRow("p", "1", "", "0 - 2", it) },
        department = dept,
    )

    @Test
    fun `continuous is one group holding every section in order`() {
        val s = listOf(sec("A", "X"), sec("B", "Y"), sec("C", null))
        val g = pageGroups(ReportPagination.CONTINUOUS, s)
        assertEquals(1, g.size)
        assertNull(g[0].heading)
        assertEquals(s, g[0].sections)
    }

    @Test
    fun `per test is one group per section, no banner`() {
        val s = listOf(sec("A", "X"), sec("B", "X"), sec("C", null))
        val g = pageGroups(ReportPagination.PER_TEST, s)
        assertEquals(listOf("A", "B", "C"), g.map { it.sections.single().title })
        assertEquals(listOf(null, null, null), g.map { it.heading })
    }

    @Test
    fun `per department merges a department's tests even when they are not adjacent`() {
        // Order lines come in the order the receptionist ticked them; the
        // biochemistry sheet must still collect ALL biochemistry tests.
        val s = listOf(
            sec("CBC", "Hematology"),
            sec("LFT", "Biochemistry"),
            sec("HBsAg", "Serology"),
            sec("FBS", "Biochemistry"),
        )
        val g = pageGroups(ReportPagination.PER_DEPARTMENT, s)
        assertEquals(listOf("Hematology", "Biochemistry", "Serology"), g.map { it.heading })
        assertEquals(listOf("LFT", "FBS"), g[1].sections.map { it.title },
            "FBS must join the Biochemistry sheet opened by LFT")
    }

    @Test
    fun `per department keys are case and whitespace insensitive, banner keeps the first spelling`() {
        val s = listOf(sec("A", "Biochemistry"), sec("B", " biochemistry "), sec("C", "BIOCHEMISTRY"))
        val g = pageGroups(ReportPagination.PER_DEPARTMENT, s)
        assertEquals(1, g.size)
        assertEquals("Biochemistry", g[0].heading)
        assertEquals(listOf("A", "B", "C"), g[0].sections.map { it.title })
    }

    @Test
    fun `per department never merges uncategorised tests with each other`() {
        // Two tests with no category share nothing that says they belong on
        // one sheet, so each gets its own — the PER_TEST behaviour for them.
        val s = listOf(sec("A", null), sec("B", ""), sec("C", "   "))
        val g = pageGroups(ReportPagination.PER_DEPARTMENT, s)
        assertEquals(3, g.size)
        assertEquals(listOf(null, null, null), g.map { it.heading })
    }

    @Test
    fun `an empty report still yields one empty group so the sign-off prints`() {
        for (p in ReportPagination.entries) {
            val g = pageGroups(p, emptyList())
            assertEquals(1, g.size, "$p")
            assertEquals(0, g[0].sections.size, "$p")
        }
    }

    @Test
    fun `the flag key is per group, not per document`() {
        // A "!! CRITICAL" line on a sheet with nothing critical on it would send
        // a patient hunting for a mark that is on a different page.
        val s = listOf(sec("CBC", "Hematology", "N", "CL"), sec("FBS", "Biochemistry", "H"))
        val g = pageGroups(ReportPagination.PER_TEST, s)
        assert(g[0].flagLegendLine.contains("CRITICAL")) { g[0].flagLegendLine }
        assert(!g[1].flagLegendLine.contains("CRITICAL")) { g[1].flagLegendLine }
        assert(g[1].flagLegendLine.contains("^ above range")) { g[1].flagLegendLine }
    }

    @Test
    fun `unknown slugs fall back to one test per page`() {
        assertEquals(ReportPagination.PER_TEST, ReportPagination.fromSlug(null))
        assertEquals(ReportPagination.PER_TEST, ReportPagination.fromSlug("nonsense"))
        assertEquals(ReportPagination.PER_DEPARTMENT, ReportPagination.fromSlug("per_department"))
        assertEquals(ReportPagination.CONTINUOUS, ReportPagination.fromSlug("continuous"))
    }
}
