package com.bnm.lab.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * One log file per day, `<prefix>-yyyy-MM-dd.log`, pruned by age and total size.
 *
 * RETENTION — 7 days, not 24 or 48 hours. Problems are not always reported the
 * day they happen: a failure on Friday evening that a lab mentions on Monday
 * morning would already be gone from a 48-hour window, and it is precisely the
 * intermittent, came-and-went failures that most need the history. The files
 * hold no patient data, so a longer window costs only disk, which [maxTotalBytes]
 * bounds.
 *
 * WRITE-THROUGH: every line is flushed as it is written. Volume is modest (events,
 * not traces), and the lines that matter most are the ones written just before a
 * crash — a buffered writer would lose exactly those.
 *
 * STORM GUARD, in two stages. Past [maxFileBytes] in a day DEBUG/INFO are dropped
 * and WARN/ERROR kept, so the errors that explain a runaway loop are still
 * recorded. Past twice that, everything is dropped and only counted: a loop that
 * throws on every iteration logs WARN/ERROR too, and a disk cap that exempts
 * them is not a cap. Worst case on disk is therefore bounded at roughly
 * [maxTotalBytes] of older days plus 2 x [maxFileBytes] for today.
 */
class RollingLogFile(
    val dir: File,
    private val prefix: String,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val retentionDays: Long = 7,
    private val maxTotalBytes: Long = 50L * 1024 * 1024,
    private val maxFileBytes: Long = 10L * 1024 * 1024,
) : AppLog.LogSink {

    private val lock = Any()
    private var openDate: LocalDate? = null
    private var writer: Writer? = null
    private var openFile: File? = null
    private var stormNoticeWritten = false
    /** Bytes in today's file, tracked in memory — not a stat() per line. */
    private var bytesToday = 0L
    private var suppressedToday = 0L
    private val hardCapBytes get() = maxFileBytes * 2

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    fun fileFor(date: LocalDate): File = File(dir, "$prefix-$date.log")

    /** Every file this writer owns — logs and crash records — oldest first. */
    fun ownedFiles(): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && (it.name.startsWith("$prefix-") || it.name.startsWith("crash-")) }
            .sortedBy { it.lastModified() }

    override fun write(level: AppLog.Level, tag: String, message: String, error: Throwable?) {
        val t = now()
        val line = buildString {
            append(stamp.format(t)).append(' ').append(level.label)
            append(" [").append(Thread.currentThread().name).append("] ")
            append(tag).append(": ").append(message)
            if (error != null) {
                // Deep cause chains (and recursive frames) can run to megabytes.
                val trace = error.stackTraceToString().trimEnd()
                append('\n').append(if (trace.length <= MAX_TRACE_CHARS) trace else trace.take(MAX_TRACE_CHARS) + "\n\t… stack trace truncated")
            }
        }
        append(level, LogRedactor.redact(line), t)
    }

    private fun append(level: AppLog.Level, redactedLine: String, t: ZonedDateTime) = synchronized(lock) {
        val w = writerFor(t.toLocalDate()) ?: return@synchronized
        if (bytesToday >= hardCapBytes) {
            suppressedToday++
            return@synchronized
        }
        if (bytesToday >= maxFileBytes && level < AppLog.Level.WARN) {
            if (!stormNoticeWritten) {
                stormNoticeWritten = true
                emit(w, "${stamp.format(t)} W [log] Log: today's file passed ${maxFileBytes / 1024 / 1024} MB — " +
                    "keeping only warnings and errors until tomorrow")
            }
            suppressedToday++
            return@synchronized
        }
        emit(w, redactedLine)
        if (bytesToday >= hardCapBytes) {
            emit(w, "${stamp.format(t)} W [log] Log: today's file passed ${hardCapBytes / 1024 / 1024} MB — " +
                "no further lines are written today (they are counted)")
        }
    }

    private fun emit(w: Writer, line: String) {
        w.write(line)
        w.write("\n")
        w.flush()
        bytesToday += line.length + 1L // chars ≈ bytes for log text; a bound, not an audit
    }

    private fun writerFor(date: LocalDate): Writer? {
        if (date == openDate && writer != null) return writer
        val carriedOver = suppressedToday
        runCatching { writer?.close() }
        dir.mkdirs()
        val f = fileFor(date)
        writer = runCatching { OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8) }.getOrNull()
        openFile = f
        openDate = date
        stormNoticeWritten = false
        bytesToday = f.length()
        suppressedToday = 0
        prune()
        if (carriedOver > 0) writer?.let {
            emit(it, "${stamp.format(now())} W [log] Log: $carriedOver lines were dropped yesterday after the size cap")
        }
        return writer
    }

    /**
     * Delete by age, then by total size (oldest first). Never deletes the file
     * currently being written — the log of the session that is running is the
     * last thing a report should lose.
     */
    fun prune() = synchronized(lock) {
        val cutoff = now().minus(Duration.ofDays(retentionDays)).toInstant().toEpochMilli()
        val current = openFile?.canonicalFile
        val files = ownedFiles().filter { it.canonicalFile != current }
        files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        val remaining = ownedFiles()
        var total = remaining.sumOf { it.length() }
        for (f in remaining) {
            if (total <= maxTotalBytes) break
            if (f.canonicalFile == current) continue
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        const val MAX_TRACE_CHARS = 16_000
    }

    fun close() = synchronized(lock) {
        runCatching { writer?.close() }
        writer = null
        openDate = null
    }
}
