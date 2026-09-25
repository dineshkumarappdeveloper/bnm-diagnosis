package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.api.LabApi
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.db.addColumn
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.RefRange
import com.bnm.lab.lab.TestParameter
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.report.ReportAssembler
import com.bnm.lab.report.ReportShare
import com.bnm.lab.report.ReportSnapshot
import com.bnm.lab.report.ReportUploader
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffRepository
import com.bnm.lab.staff.StaffRole
import com.bnm.lab.sync.SyncPrefs
import com.russhwolf.settings.PropertiesSettings
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The drain that makes a printed QR resolve: it publishes the report as data,
 * skips a report the server already has, backs a failing row off instead of
 * letting it block the queue, republishes PDF-era uploads once, and never
 * brings a revoked link back.
 */
class ReportUploaderTest {

    private class Lab {
        val db: AppDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { AppDatabase.Schema.create(it); AppDatabase(it) }
        val repo = LabRepository(db, ApiClient.json)
        val staff = StaffRepository(db, ApiClient.json)
        val license = LicenseManager(PropertiesSettings(Properties()), { true }, { 1_800_000_000L })
        val prefs = SyncPrefs(PropertiesSettings(Properties()))
        var now: Instant = Instant.parse("2026-09-15T08:00:00Z")
        val published = ArrayList<Pair<String, JsonObject>>()
        /** Tokens the fake server refuses. */
        val failing = HashSet<String>()

        val uploader = ReportUploader(
            repo, LabApi(HttpClient()) { null }, ReportAssembler(repo, staff, license = license), license, prefs,
            publish = { row, snapshot ->
                if (row.token in failing) Result.failure(IllegalStateException("HTTP 500"))
                else { published += row.token to snapshot; Result.success(Unit) }
            },
            clock = { now },
        )

        /** An approved one-test order with its share row minted (state pending). */
        fun approvedOrder(name: String): Pair<String, String> = runBlocking {
            if (repo.testById("t-glu") == null) {
                repo.upsertTest(LabTest(id = "t-glu", code = "GLU", name = "Glucose", price = 100.0,
                    parameters = listOf(TestParameter(key = "glu", name = "Glucose", unit = "mg/dL", decimals = 0,
                        ranges = listOf(RefRange(low = 70.0, high = 100.0))))))
                staff.upsert(Staff(id = "s-path", name = "Dr. Meena Iyer", role = StaffRole.PATHOLOGIST))
            }
            val patient = repo.upsertPatient(Patient(id = "p-$name", name = name, sex = "F", ageYears = 30))
            val order = repo.createLabOrder(patient.id, testIds = listOf("t-glu")).getOrThrow()
            repo.enterResult(order.id, "t-glu", "glu", "90").getOrThrow()
            repo.verifyOrder(order.id, "Tech").getOrThrow()
            repo.approveOrder(order.id, "Dr. Meena Iyer", "s-path").getOrThrow()
            order.id to repo.reportShareToken(order.id, order.accessionNo)
        }

        fun row(orderId: String) = runBlocking { repo.reportShare(orderId)!! }
    }

    @Test
    fun `the drain publishes the snapshot, and an unchanged report is not sent again`() = runBlocking {
        val lab = Lab()
        val (orderId, token) = lab.approvedOrder("Asha Raman")

        assertEquals(1, lab.uploader.drain())
        val (sentToken, snapshot) = lab.published.single()
        assertEquals(token, sentToken)
        assertEquals("Asha Raman", snapshot.getValue("patient").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("2026-09-15T08:00:00Z", snapshot.getValue("generatedAt").jsonPrimitive.content)
        val row = lab.row(orderId)
        assertEquals("uploaded", row.state)
        assertEquals(ReportSnapshot.contentSha256(snapshot), row.sha256, "the local row remembers what the server has")

        // A reprint re-queues it; the rebuilt report is identical, so nothing goes out.
        lab.now += 1.hours
        lab.db.labReportsQueries.requeueReport(lab.now.toString(), orderId)
        assertEquals("pending", lab.row(orderId).state)
        assertEquals(1, lab.uploader.drain())
        assertEquals(1, lab.published.size, "an unchanged snapshot is not uploaded twice")
        assertEquals("uploaded", lab.row(orderId).state)
        assertEquals(row.publishedAt, lab.row(orderId).publishedAt, "published_at still says when it last went out")

        // A revoke that lands while an upload is in flight is not undone by its result.
        lab.db.labReportsQueries.requeueReport(lab.now.toString(), orderId)
        lab.repo.revokeReportShare(orderId)
        lab.repo.markReportUploaded(orderId, token, "sha")
        assertEquals("revoked", lab.row(orderId).state)
    }

    @Test
    fun `a report that keeps failing backs off and never holds up a fresh one`() = runBlocking {
        val lab = Lab()
        val (stuck, stuckToken) = lab.approvedOrder("Stuck Patient")
        lab.failing += stuckToken
        // Oldest first — before the fix, take(limit) over created_at would pick it every time.
        lab.db.labReportsQueries.upsertReport(stuck, stuckToken, "ACC-OLD", "pending", null, null, null,
            "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")

        assertEquals(0, lab.uploader.drain(limit = 1))
        lab.row(stuck).let {
            assertEquals(1, it.attempts)
            assertEquals((lab.now + 5.minutes).toString(), it.nextAttemptAt)
            assertEquals("pending", it.state)
        }

        val (fresh, freshToken) = lab.approvedOrder("Fresh Patient")
        assertEquals(1, lab.uploader.drain(limit = 1), "the fresh report goes out past the failing one")
        assertEquals(listOf(freshToken), lab.published.map { it.first })
        assertEquals("uploaded", lab.row(fresh).state)

        // Inside its backoff the stuck row is not even tried.
        lab.now += 2.minutes
        assertEquals(0, lab.uploader.drain())
        assertEquals(1, lab.row(stuck).attempts)

        lab.now += 4.minutes
        assertEquals(0, lab.uploader.drain())
        assertEquals(2, lab.row(stuck).attempts, "due again, tried again, backed off further")
        assertEquals((lab.now + 10.minutes).toString(), lab.row(stuck).nextAttemptAt)

        // The server comes back: the next due run publishes it and clears the count.
        lab.failing.clear()
        lab.now += 11.minutes
        assertEquals(1, lab.uploader.drain())
        lab.row(stuck).let {
            assertEquals("uploaded", it.state)
            assertEquals(0, it.attempts)
            assertNull(it.nextAttemptAt)
        }

        assertEquals(5.minutes, ReportUploader.retryDelay(1))
        assertEquals(10.minutes, ReportUploader.retryDelay(2))
        assertEquals(80.minutes, ReportUploader.retryDelay(5))
        assertEquals(6.hours, ReportUploader.retryDelay(9))
        assertEquals(6.hours, ReportUploader.retryDelay(500), "capped, and never gives up")
    }

    @Test
    fun `reports published as PDFs republish once as snapshots`() = runBlocking {
        val lab = Lab()
        val (orderId, token) = lab.approvedOrder("Legacy Patient")
        // What a PDF-era build left behind: uploaded, no hash.
        lab.db.labReportsQueries.upsertReport(orderId, token, "ACC-1", "uploaded", "2026-09-01T00:00:00Z", null, null,
            "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")

        assertEquals(1, lab.uploader.drain())
        assertEquals(listOf(token), lab.published.map { it.first }, "same token — the printed QR keeps working")
        assertTrue(lab.prefs.reportsRequeuedForSnapshot)

        // Once only: a row marked uploaded after the switch is not swept again.
        lab.db.labReportsQueries.upsertReport(orderId, token, "ACC-1", "uploaded", "2026-09-01T00:00:00Z", null, null,
            "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")
        assertEquals(0, lab.uploader.drain())
        assertEquals(1, lab.published.size)
    }

    @Test
    fun `a revoked link is never printed again — the next print mints a new token`() = runBlocking {
        val lab = Lab()
        val (orderId, dead) = lab.approvedOrder("Wrong Patient")
        lab.repo.revokeReportShare(orderId)

        val next = lab.repo.reportShareToken(orderId, "ACC-1")
        assertNotEquals(dead, next)
        assertTrue(ReportShare.isWellFormed(next))
        assertEquals("pending", lab.row(orderId).state, "the new link is queued like a first print")
        assertNull(lab.db.labReportsQueries.reportByToken(dead).executeAsOneOrNull())
        assertEquals(next, lab.repo.reportShareToken(orderId, "ACC-1"), "and is then reused like any live token")

        assertEquals(1, lab.uploader.drain())
        assertEquals(listOf(next), lab.published.map { it.first }, "the killed token is never republished")
    }

    @Test
    fun `a device with the old lab_reports table gains the retry columns`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        driver.execute(null, "DROP TABLE lab_reports", 0)
        driver.execute(null,
            "CREATE TABLE lab_reports (order_id TEXT NOT NULL PRIMARY KEY, " +
            "token TEXT NOT NULL, accession_no TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', " +
            "published_at TEXT, expires_at TEXT, sha256 TEXT, created_at TEXT NOT NULL, updated_at TEXT)", 0)
        driver.execute(null, "INSERT INTO lab_reports VALUES ('o-old', '${"a".repeat(64)}', 'ACC-9', 'pending', " +
            "NULL, NULL, NULL, '2026-09-01T00:00:00Z', NULL)", 0)
        // The self-heal, run TWICE — the second pass must be a harmless no-op.
        repeat(2) {
            driver.addColumn("lab_reports", "attempts", "INTEGER NOT NULL DEFAULT 0")
            driver.addColumn("lab_reports", "next_attempt_at", "TEXT")
        }

        val repo = LabRepository(AppDatabase(driver), ApiClient.json)
        val row = assertNotNull(repo.pendingReportUploads().singleOrNull())
        assertEquals("ACC-9", row.accessionNo)
        assertEquals(0, row.attempts)
        repo.deferReportUpload("o-old", "a".repeat(64), "2026-09-15T08:05:00Z")
        repo.reportShare("o-old")!!.let {
            assertEquals(1, it.attempts)
            assertEquals("2026-09-15T08:05:00Z", it.nextAttemptAt)
            assertTrue(!it.isDue(Instant.parse("2026-09-15T08:04:59Z")) && it.isDue(Instant.parse("2026-09-15T08:05:00Z")))
        }
    }
}
