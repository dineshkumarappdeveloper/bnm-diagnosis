package com.bnm.lab

import com.bnm.lab.api.ApiClient
import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.diagnostics.DesktopDiagnostics
import com.bnm.lab.diagnostics.DiagnosticsContext
import com.bnm.lab.diagnostics.LogRedactor
import com.bnm.lab.diagnostics.RollingLogFile
import com.bnm.lab.diagnostics.SupportMail
import com.bnm.lab.diagnostics.SupportUi
import com.bnm.lab.diagnostics.SupportZip
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The support-report pipeline. These files leave the lab by email, so most of
 * what is under test here is what must NOT be in them.
 */
class DiagnosticsLogTest {

    @AfterTest fun resetGlobals() { LogRedactor.setHomeDir(null); SupportUi.close() }

    private fun tempDir(): File = Files.createTempDirectory("bnmlab-logs").toFile()
    private val ist = ZoneId.of("Asia/Kolkata")

    // ── redaction: what must go ─────────────────────────────────────────────

    @Test
    fun `bearer tokens and JWTs are removed`() {
        val jwt = "eyJhbGciOiJFUzI1NiJ9.eyJsaWQiOiIxMjM0NTYifQ.c2lnbmF0dXJlLWJ5dGVz"
        val out = LogRedactor.redact("Authorization: Bearer $jwt and later $jwt")
        assertFalse(out.contains("eyJhbGci"), out)
        assertTrue(out.contains("[redacted]") || out.contains("[jwt]"), out)
    }

    @Test
    fun `licence keys are removed`() {
        val out = LogRedactor.redact("activating BNMD-M96V-P836-FGPB-QVUH now")
        assertFalse(out.contains("M96V"), out)
        assertTrue(out.contains("BNMD-[key]"), out)
    }

    @Test
    fun `credential-shaped key-value pairs are removed`() {
        val out = LogRedactor.redact("""{"device_token": "abc123secretXYZ", "password":"hunter2", pin=4821}""")
        assertFalse(out.contains("abc123secretXYZ"), out)
        assertFalse(out.contains("hunter2"), out)
        assertFalse(out.contains("4821"), out)
    }

    @Test
    fun `emails and Indian phone numbers are removed in every common form`() {
        val forms = listOf(
            "9876543210", "+91 9876543210", "+919876543210", "09876543210", "91-9876543210",
            // How people actually write them — the 5+5 and 4+3+3 groupings.
            "98765 43210", "+91 98765 43210", "+91-98765-43210", "098765 43210",
            "9876 543 210", "+91 9876 543 210", "%2B919876543210",
        )
        for (phone in forms) {
            val out = LogRedactor.redact("patient phone $phone registered")
            assertFalse(out.contains("98765") || out.contains("43210") || out.contains("9876 543"), "leaked $phone -> $out")
            assertTrue(out.contains("[phone]"), "not recognised: $phone -> $out")
        }
        assertFalse(LogRedactor.redact("sent to ravi.k@example.com").contains("ravi.k@"))
    }

    @Test
    fun `the OS account name in paths becomes a tilde`() {
        LogRedactor.setHomeDir("/Users/dineshkumarr")
        assertEquals(
            "opening ~/Library/Application Support/BNMLab/bnm_chat.db",
            LogRedactor.redact("opening /Users/dineshkumarr/Library/Application Support/BNMLab/bnm_chat.db"),
        )
    }

    // ── redaction: what must stay (a log that redacts everything is useless) ─

    @Test
    fun `numbers that make a log useful survive redaction`() {
        val line = "GET voelldnyfamrbzvthgfk.supabase.co/functions/v1/admin-lab/sync/pull -> 200 in 3120 ms " +
            "epoch 1789165147 millis 1789165147039 ACC-S1-00021 order 4f1c2a9e-7b3d-4e21-9a55-1c2d3e4f5a6b " +
            "22 params · specimen 2409130012"
        assertEquals(line, LogRedactor.redact(line))
    }

    // ── rolling files ───────────────────────────────────────────────────────

    @Test
    fun `lines are formatted, redacted and written through immediately`() {
        val dir = tempDir()
        val now = ZonedDateTime.of(2026, 9, 13, 10, 15, 30, 0, ist)
        val log = RollingLogFile(dir, "bnmlab", now = { now })
        log.write(AppLog.Level.INFO, "Licence", "activating BNMD-M96V-P836-FGPB-QVUH", null)
        // No close/flush call — a crash straight after must not lose the line.
        val text = File(dir, "bnmlab-2026-09-13.log").readText()
        assertTrue(text.startsWith("2026-09-13T10:15:30.000+05:30 I ["), text)
        assertTrue(text.contains("Licence: activating BNMD-[key]"), text)
        assertFalse(text.contains("M96V"), text)
    }

    @Test
    fun `errors carry their stack trace`() {
        val dir = tempDir()
        val log = RollingLogFile(dir, "bnmlab", now = { ZonedDateTime.of(2026, 9, 13, 9, 0, 0, 0, ist) })
        log.write(AppLog.Level.ERROR, "Sync", "sweep failed", IllegalStateException("boom"))
        val text = File(dir, "bnmlab-2026-09-13.log").readText()
        assertTrue(text.contains("java.lang.IllegalStateException: boom"), text)
        assertTrue(text.contains("\tat "), text)
    }

    @Test
    fun `a new day opens a new file`() {
        val dir = tempDir()
        var now = ZonedDateTime.of(2026, 9, 13, 23, 59, 0, 0, ist)
        val log = RollingLogFile(dir, "bnmlab", now = { now })
        log.write(AppLog.Level.INFO, "T", "before midnight", null)
        now = now.plusMinutes(2)
        log.write(AppLog.Level.INFO, "T", "after midnight", null)
        assertTrue(File(dir, "bnmlab-2026-09-13.log").readText().contains("before midnight"))
        assertTrue(File(dir, "bnmlab-2026-09-14.log").readText().contains("after midnight"))
    }

    @Test
    fun `files older than the retention window are deleted, recent ones kept`() {
        val dir = tempDir()
        val now = ZonedDateTime.of(2026, 9, 20, 12, 0, 0, 0, ist)
        fun aged(name: String, daysOld: Long) = File(dir, name).apply {
            writeText("x\n"); setLastModified(now.minusDays(daysOld).toInstant().toEpochMilli())
        }
        val old = aged("bnmlab-2026-09-10.log", 10)
        val oldCrash = aged("crash-2026-09-09T10-00.txt", 11)
        val recent = aged("bnmlab-2026-09-16.log", 4)
        val foreign = aged("someone-elses.txt", 30) // not ours — never touched
        val log = RollingLogFile(dir, "bnmlab", now = { now }, retentionDays = 7)
        log.write(AppLog.Level.INFO, "T", "today", null) // opening today's file prunes
        assertFalse(old.exists()); assertFalse(oldCrash.exists())
        assertTrue(recent.exists()); assertTrue(foreign.exists())
    }

    @Test
    fun `total size cap deletes oldest first and never the file being written`() {
        val dir = tempDir()
        val now = ZonedDateTime.of(2026, 9, 20, 12, 0, 0, 0, ist)
        val oldest = File(dir, "bnmlab-2026-09-17.log").apply { writeText("a".repeat(600)); setLastModified(now.minusDays(3).toInstant().toEpochMilli()) }
        val newer = File(dir, "bnmlab-2026-09-19.log").apply { writeText("b".repeat(600)); setLastModified(now.minusDays(1).toInstant().toEpochMilli()) }
        val log = RollingLogFile(dir, "bnmlab", now = { now }, maxTotalBytes = 1_000)
        log.write(AppLog.Level.INFO, "T", "today", null)
        assertFalse(oldest.exists(), "oldest should go first")
        assertTrue(newer.exists())
        assertTrue(File(dir, "bnmlab-2026-09-20.log").exists())
    }

    @Test
    fun `past the daily cap only warnings and errors are kept`() {
        val dir = tempDir()
        val log = RollingLogFile(dir, "bnmlab", now = { ZonedDateTime.of(2026, 9, 13, 9, 0, 0, 0, ist) }, maxFileBytes = 200)
        repeat(20) { log.write(AppLog.Level.INFO, "Chatty", "noise line $it padding padding", null) }
        log.write(AppLog.Level.ERROR, "Sync", "the error that explains it", null)
        val text = File(dir, "bnmlab-2026-09-13.log").readText()
        assertTrue(text.contains("the error that explains it"), text)
        assertFalse(text.contains("noise line 19"), text)
        assertEquals(1, Regex("keeping only warnings and errors").findAll(text).count())
    }

    // ── the report ──────────────────────────────────────────────────────────

    @Test
    fun `report zip holds summary and logs, re-redacted, and nothing else`() {
        val logs = tempDir()
        val out = tempDir()
        // A file written by an OLDER build, before redaction existed.
        val legacy = File(logs, "bnmlab-2026-09-12.log").apply {
            writeText("device_token=abc123secretXYZ phone 9876543210\nordinary line\n")
        }
        val zip = SupportZip.write("summary body", listOf(legacy), out,
            ZonedDateTime.of(2026, 9, 13, 10, 20, 0, 0, ist))
        assertEquals("BNMLab-support-20260913-102000.zip", zip.name)
        ZipFile(zip).use { z ->
            assertEquals(setOf("summary.txt", "logs/bnmlab-2026-09-12.log"), z.entries().toList().map { it.name }.toSet())
            val shipped = z.getInputStream(z.getEntry("logs/bnmlab-2026-09-12.log")).readBytes().toString(Charsets.UTF_8)
            assertFalse(shipped.contains("abc123secretXYZ"), shipped)
            assertFalse(shipped.contains("9876543210"), shipped)
            assertTrue(shipped.contains("ordinary line"))
        }
    }

    @Test
    fun `report sections render, and a failing section does not sink the report`() = runBlocking {
        DiagnosticsContext.register("TestOk") { "patients=2 orders=21" }
        DiagnosticsContext.register("TestBroken") { error("db locked") }
        DiagnosticsContext.register("TestLeaky") { "token=abc123secretXYZ" }
        val text = DiagnosticsContext.render()
        assertTrue(text.contains("== TestOk ==\npatients=2 orders=21"), text)
        assertTrue(text.contains("(unavailable: IllegalStateException: db locked)"), text)
        assertFalse(text.contains("abc123secretXYZ"), text)
    }

    @Test
    fun `mailto uses percent-encoding a mail client will actually decode`() {
        val uri = SupportMail.mailtoUri("support@bnmapp.com", "BNM Lab report — SRT", "line one\nA & B? 50%")
        assertTrue(uri.startsWith("mailto:support@bnmapp.com?subject="), uri)
        assertFalse(uri.contains("+"), "URLEncoder's '+' would show up literally: $uri")
        assertTrue(uri.contains("%20") && uri.contains("%0A") && uri.contains("%26") && uri.contains("%3F"), uri)
    }

    @Test
    fun `email body is redacted — secrets placed where truncation cannot hide them`() {
        val body = SupportMail.body("1.1.0", "SRT Diagnostics · offline edition", "BNMLab-support-x.zip",
            description = "call me on 98765 43210 or ravi@example.com, token=abc123secretXYZ",
            environment = "Mac OS X 15")
        assertFalse(body.contains("43210")); assertFalse(body.contains("ravi@example.com"))
        assertFalse(body.contains("abc123secretXYZ"))
        assertTrue(body.contains("[phone]") && body.contains("BNMLab-support-x.zip"))
    }

    @Test
    fun `mailto stays under the Windows limit even for a long Hindi or Tamil description`() {
        for (script in listOf("रिपोर्ट प्रिंट नहीं हुई ", "அறிக்கை அச்சிடப்படவில்லை ")) {
            val uri = SupportMail.fittedMailtoUri(
                to = "support@bnmapp.com", subject = "BNM Lab support report — SRT Diagnostics",
                appVersion = "1.1.0", headline = "SRT Diagnostics · offline edition",
                fileName = "BNMLab-support-20260913-102000.zip",
                description = script.repeat(80), environment = "Windows 11 10.0 (amd64) · Java 21",
            )
            assertTrue(uri.length <= SupportMail.MAX_URI_CHARS, "URI is ${uri.length} chars")
            // The description is what gives way — never the attachment instruction.
            assertTrue(uri.contains("BNMLab-support-20260913-102000.zip"), "attachment line was cut")
        }
    }

    // ── review regressions ──────────────────────────────────────────────────

    @Test
    fun `a report-share capability token never reaches the log — path or timeout message`() {
        val token = "3f9c1e0b" + "a".repeat(40) + "0123456789abcdef"
        assertEquals(64, token.length)
        val path = ApiClient.loggablePath(listOf("functions", "v1", "admin-lab", "reports", token, "whatsapp"))
        assertEquals("functions/v1/admin-lab/reports/{token}/whatsapp", path)
        // Backstop for text the plugin does not build, e.g. Ktor's timeout message.
        val timeout = LogRedactor.redact(
            "Request timeout has expired [url=https://x.supabase.co/functions/v1/admin-lab/reports/$token/whatsapp, request_timeout=30000 ms]")
        assertFalse(timeout.contains(token.take(16)), timeout)
    }

    @Test
    fun `log paths keep record ids and route names, mask opaque tokens`() {
        val id = "4f1c2a9e-7b3d-4e21-9a55-1c2d3e4f5a6b"
        assertEquals("functions/v1/admin-lab/emr-orders/$id/status",
            ApiClient.loggablePath(listOf("functions", "v1", "admin-lab", "emr-orders", id, "status")))
        assertEquals("functions/v1/clinical-recording-process",
            ApiClient.loggablePath(listOf("functions", "v1", "clinical-recording-process")))
        assertEquals("magic/{token}", ApiClient.loggablePath(listOf("magic", "Xk9pQ2vR7sT1uW3yZ5aB8cD")))
    }

    @Test
    fun `a synced record quoted by a JSON decode error never reaches the log`() {
        val msg = "Unexpected JSON token at offset 57: Failed to parse literal 'ten' as an int value at element: $.commissionPct\n" +
            "Use 'coerceInputValues = true' in 'Json {}' builder to coerce nulls if property has a default value.\n" +
            "JSON input: {\"id\":\"r1\",\"kind\":\"doctor\",\"name\":\"Dr. Anand Rao\",\"commissionPct\":\"ten\"}"
        val out = LogRedactor.redact(msg)
        assertFalse(out.contains("Anand Rao"), out)
        assertFalse(out.contains("'ten'"), out)
        assertTrue(out.contains("at element: $.commissionPct"), "the useful part — which field — must survive: $out")
    }

    @Test
    fun `the account name is removed from paths in every form`() {
        LogRedactor.setHomeDir("C:\\Users\\Dinesh Kumar")
        for (line in listOf(
            "FileNotFoundException: C:\\Users\\DINESH~1\\AppData\\Local\\Temp\\bnm\\ACC-1-report.pdf",
            "file:/C:/Users/DineshKumar/AppData/Local/Temp/x.pdf",
            "/Users/dineshkumarr/Library/Logs/BNMLab",
            "/home/labtech/.local/state/BNMLab/logs",
        )) {
            val out = LogRedactor.redact(line)
            assertFalse(Regex("(?i)dinesh|labtech").containsMatchIn(out), out)
        }
    }

    @Test
    fun `a warning storm is bounded too — past the hard cap nothing more is written`() {
        val dir = tempDir()
        var now = ZonedDateTime.of(2026, 9, 13, 9, 0, 0, 0, ist)
        val log = RollingLogFile(dir, "bnmlab", now = { now }, maxFileBytes = 1_000)
        repeat(500) { log.write(AppLog.Level.ERROR, "Loop", "failing again and again, iteration $it", null) }
        val today = File(dir, "bnmlab-2026-09-13.log")
        assertTrue(today.length() < 2_000 + 400, "file grew to ${today.length()} bytes")
        assertTrue(today.readText().contains("no further lines are written today"))
        now = now.plusDays(1)
        log.write(AppLog.Level.INFO, "T", "new day", null)
        assertTrue(File(dir, "bnmlab-2026-09-14.log").readText().contains("lines were dropped yesterday"))
    }

    @Test
    fun `a huge stack trace is truncated, not written whole`() {
        val dir = tempDir()
        val log = RollingLogFile(dir, "bnmlab", now = { ZonedDateTime.of(2026, 9, 13, 9, 0, 0, 0, ist) })
        var e: Throwable = RuntimeException("root")
        repeat(400) { e = RuntimeException("layer $it " + "x".repeat(200), e) }
        log.write(AppLog.Level.ERROR, "Deep", "boom", e)
        val text = File(dir, "bnmlab-2026-09-13.log").readText()
        assertTrue(text.length < RollingLogFile.MAX_TRACE_CHARS + 500, "${text.length} chars")
        assertTrue(text.contains("stack trace truncated"))
    }

    @Test
    fun `only failures that take the app down count as crashes`() {
        assertTrue(DesktopDiagnostics.isFatal("AWT-EventQueue-0", IllegalStateException()))
        assertTrue(DesktopDiagnostics.isFatal("main", RuntimeException()))
        assertTrue(DesktopDiagnostics.isFatal("DefaultDispatcher-worker-3", OutOfMemoryError()))
        // An analyzer resetting its TCP connection is routine, not "closed unexpectedly".
        assertFalse(DesktopDiagnostics.isFatal("DefaultDispatcher-worker-3", java.io.IOException("Connection reset")))
        assertFalse(DesktopDiagnostics.isFatal("ktor-io-2", RuntimeException()))
    }

    @Test
    fun `the pending crash record is never pruned by retention`() {
        val dir = tempDir()
        val now = ZonedDateTime.of(2026, 9, 30, 9, 0, 0, 0, ist)
        val pending = File(dir, DesktopDiagnostics.PENDING_CRASH).apply {
            writeText("crashed"); setLastModified(now.minusDays(20).toInstant().toEpochMilli())
        }
        RollingLogFile(dir, "bnmlab", now = { now }, retentionDays = 7).write(AppLog.Level.INFO, "T", "launch", null)
        assertTrue(pending.exists(), "a crash the lab was never shown must survive a long closure")
    }

    @Test
    fun `two reports in the same second get different files, never an overwrite`() {
        val logs = tempDir(); val out = tempDir()
        val f = File(logs, "bnmlab-2026-09-13.log").apply { writeText("line\n") }
        val t = ZonedDateTime.of(2026, 9, 13, 10, 20, 5, 0, ist)
        val first = SupportZip.write("first", listOf(f), out, t)
        val second = SupportZip.write("second", listOf(f), out, t)
        assertEquals("BNMLab-support-20260913-102005.zip", first.name)
        assertEquals("BNMLab-support-20260913-102005-2.zip", second.name)
        ZipFile(first).use { z -> assertEquals("first", z.getInputStream(z.getEntry("summary.txt")).readBytes().toString(Charsets.UTF_8)) }
        assertTrue(out.listFiles()!!.none { it.name.endsWith(".part") }, "temp file left behind")
    }

    @Test
    fun `opening the report dialog from Help does not replace a showing crash prompt`() {
        SupportUi.open(crashNotice = "crashed at 09:58")
        SupportUi.open()
        assertEquals("crashed at 09:58", SupportUi.request.value?.crashNotice)
    }
}
