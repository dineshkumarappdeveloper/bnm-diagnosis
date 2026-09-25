package com.bnm.lab.backup

import com.bnm.lab.util.formatDecimal1
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Every word the Backup pendrive feature says to staff, and the small rules
 * that pick them — one place, so the home chip, the Settings row, the banner
 * and the Backup page can never describe the same [BackupStatus] differently.
 *
 * Copy is for the person at the desk, not for us: no cipher names, no
 * "vacuum", no sequence numbers. Times read like the rest of the app
 * ([com.bnm.lab.screens.lab.shortTimeLabel] style — "09:12" today, "21 Aug
 * 09:12" otherwise). Everything takes an explicit `nowMs`, so tests pin the
 * wording without a clock.
 */
object BackupCopy {
    const val FEATURE = "Backup pendrive"

    /** Unsaved work older than this — with no pendrive taking it — earns the banner. */
    const val STALE_AFTER_MS = 4 * 60 * 60_000L

    /** A pendrive with less room than this is refused at set-up. */
    const val MIN_FREE_BYTES = 1L shl 30

    /** How many wrong licence keys the set-up wizard tolerates before it closes. */
    const val MAX_KEY_ATTEMPTS = 3

    const val NEW_SERIES_NOTE = "New orders and bills will start a new series on this computer."

    /** The restore list was opened from a folder on this PC's own disk — a copy of the stick, not the stick. */
    const val RESTORE_FROM_OWN_DISK_WARNING =
        "These backups are on this computer's own disk. After the restore, set up the backup pendrive again — a copy here is lost with the computer."

    const val ACKNOWLEDGE_CODE =
        "I have written down or printed the recovery code and kept it away from this computer"

    /** Colour family for a phase; the chip and the row map it onto theme tokens. */
    enum class Tone { NEUTRAL, GOOD, BUSY, WARN, BAD }

    fun tone(phase: BackupStatus.Phase): Tone = when (phase) {
        BackupStatus.Phase.NOT_SET_UP -> Tone.NEUTRAL
        BackupStatus.Phase.OK -> Tone.GOOD
        BackupStatus.Phase.PENDING, BackupStatus.Phase.WORKING -> Tone.BUSY
        BackupStatus.Phase.DRIVE_MISSING -> Tone.WARN
        BackupStatus.Phase.FAILING, BackupStatus.Phase.DB_PROBLEM -> Tone.BAD
    }

    /** The wide header chip and the Backup page headline. */
    fun chipLabel(status: BackupStatus, nowMs: Long): String = when (status.phase) {
        BackupStatus.Phase.NOT_SET_UP -> "Backup not set up"
        BackupStatus.Phase.OK -> buildString {
            append("Backed up ")
            append(status.lastOkAt?.let { timeLabel(it, nowMs) } ?: "—")
            status.driveName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        BackupStatus.Phase.PENDING -> "Changes waiting…"
        BackupStatus.Phase.WORKING -> "Backing up…"
        BackupStatus.Phase.DRIVE_MISSING -> buildString {
            append("Backup pendrive not connected")
            status.lastOkAt?.let { append(" · last backup ").append(timeLabel(it, nowMs)) }
        }
        BackupStatus.Phase.FAILING ->
            "Backup failing: " + (status.lastError?.takeIf { it.isNotBlank() } ?: "see Settings › Backup pendrive")
        BackupStatus.Phase.DB_PROBLEM -> "Database problem detected — contact BNM"
    }

    /** Narrow TopAppBar chip: a few words beside the staff chip. */
    fun chipLabelShort(status: BackupStatus, nowMs: Long): String = when (status.phase) {
        BackupStatus.Phase.NOT_SET_UP -> "Backup not set up"
        BackupStatus.Phase.OK -> "Backed up " + (status.lastOkAt?.let { timeLabel(it, nowMs) } ?: "")
        BackupStatus.Phase.PENDING -> "Changes waiting…"
        BackupStatus.Phase.WORKING -> "Backing up…"
        BackupStatus.Phase.DRIVE_MISSING -> "Pendrive not connected"
        BackupStatus.Phase.FAILING -> "Backup failing"
        BackupStatus.Phase.DB_PROBLEM -> "Database problem"
    }.trim()

    /** Settings › Data row subtitle: the headline, plus today's verification when there was one. */
    fun rowSubtitle(status: BackupStatus, nowMs: Long): String {
        val head = chipLabel(status, nowMs)
        val verified = status.lastVerifiedAt
            ?.takeIf { status.phase == BackupStatus.Phase.OK && sameDay(it, nowMs) }
            ?: return head
        return "$head\nVerified restorable today ${timeLabel(verified, nowMs)}"
    }

    /** "Verified restorable today 09:15" / "Verified restorable 14 Sep 09:15" / null when never. */
    fun verifiedLine(status: BackupStatus, nowMs: Long): String? =
        status.lastVerifiedAt?.let { "Verified restorable ${if (sameDay(it, nowMs)) "today " else ""}${timeLabel(it, nowMs)}" }

    /**
     * The once-per-session banner, or null when nothing needs saying. Shown
     * when unsaved work exists, nothing is taking it (pendrive absent, failing,
     * or the database itself is the problem) AND either that work or the last
     * good backup is older than [STALE_AFTER_MS]. Pending or in-progress work
     * with the pendrive present is the engine's job, not the banner's.
     */
    fun bannerText(status: BackupStatus, nowMs: Long): String? {
        if (!status.isSetUp || status.bannerDismissed) return null
        val since = status.dirtySince ?: return null
        when (status.phase) {
            BackupStatus.Phase.OK, BackupStatus.Phase.PENDING, BackupStatus.Phase.WORKING -> return null
            else -> Unit
        }
        val lastOkStale = status.lastOkAt?.let { nowMs - it >= STALE_AFTER_MS } ?: true
        val workStale = nowMs - since >= STALE_AFTER_MS
        if (!lastOkStale && !workStale) return null
        val ask = when (status.phase) {
            BackupStatus.Phase.DRIVE_MISSING -> "Plug in the backup pendrive."
            BackupStatus.Phase.DB_PROBLEM -> "Contact BNM."
            else -> "Check the backup pendrive in Settings."
        }
        return "Work since ${timeLabel(since, nowMs)} is not backed up. $ask"
    }

    /** The row-count-drop guard's notice (shown while [BackupStatus.retentionPaused]). */
    const val RETENTION_PAUSED =
        "Data looks smaller than before — older backups are being kept. If this is expected, press Back up now."

    // ── Restore ──

    fun restorePreviewText(p: RestorePreview): String =
        "Restore ${p.labName} as of ${dateTimeLabel(p.createdAt)} — " +
            "${countOf(p.patients.toLong(), "patient")}, ${countOf(p.orders.toLong(), "order")}, " +
            "${countOf(p.results.toLong(), "result")}, ${count(p.staff.toLong())} staff?"

    fun setAsideText(p: RestorePreview): String =
        "The data on this computer (" +
            (if (p.currentPatientsHere > 0) countOf(p.currentPatientsHere.toLong(), "patient") else "empty") +
            ") will be set aside."

    /** Why a previewed generation must not be restored, or null when it may. */
    fun restoreRefusal(p: RestorePreview): String? = when {
        p.newerThanThisApp -> "This backup was made by a newer BNM Lab. Update BNM Lab first."
        p.sameLicence == false -> "This backup belongs to a different lab licence."
        else -> null
    }

    fun restoreOfferText(gen: BackupGeneration): String =
        "Your backup pendrive holds records of ${gen.labName ?: "your lab"} from " +
            "${dateLabel(gen.createdAt)}. Restore them?"

    /** One generation as listed: date, time, size — and "damaged" when it cannot be opened. */
    fun generationLine(gen: BackupGeneration): String =
        "${dateTimeLabel(gen.createdAt)} · ${sizeLabel(gen.bytes)}" + if (gen.damaged) " · damaged" else ""

    // ── Set-up ──

    /** Can this volume be the backup pendrive? [note] is shown either way. */
    data class DriveVerdict(val allowed: Boolean, val note: String?)

    fun driveVerdict(c: DriveCandidate, labName: String?): DriveVerdict {
        val other = c.existingBackupOf?.takeIf { it.isNotBlank() && !it.equals(labName, ignoreCase = true) }
        return when {
            c.sameVolumeAsData -> DriveVerdict(false, "That is this computer's own disk — a backup there is lost with it")
            other != null -> DriveVerdict(false, "This pendrive already holds backups of $other. Use Restore, or choose another pendrive.")
            c.freeBytes < MIN_FREE_BYTES -> DriveVerdict(false, "Less than 1 GB free — use a bigger pendrive")
            c.existingBackupOf != null -> DriveVerdict(true, "Already holds this lab's backups — they will be kept")
            !c.removableLikely -> DriveVerdict(true, "Doesn't look like a pendrive — make sure it is one")
            else -> DriveVerdict(true, null)
        }
    }

    /** The candidate a hand-picked folder lives on (longest matching root), if any. */
    fun matchCandidate(path: String, candidates: List<DriveCandidate>): DriveCandidate? {
        val p = normalisePath(path)
        return candidates
            .filter { c -> val r = normalisePath(c.path); p == r || p.startsWith(r) }
            .maxByOrNull { normalisePath(it.path).length }
    }

    private fun normalisePath(path: String): String {
        val p = path.replace('\\', '/').trimEnd('/')
        return if (p.isEmpty()) "/" else "$p/"
    }

    /** "12 GB free of 32 GB · exFAT" for a candidate row. */
    fun driveDetail(c: DriveCandidate): String = buildString {
        append(sizeLabel(c.freeBytes)).append(" free of ").append(sizeLabel(c.totalBytes))
        c.fsType?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    }

    /** Uppercase, letters/digits/dashes only — the same shape the Activation screen accepts. */
    fun licenceKeyInput(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(32)

    /** Typed recovery code shown as XXXXX-XXXXX-XXXXX-XXXXX-XXXXX while it is entered. */
    fun recoveryCodeInput(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }.take(25).chunked(5).joinToString("-")

    fun firstBackupLine(bytes: Long, patients: Long?): String =
        "First backup done — ${sizeLabel(bytes)}" +
            (patients?.let { ", ${countOf(it, "patient")}" } ?: "") + " ✓"

    // ── Formatting ──

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "09:12" when [epochMs] falls on the same local day as [nowMs], else "21 Aug 09:12". */
    fun timeLabel(epochMs: Long, nowMs: Long): String {
        val tz = TimeZone.currentSystemDefault()
        val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
        val hm = hm(dt.hour, dt.minute)
        if (dt.date == today) return hm
        return "${dt.date.day} ${MONTHS[dt.date.month.ordinal]} $hm"
    }

    /** "15 Sep 2026 09:12". */
    fun dateTimeLabel(epochMs: Long): String {
        val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${dt.date.day} ${MONTHS[dt.date.month.ordinal]} ${dt.date.year} ${hm(dt.hour, dt.minute)}"
    }

    /** "15 Sep 2026". */
    fun dateLabel(epochMs: Long): String {
        val d = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault()).date
        return "${d.day} ${MONTHS[d.month.ordinal]} ${d.year}"
    }

    fun sameDay(aMs: Long, bMs: Long): Boolean {
        val tz = TimeZone.currentSystemDefault()
        return Instant.fromEpochMilliseconds(aMs).toLocalDateTime(tz).date ==
            Instant.fromEpochMilliseconds(bMs).toLocalDateTime(tz).date
    }

    private fun hm(h: Int, m: Int) = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"

    /** "12 MB" — one decimal only while the number is small ("1.5 MB", "2.5 GB"). */
    fun sizeLabel(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        val gb = mb / 1024
        return when {
            mb < 1 -> "less than 1 MB"
            mb < 10 -> "${formatDecimal1(mb)} MB"
            mb < 1024 -> "${mb.roundToLong()} MB"
            gb < 10 -> "${formatDecimal1(gb)} GB"
            else -> "${gb.roundToLong()} GB"
        }
    }

    /** "1,204" — thousands grouped, KMP-safe. */
    fun count(n: Long): String {
        val digits = n.toString().trimStart('-')
        val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
        return if (n < 0) "-$grouped" else grouped
    }

    /** "1 patient" / "1,204 patients". */
    fun countOf(n: Long, noun: String): String = "${count(n)} $noun${if (n == 1L) "" else "s"}"
}
