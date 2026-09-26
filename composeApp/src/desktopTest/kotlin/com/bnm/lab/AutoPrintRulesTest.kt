package com.bnm.lab

import com.bnm.lab.instruments.MachineReport
import com.bnm.lab.instruments.StoredInstrumentFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules that decide WHAT auto-print sends to the printer.
 *
 * This is a feature that runs unattended next to a tray of paper, so the
 * selection has to be right for reasons that are not about code: a lab that
 * comes back to twenty sheets of the analyzer's own blank turns the feature
 * off and never turns it on again, and a lab that reprints the morning's work
 * every time the app restarts stops trusting the printout.
 *
 * The selection is expressed here as a pure function over the same inputs the
 * screen uses, so it can be tested without a printer or a UI.
 */
class AutoPrintRulesTest {

    private data class Row(val id: String, val receivedAt: String, val frame: StoredInstrumentFrame)

    private fun frame(specimen: String?) = StoredInstrumentFrame(
        driver = "mindray_hl7",
        specimenId = specimen,
        params = mapOf("WBC" to "8.1"),
        units = mapOf("WBC" to "10*9/L"),
    )

    /** Mirrors the screen's selection: not background, newer than the
     *  watermark, not already printed, oldest first. */
    private fun due(rows: List<Row>, after: String, printed: Set<String>): List<Row> =
        rows.filterNot { MachineReport.isBackgroundRun(it.frame) }
            .filter { it.receivedAt > after && it.id !in printed }
            .sortedBy { it.receivedAt }

    private val rows = listOf(
        Row("a", "2026-09-26T09:00:00Z", frame("11")),
        Row("bg", "2026-09-26T09:01:00Z", frame("Background")),
        Row("b", "2026-09-26T09:02:00Z", frame("12")),
        Row("c", "2026-09-26T09:03:00Z", frame("13")),
    )

    @Test
    fun `the analyzer's own blank is never printed`() {
        val ids = due(rows, after = "", printed = emptySet()).map { it.id }
        assertEquals(listOf("a", "b", "c"), ids, "a background count is not a patient report")
    }

    @Test
    fun `switching it on does not print the backlog`() {
        // The watermark is stamped with "now" when the toggle goes on, so
        // everything already on screen is behind it.
        val enabledAt = "2026-09-26T09:02:30Z"
        val ids = due(rows, after = enabledAt, printed = emptySet()).map { it.id }
        assertEquals(listOf("c"), ids, "only what arrives AFTER enabling")
    }

    @Test
    fun `a restart does not reprint what was already printed`() {
        // The watermark is persisted, so the app comes back knowing where it
        // got to even though the in-memory set is empty.
        val afterRestart = due(rows, after = "2026-09-26T09:03:00Z", printed = emptySet())
        assertTrue(afterRestart.isEmpty(), "everything up to the watermark is done")
    }

    @Test
    fun `nothing is printed twice within a session`() {
        val ids = due(rows, after = "", printed = setOf("a", "b")).map { it.id }
        assertEquals(listOf("c"), ids)
    }

    @Test
    fun `results print oldest first`() {
        // The tray should read in the order the bench ran the samples, not
        // whichever order the query happened to return.
        val shuffled = rows.reversed()
        val ids = due(shuffled, after = "", printed = emptySet()).map { it.id }
        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun `a sample with no id is still a patient and still prints`() {
        // Only "Background"/"Blank" is a blank. A bench that keyed no id at
        // all has still run a patient, and losing that report silently would
        // be far worse than printing one sheet too many.
        val anon = listOf(Row("x", "2026-09-26T10:00:00Z", frame(null)))
        assertEquals(listOf("x"), due(anon, after = "", printed = emptySet()).map { it.id })
    }
}
