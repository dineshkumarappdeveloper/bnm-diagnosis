package com.bnm.lab.backup

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Generation file names and the numbering they carry.
 *
 * `snapshots/<yyyy-MM>/bnmlab-<yyyyMMdd-HHmmss>-<seq 8 digits>.bnmlab` — the
 * generation number is the ONLY order that matters: the newest generation is
 * always the highest `seq`, never the newest mtime or the latest date in the
 * name (a lab PC with a dead CMOS battery boots in 2010).
 */
internal object BackupNaming {
    const val EXT = ".bnmlab"
    const val VAULT_DIR = "BNM Lab Backup"
    const val SNAPSHOTS_DIR = "snapshots"
    const val MARKER = "backup-id.json"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")
    private val NAME = Regex("""bnmlab-(\d{8})-(\d{6})-(\d{8})\.bnmlab""")

    fun fileName(created: ZonedDateTime, seq: Long): String =
        "bnmlab-${created.format(STAMP)}-${seq.toString().padStart(8, '0')}$EXT"

    fun monthDir(created: ZonedDateTime): String = created.format(MONTH)

    /** seq and the date in the name, or null when the name is not a generation. */
    fun parse(name: String): Parsed? {
        val m = NAME.matchEntire(name) ?: return null
        val date = try {
            LocalDate.parse(m.groupValues[1], DateTimeFormatter.BASIC_ISO_DATE)
        } catch (e: DateTimeParseException) {
            return null
        }
        return Parsed(seq = m.groupValues[3].toLong(), date = date)
    }

    /** The next generation number: never behind what the drive already holds, never behind what this PC issued. */
    fun nextSeq(prefsSeq: Long, driveMaxSeq: Long): Long = maxOf(prefsSeq, driveMaxSeq) + 1

    data class Parsed(val seq: Long, val date: LocalDate)
}

/**
 * Which generations to keep — decided from file names alone.
 *
 * Keep the [BackupPolicy.KEEP_NEWEST] highest generation numbers, plus the
 * highest of each of the last 30 calendar days, plus the highest of each of the
 * last 12 calendar months; never prune below [BackupPolicy.KEEP_FLOOR] files;
 * delete nothing at all while retention is paused (the row-count-drop guard has
 * tripped and an owner has not yet said the drop was expected).
 *
 * The date tiers are a hint layered on the seq tier: after a wrong clock is
 * corrected a whole date tier may fall out of its window at once, and the
 * newest-by-seq tier and the floor are what still stand.
 */
internal object BackupRetention {
    data class Entry(val name: String, val seq: Long, val date: LocalDate)

    data class Plan(val keep: List<Entry>, val delete: List<Entry>)

    fun plan(
        entries: List<Entry>,
        today: LocalDate,
        paused: Boolean,
        keepNewest: Int = BackupPolicy.KEEP_NEWEST,
        dailyDays: Int = BackupPolicy.KEEP_DAILY_DAYS,
        monthlyMonths: Int = BackupPolicy.KEEP_MONTHLY_MONTHS,
        floor: Int = BackupPolicy.KEEP_FLOOR,
    ): Plan {
        val bySeqDesc = entries.sortedByDescending { it.seq }
        if (paused || bySeqDesc.size <= floor) return Plan(keep = bySeqDesc, delete = emptyList())

        val keep = LinkedHashSet<Entry>()
        keep += bySeqDesc.take(keepNewest)

        val oldestDay = today.minusDays((dailyDays - 1).toLong())
        bySeqDesc.filter { !it.date.isBefore(oldestDay) && !it.date.isAfter(today) }
            .groupBy { it.date }
            .values.forEach { day -> keep += day.maxBy { it.seq } }

        val thisMonth = YearMonth.from(today)
        val oldestMonth = thisMonth.minusMonths((monthlyMonths - 1).toLong())
        bySeqDesc.filter { YearMonth.from(it.date).let { m -> !m.isBefore(oldestMonth) && !m.isAfter(thisMonth) } }
            .groupBy { YearMonth.from(it.date) }
            .values.forEach { month -> keep += month.maxBy { it.seq } }

        // The floor: whatever the tiers say, a lab keeps at least a few generations.
        for (e in bySeqDesc) {
            if (keep.size >= floor) break
            keep += e
        }
        val delete = bySeqDesc.filter { it !in keep }
        return Plan(keep = bySeqDesc.filter { it in keep }, delete = delete)
    }

    /** A leftover `.part` / `.bad` from a dead process is stale once it stops changing for this long. */
    fun isStaleScrap(name: String, lastModifiedMillis: Long, nowMillis: Long): Boolean =
        (name.endsWith(".part") || name.endsWith(".bad")) &&
            nowMillis - lastModifiedMillis > BackupPolicy.STALE_PART_MS

    fun toEntry(name: String): Entry? = BackupNaming.parse(name)?.let { Entry(name, it.seq, it.date) }

    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)
}
