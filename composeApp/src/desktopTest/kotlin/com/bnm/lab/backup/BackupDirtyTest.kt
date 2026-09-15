package com.bnm.lab.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.Patient
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Three independent ways a change is noticed — the driver listener, the
 * `data_version` probe and the preferences hash — and the debounce that turns
 * "dirty" into "snapshot now" on a fake clock.
 */
class BackupDirtyTest {

    private val nano = AtomicLong(1_000_000_000L)
    private val wall = AtomicLong(1_760_000_000_000L)
    private fun settings() = PropertiesSettings(Properties())

    private fun service(settings: PropertiesSettings, dir: File) = BackupService(
        prefs = BackupPrefs(settings, flush = {}),
        dataDir = { dir },
        nanos = { nano.get() },
        wall = { wall.get() },
        appVersion = "1.2.0",
        kdfIterations = 1_000,
        sameVolumeAsData = { _, _ -> false },
    )

    @Test
    fun `the driver listener marks dirty on a generated write and persists since-when once`() = runBlocking {
        val dir = Files.createTempDirectory("bnm-dirty").toFile()
        try {
            val s = settings()
            val svc = service(s, dir)
            val dbFile = File(dir, "bnm_chat.db")
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
            AppDatabase.Schema.create(driver)
            svc.attachDatabase(driver, dbFile)
            assertFalse(svc.dirty.isDirty)
            assertNull(s.getLongOrNull(BackupPrefs.K_DIRTY_SINCE))

            val repo = LabRepository(AppDatabase(driver), ApiClient.json, accessionSeat = { "T01" })
            repo.upsertPatient(Patient(id = "", name = "A", sex = "M"))
            assertTrue(svc.dirty.isDirty)
            assertEquals(wall.get(), s.getLongOrNull(BackupPrefs.K_DIRTY_SINCE))
            assertEquals(wall.get(), svc.dirty.dirtySinceWall)

            wall.addAndGet(60_000)
            repo.upsertPatient(Patient(id = "", name = "B", sex = "F"))
            assertEquals(wall.get() - 60_000, svc.dirty.dirtySinceWall, "the FIRST unsaved change is what staff see")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the data_version probe catches a raw write the listener never sees`() {
        val dir = Files.createTempDirectory("bnm-dirty-dv").toFile()
        try {
            val svc = service(settings(), dir)
            val dbFile = File(dir, "bnm_chat.db")
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
            AppDatabase.Schema.create(driver)
            svc.attachDatabase(driver, dbFile)
            svc.probeDataVersion() // baseline
            svc.probeDataVersion()
            assertFalse(svc.dirty.isDirty, "no commit, no dirt")
            driver.execute(null, "INSERT INTO lab_settings(key, value) VALUES ('commission.base_pct', '10')", 0)
            assertFalse(svc.dirty.isDirty, "raw driver.execute bypasses the listener")
            svc.probeDataVersion()
            assertTrue(svc.dirty.isDirty)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a carried preference edit is a change, a session key is not`() {
        val dir = Files.createTempDirectory("bnm-dirty-prefs").toFile()
        try {
            val s = settings()
            val svc = service(s, dir)
            s.putString("report_lh_address", "12 Main Rd")
            svc.probePrefsHash() // baseline
            s.putString("session_token", "changed")
            s.putString("lab_backup_seq", "9")
            svc.probePrefsHash()
            assertFalse(svc.dirty.isDirty)
            s.putString("report_lh_address", "14 Main Rd")
            svc.probePrefsHash()
            assertTrue(svc.dirty.isDirty)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `debounce - twenty quiet seconds, or two minutes of continuous writing`() {
        val sec = 1_000_000_000L
        var t = 100 * sec
        val tracker = DirtyTracker(nanos = { t }, wall = { wall.get() })
        assertFalse(tracker.due(t))
        tracker.markDirty()
        t += 10 * sec
        assertFalse(tracker.due(t), "10 s of quiet is not enough")
        t += 10 * sec
        assertTrue(tracker.due(t), "20 s of quiet")

        // An analyzer batch writing every 5 s never goes quiet — the ceiling fires.
        val tracker2 = DirtyTracker(nanos = { t }, wall = { wall.get() })
        val start = t
        tracker2.markDirty()
        while (t - start < 115 * sec) {
            t += 5 * sec
            tracker2.markDirty()
            assertFalse(tracker2.due(t), "at ${(t - start) / sec} s")
        }
        t += 5 * sec
        tracker2.markDirty()
        assertTrue(tracker2.due(t), "120 s since the first unsaved write")
    }

    @Test
    fun `clearing after a snapshot keeps the writes that landed meanwhile`() {
        var t = 5_000_000_000L
        val persisted = ArrayList<Long?>()
        val tracker = DirtyTracker(nanos = { t }, wall = { wall.get() }, persistDirtySince = { persisted += it })
        tracker.markDirty()
        val before = tracker.writes.get()
        assertTrue(tracker.clearIfNoWritesSince(before))
        assertFalse(tracker.isDirty)
        assertEquals(listOf(wall.get(), null), persisted)

        tracker.markDirty()
        val before2 = tracker.writes.get()
        t += 1_000_000_000L
        tracker.markDirty() // a write during the snapshot
        assertFalse(tracker.clearIfNoWritesSince(before2))
        assertTrue(tracker.isDirty, "the newer write still waits")

        // A relaunch after a crash: the persisted since-when is carried into a clean tracker.
        val relaunched = DirtyTracker(nanos = { t }, wall = { wall.get() })
        relaunched.restore(1_234L)
        assertTrue(relaunched.isDirty)
        assertEquals(1_234L, relaunched.dirtySinceWall)
    }
}
