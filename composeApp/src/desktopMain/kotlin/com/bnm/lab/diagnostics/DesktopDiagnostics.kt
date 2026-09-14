package com.bnm.lab.diagnostics

import com.bnm.lab.BuildInfo
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.time.ZonedDateTime

/**
 * Desktop wiring for the activity log: file sink, crash capture, unclean-exit
 * detection and stderr capture. [install] is the first thing `main()` does, so
 * a failure anywhere after it — including in the first frame of the UI — lands
 * in the log.
 */
object DesktopDiagnostics {

    const val APP_DIR = "BNMLab"
    private const val PREFIX = "bnmlab"
    private const val RUNNING_MARKER = "session.running"
    /**
     * Deliberately NOT prefixed "crash-": files with that prefix age out with
     * the logs, and a crash the lab has not yet been shown must not be pruned
     * before the next launch offers it (a lab closed for a week-long holiday
     * after a crash would otherwise never be asked).
     */
    const val PENDING_CRASH = "pending-crash.txt"

    /** Platform-standard log location — where each OS's own tools look. */
    val logDir: File by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = System.getProperty("user.home").orEmpty()
        // An env var that is set but EMPTY or RELATIVE (e.g. a literal "$HOME/..."
        // from /etc/environment, which does not expand) must fall back, not
        // resolve to "/BNMLab/logs" or the working directory.
        fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() && File(it).isAbsolute }
        when {
            os.contains("mac") -> File("$home/Library/Logs", APP_DIR)
            os.contains("win") -> File(env("LOCALAPPDATA") ?: "$home\\AppData\\Local", "$APP_DIR\\Logs")
            else -> File(env("XDG_STATE_HOME") ?: "$home/.local/state", "$APP_DIR/logs")
        }.apply { mkdirs() }
    }

    lateinit var files: RollingLogFile
        private set

    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        // Crash capture FIRST, so even a failure in the rest of this setup is recorded.
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            recordCrash(thread, error, fatal = isFatal(thread.name, error))
        }
        // Diagnostics must never be the reason BNM Lab will not open. If anything
        // below fails — an unwritable folder, a missing runtime module — log to the
        // console and let the app start regardless.
        try {
            LogRedactor.setHomeDir(System.getProperty("user.home"))
            if (!logDir.isDirectory || !logDir.canWrite()) {
                System.err.println("[diagnostics] log folder is not writable: ${logDir.absolutePath}")
            }
            files = RollingLogFile(logDir, PREFIX)
            AppLog.install(files)

            val marker = File(logDir, RUNNING_MARKER)
            if (marker.exists()) {
                // Not a crash we caught (that writes its own record) — a force quit,
                // a kill from Task Manager, or power loss. Worth knowing when a lab
                // says "the app keeps closing", not worth a dialog.
                AppLog.w("Lifecycle", "previous session did not shut down cleanly (${marker.readText().trim()})")
            }
            runCatching { marker.writeText("pid ${ProcessHandle.current().pid()} started ${ZonedDateTime.now()}") }

            AppLog.i("Lifecycle", "BNM Lab ${BuildInfo.VERSION} starting — ${environmentLine()}")
            captureStderr()

            Runtime.getRuntime().addShutdownHook(Thread({
                AppLog.i("Lifecycle", "BNM Lab exiting normally")
                runCatching { marker.delete() }
                files.close()
            }, "log-shutdown"))
        } catch (t: Throwable) {
            System.err.println("[diagnostics] activity log unavailable, continuing without it: $t")
        }
    }

    /**
     * Does this uncaught exception actually take BNM Lab down? The UI thread
     * (AWT event queue, where Compose runs) and `main` do; so does the JVM running
     * out of memory. An exception escaping a background coroutine or worker thread
     * does not — an analyzer resetting its TCP connection mid-send is routine —
     * and prompting "BNM Lab closed unexpectedly" on the next launch for one of
     * those would teach a lab to ignore the prompt that matters.
     */
    fun isFatal(threadName: String, error: Throwable): Boolean =
        threadName == "main" || threadName.startsWith("AWT-EventQueue") || error is VirtualMachineError

    /**
     * Record an uncaught exception. Always logged; a FATAL one also gets a pending
     * record, written synchronously, so the next launch offers to send it even if
     * nobody opens the menu.
     */
    fun recordCrash(thread: Thread, error: Throwable, fatal: Boolean) {
        if (!fatal) {
            AppLog.e("Uncaught", "background failure on thread '${thread.name}' (app kept running)", error)
            return
        }
        AppLog.e("CRASH", "uncaught exception on thread '${thread.name}'", error)
        runCatching {
            File(logDir, PENDING_CRASH).writeText(
                LogRedactor.redact(
                    "BNM Lab ${BuildInfo.VERSION} crashed at ${ZonedDateTime.now()}\n" +
                        "thread: ${thread.name}\n" +
                        error.stackTraceToString().take(RollingLogFile.MAX_TRACE_CHARS),
                ),
            )
        }
    }

    fun pendingCrashFile(): File? = File(logDir, PENDING_CRASH).takeIf { it.exists() }

    /** Keep the record for reports, but stop prompting about it. */
    fun acknowledgeCrash() {
        val f = pendingCrashFile() ?: return
        val stamp = ZonedDateTime.now().toLocalDateTime().toString().replace(':', '-')
        f.renameTo(File(logDir, "crash-$stamp.txt"))
    }

    /**
     * java.base APIs ONLY. The packaged app ships a jlink-trimmed runtime with just
     * the modules listed in build.gradle.kts — `java.lang.management` (process
     * uptime) is not among them, and using it here crashed BNM Lab on launch while
     * every unit test, running on a full JDK, passed.
     */
    fun environmentLine(): String {
        val rt = Runtime.getRuntime()
        return "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            "(${System.getProperty("os.arch")}) · Java ${System.getProperty("java.version")} · " +
            "heap max ${rt.maxMemory() / 1024 / 1024} MB · ${rt.availableProcessors()} CPUs · " +
            "process started ${ProcessHandle.current().info().startInstant().map { it.toString() }.orElse("?")}"
    }

    /**
     * Libraries report their own trouble on stderr (PDFBox font substitution,
     * the SQLite driver, the JDK itself). Mirror it into the log — still printed
     * to the console for `gradlew run` — one log entry per line.
     */
    private fun captureStderr() {
        val original = System.err
        // Bytes, not chars: stderr is UTF-8, and decoding byte-by-byte would
        // garble every non-ASCII line (₹, Tamil and Hindi test names, …).
        val lineBuffer = java.io.ByteArrayOutputStream()
        val tee = object : OutputStream() {
            override fun write(b: Int) {
                original.write(b)
                synchronized(lineBuffer) {
                    if (b == '\n'.code) {
                        val line = lineBuffer.toString(Charsets.UTF_8).trimEnd('\r')
                        lineBuffer.reset()
                        if (line.isNotBlank()) AppLog.w("stderr", line)
                    } else if (lineBuffer.size() < 8_000) {
                        lineBuffer.write(b)
                    }
                }
            }
            override fun flush() = original.flush()
        }
        System.setErr(PrintStream(tee, true, Charsets.UTF_8))
    }
}
