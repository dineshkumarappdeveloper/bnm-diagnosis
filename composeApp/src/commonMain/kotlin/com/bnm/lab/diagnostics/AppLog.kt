package com.bnm.lab.diagnostics

/**
 * The app's activity log — what a lab emails to BNM support when something goes
 * wrong, so a problem can be diagnosed from here and fixed in the next release.
 *
 * WHAT GOES IN: lifecycle, screen changes, sync sweeps, licence outcomes, network
 * calls (method, path, status, duration), analyzer connections, report printing,
 * and every error with its stack trace. Enough to reconstruct what the operator
 * was doing and what failed.
 *
 * WHAT NEVER GOES IN: patient names, phone numbers, addresses, results, report
 * content, request/response bodies, tokens, licence keys. These files leave the
 * building by email. Call sites log IDs and counts, never records — and every
 * line is additionally run through [LogRedactor] before it touches disk, as a
 * backstop for the call site that forgets.
 *
 * Platform-neutral on purpose: common code (sync engine, repositories, screens)
 * logs through this object, and each platform decides where lines go by
 * installing a [LogSink]. Until one is installed, lines go to stdout, which is
 * what `./gradlew run` and the tests want.
 */
object AppLog {

    enum class Level(val label: String) { DEBUG("D"), INFO("I"), WARN("W"), ERROR("E") }

    /** Where formatted events go. Desktop installs a rolling file sink. */
    fun interface LogSink {
        fun write(level: Level, tag: String, message: String, error: Throwable?)
    }

    @kotlin.concurrent.Volatile
    private var sink: LogSink = LogSink { level, tag, message, error ->
        println("${level.label}/$tag: ${LogRedactor.redact(message)}")
        error?.let { println(LogRedactor.redact(it.stackTraceToString())) }
    }

    fun install(newSink: LogSink) { sink = newSink }

    fun d(tag: String, message: String) = emit(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = emit(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = emit(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = emit(Level.ERROR, tag, message, error)

    private fun emit(level: Level, tag: String, message: String, error: Throwable?) {
        // Logging must never be the thing that breaks the app.
        runCatching { sink.write(level, tag, message, error) }
    }
}

/**
 * `runCatching { … }.logFailure("Sync", "report upload")` — keeps a swallowed
 * failure swallowed, but on the record. Field problems are overwhelmingly the
 * ones a `runCatching` quietly ate; this is how they become visible.
 */
fun <T> Result<T>.logFailure(tag: String, what: String): Result<T> =
    onFailure { AppLog.w(tag, "$what failed: ${it::class.simpleName}: ${it.message}", it) }
