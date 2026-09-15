package com.bnm.lab.backup

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Retention decides from file names alone: 12 newest by generation number, the
 * best of each of the last 30 days, the best of each of the last 12 months, a
 * floor of 3, and nothing at all while paused.
 */
class BackupRetentionTest {

    private val today = LocalDate.of(2026, 9, 15)
    private fun e(seq: Long, date: LocalDate) = BackupRetention.Entry("bnmlab-x-$seq.bnmlab", seq, date)

    @Test
    fun `the exact keep and delete sets across the three tiers`() {
        val entries = ArrayList<BackupRetention.Entry>()
        // 40 generations today: only the 12 newest survive the seq tier (the day tier adds nothing new).
        for (s in 200L..239L) entries += e(s, today)
        // One a day for the 45 days before today: the last 29 days stay, the 16 before them go.
        for (i in 1..45) entries += e(100L + (45 - i), today.minusDays(i.toLong()))
        // Old monthly survivors and one too old.
        entries += e(1, LocalDate.of(2025, 8, 20))   // 13 months back: gone
        entries += e(2, LocalDate.of(2025, 10, 5))   // Oct 2025 is the 12th month back: kept via its best…
        entries += e(3, LocalDate.of(2025, 10, 20))  // …which is this one, so 2 goes
        entries += e(4, LocalDate.of(2026, 1, 15))   // kept

        val plan = BackupRetention.plan(entries, today, paused = false)
        val deleted = plan.delete.map { it.seq }.toSet()
        val expectedDeleted = setOf(1L, 2L) + (100L..115L) + (200L..227L)
        assertEquals(expectedDeleted, deleted)
        assertEquals(entries.size - expectedDeleted.size, plan.keep.size)
        assertTrue(plan.keep.map { it.seq }.containsAll((228L..239L).toList()))
        assertTrue(plan.keep.map { it.seq }.containsAll((116L..144L).toList()))
        assertTrue(plan.keep.map { it.seq }.containsAll(listOf(3L, 4L)))
        // Newest first.
        assertEquals(plan.keep.map { it.seq }, plan.keep.map { it.seq }.sortedDescending())
    }

    @Test
    fun `the floor - never below three generations, whatever the tiers say`() {
        val old = LocalDate.of(2020, 1, 1)
        val few = listOf(e(1, old), e(2, old))
        assertTrue(BackupRetention.plan(few, today, paused = false).delete.isEmpty())
        // Five generations, all years old, with the seq tier squeezed to one
        // (what "prune to the floor" on a full pendrive asks for): three stay.
        val five = (1L..5L).map { e(it, old.plusDays(it)) }
        val plan = BackupRetention.plan(five, today, paused = false, keepNewest = 1, dailyDays = 0, monthlyMonths = 0)
        assertEquals(setOf(1L, 2L), plan.delete.map { it.seq }.toSet())
        assertEquals(listOf(5L, 4L, 3L), plan.keep.map { it.seq })
        // With the normal tiers, five is already under the twelve-newest tier.
        assertTrue(BackupRetention.plan(five, today, paused = false).delete.isEmpty())
    }

    @Test
    fun `the newest is chosen by generation number, never by date in the name`() {
        // A wrong clock stamped seq 50 as 2010; it is still the newest generation.
        val entries = (30L..49L).map { e(it, today.minusDays(60)) } + e(50, LocalDate.of(2010, 1, 1))
        val plan = BackupRetention.plan(entries, today, paused = false)
        assertTrue(plan.keep.any { it.seq == 50L })
        assertEquals(50L, plan.keep.first().seq)
    }

    @Test
    fun `paused retention deletes nothing`() {
        val entries = (1L..60L).map { e(it, today.minusDays(200)) }
        val plan = BackupRetention.plan(entries, today, paused = true)
        assertTrue(plan.delete.isEmpty())
        assertEquals(60, plan.keep.size)
    }

    @Test
    fun `stale part and bad files are scrap after ten minutes, finished generations never are`() {
        val now = 1_700_000_000_000L
        val tenMin = 10 * 60_000L
        assertTrue(BackupRetention.isStaleScrap("bnmlab-20260915-091200-00000007.bnmlab.part", now - tenMin - 1, now))
        assertTrue(BackupRetention.isStaleScrap("bnmlab-20260915-091200-00000007.bnmlab.bad", now - tenMin - 1, now))
        assertFalse(BackupRetention.isStaleScrap("bnmlab-20260915-091200-00000007.bnmlab.part", now - 60_000, now))
        assertFalse(BackupRetention.isStaleScrap("bnmlab-20260915-091200-00000007.bnmlab", now - 10 * tenMin, now))
    }
}
