package com.bnm.lab.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.TestParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The snapshot must never lose a write and never block one: `VACUUM INTO`
 * runs against the REAL app schema on a file database while a coroutine
 * registers patients and orders as fast as it can. With the app driver's
 * busy_timeout raised (DriverFactory) and the engine's own connection at 15 s,
 * no write fails with SQLITE_BUSY, every snapshot passes `quick_check`, and
 * the last one taken after the writer stops matches the live counts.
 */
class BackupSnapshotTest {

    @Test
    fun `twenty snapshots under a write hammer - no busy errors, consistent copies, counts match`() = runBlocking {
        val dir = Files.createTempDirectory("bnm-snapshot").toFile()
        try {
            val dbFile = File(dir, "bnm_chat.db")
            val driver = JdbcSqliteDriver(
                "jdbc:sqlite:${dbFile.absolutePath}",
                Properties().apply { setProperty("busy_timeout", "10000") },
            )
            AppDatabase.Schema.create(driver)
            val repo = LabRepository(AppDatabase(driver), ApiClient.json, accessionSeat = { "T01" })
            repo.upsertTest(LabTest(id = "t-hb", code = "HB", name = "Haemoglobin", price = 100.0,
                parameters = listOf(TestParameter(key = "hb", name = "Hb", unit = "g/dL"))))

            val failures = ArrayList<Throwable>()
            val hammer = async(Dispatchers.Default) {
                var registered = 0
                repeat(150) { i ->
                    try {
                        val p = repo.upsertPatient(Patient(id = "p-$i", name = "Patient $i", sex = if (i % 2 == 0) "M" else "F"))
                        repo.createLabOrder(patientId = p.id, testIds = listOf("t-hb")).getOrThrow()
                        registered++
                    } catch (t: Throwable) {
                        synchronized(failures) { failures += t }
                    }
                }
                registered
            }

            val staging = File(File(dir, "backup"), "staging.db")
            val conn = BackupSnapshot.connect(dbFile, BackupPolicy.SNAPSHOT_BUSY_TIMEOUT_MS, readOnly = true)
            var snapshots = 0
            conn.use {
                repeat(20) {
                    BackupSnapshot.vacuumInto(conn, staging)
                    val counts = BackupSnapshot.prepareStaging(staging) // quick_check must be ok
                    assertTrue(counts.patients >= 0)
                    snapshots++
                }
                val registered = hammer.await()
                assertTrue(failures.isEmpty(), "writes failed under the snapshot: ${failures.map { it.message }}")
                assertEquals(150, registered)

                // After the writer stops, a snapshot is exactly the live database.
                BackupSnapshot.vacuumInto(conn, staging)
                val finalCounts = BackupSnapshot.prepareStaging(staging)
                val live = repo.tenantRowCounts()
                assertEquals(live.patients, finalCounts.patients)
                assertEquals(live.orders, finalCounts.orders)
                assertEquals(live.results, finalCounts.results)
                assertEquals(live.tests, finalCounts.tests)
                assertEquals(150L, finalCounts.patients)
                assertEquals(150L, finalCounts.orders)
                assertEquals("ok", BackupSnapshot.quickCheck(staging))
                assertEquals(finalCounts, BackupSnapshot.countsOf(dbFile))
            }
            assertEquals(20, snapshots)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `data_version moves only when another connection commits`() {
        val dir = Files.createTempDirectory("bnm-dv").toFile()
        try {
            val dbFile = File(dir, "bnm_chat.db")
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
            AppDatabase.Schema.create(driver)
            BackupSnapshot.connect(dbFile, 5_000, readOnly = true).use { probe ->
                val v0 = BackupSnapshot.dataVersion(probe)
                assertEquals(v0, BackupSnapshot.dataVersion(probe), "nothing committed")
                driver.execute(null, "INSERT INTO lab_settings(key, value) VALUES ('k', 'v')", 0)
                assertTrue(BackupSnapshot.dataVersion(probe) != v0, "a raw write the listener never sees still shows")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the drop guard trips on a fifth fewer patients, orders or results - not on tests or growth`() {
        val before = BackupCounts(patients = 1000, orders = 2000, results = 9000, staff = 6, tests = 223)
        assertTrue(BackupSnapshot.dropped(before, before.copy(patients = 700)))
        assertTrue(BackupSnapshot.dropped(before, before.copy(orders = 0)))
        assertTrue(BackupSnapshot.dropped(before, before.copy(results = 7000)))
        assertTrue(!BackupSnapshot.dropped(before, before.copy(patients = 850)), "15 % is normal churn")
        assertTrue(!BackupSnapshot.dropped(before, before.copy(tests = 10, staff = 1)), "catalog and staff are not the guard")
        assertTrue(!BackupSnapshot.dropped(before, before.copy(patients = 1500)))
        assertTrue(!BackupSnapshot.dropped(BackupCounts(), BackupCounts()), "an empty lab stays empty")
    }

    @Test
    fun `a file that is not a database is reported as corrupt, not as a failed write`() {
        val dir = Files.createTempDirectory("bnm-corrupt").toFile()
        try {
            val bad = File(dir, "bnm_chat.db").apply { writeText("this is not sqlite ".repeat(100)) }
            val staging = File(dir, "staging.db")
            val thrown = runCatching {
                BackupSnapshot.connect(bad, 1_000, readOnly = true).use { BackupSnapshot.vacuumInto(it, staging) }
            }.exceptionOrNull()
            assertTrue(thrown != null && (thrown is BackupSnapshot.DatabaseCorrupt || BackupSnapshot.isCorrupt(thrown)), "$thrown")
        } finally {
            dir.deleteRecursively()
        }
    }
}
