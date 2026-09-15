package com.bnm.lab.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.staff.sha256Hex
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole loop through the controller surface the screens use: set up a
 * pendrive (a temp folder), write generations, list and preview them, stage a
 * restore on a "new PC" (fresh prefs, fresh data dir), apply the swap at the
 * next launch, and rescue a generation headlessly. Real schema, real SQLite,
 * real crypto (fewer KDF rounds), no real preferences.
 */
class BackupServiceTest {

    private val key = "BNMD-2345-6789-ABCD-EFGH"
    private val nano = AtomicLong(1_000_000_000L)
    private val wall = AtomicLong(1_760_000_000_000L)

    private class Pc(val dir: File, val settings: PropertiesSettings, val service: BackupService, val dbFile: File)

    private fun pc(activated: Boolean, appVersion: String = "1.2.0"): Pc {
        val dir = Files.createTempDirectory("bnm-pc").toFile()
        val s = PropertiesSettings(Properties())
        if (activated) {
            s.putString("lab_license_fp", sha256Hex(key))
            s.putString("license_lab_name", "Sunrise Diagnostics")
            s.putString("license_device_id", "device-old-pc")
            s.putString("license_device_token", "tok-old")
            s.putString("license_jwt", "e30.e30.sig") // {} payload: no edition claim, fine for display
            s.putString("report_lh_address", "12 Main Rd, Salem")
            s.putString("pref_accession_prefix", "SUN")
            s.putString("session_token", "never-travels")
            s.putString("sync_pull_cursor", "42")
        }
        val service = BackupService(
            prefs = BackupPrefs(s, flush = {}), dataDir = { dir }, nanos = { nano.get() }, wall = { wall.get() },
            appVersion = appVersion, kdfIterations = 1_000, sameVolumeAsData = { _, _ -> false },
        )
        return Pc(dir, s, service, File(dir, "bnm_chat.db"))
    }

    private fun openDb(pc: Pc): LabRepository {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${pc.dbFile.absolutePath}", Properties().apply { setProperty("busy_timeout", "10000") })
        AppDatabase.Schema.create(driver)
        pc.service.attachDatabase(driver, pc.dbFile)
        return LabRepository(AppDatabase(driver), ApiClient.json, accessionSeat = { "T01" })
    }

    @Test
    fun `set up, back up, find, preview, restore on a new PC, swap at launch, and rescue headlessly`(): Unit = runBlocking {
        val drive = Files.createTempDirectory("bnm-pendrive").toFile()
        val old = pc(activated = true)
        val new = pc(activated = false)
        try {
            // ── the lab PC ──
            val repo = openDb(old)
            repo.upsertTest(LabTest(id = "t-hb", code = "HB", name = "Haemoglobin", price = 100.0))
            val p = repo.upsertPatient(Patient(id = "p-1", name = "Alaguraj", sex = "M"))
            repo.createLabOrder(patientId = p.id, testIds = listOf("t-hb")).getOrThrow()

            assertEquals(BackupStatus.Phase.NOT_SET_UP, old.service.status.value.phase)
            val wrongKey = old.service.setUp(drive.absolutePath, "BNMD-0000-0000-0000-0000")
            assertTrue(wrongKey.isFailure)
            assertTrue(wrongKey.exceptionOrNull()!!.message!!.contains("licence key"))

            val code = old.service.setUp(drive.absolutePath, key.lowercase()).getOrThrow()
            assertTrue(RecoveryCode.looksValid(code), code)
            val vault = File(drive, BackupNaming.VAULT_DIR)
            assertTrue(File(vault, BackupNaming.MARKER).isFile)
            assertEquals(1, DriveScan.generationFiles(vault).size)
            val st = old.service.status.value
            assertEquals(BackupStatus.Phase.OK, st.phase, st.toString())
            assertEquals(1, st.generations)
            assertNotNull(st.lastOkAt)
            assertNull(st.dirtySince)
            assertEquals(RecoveryCode.format(RecoveryCode.normalise(code)!!), old.service.recoveryCode())
            val marker = DriveScan.readMarker(vault)!!
            assertEquals("Sunrise Diagnostics", marker.lab)
            assertEquals(DriveScan.ownerHash("device-old-pc"), marker.ownerDeviceHash)

            // A second patient: the listener marks dirty, "Back up now" writes generation 2.
            repo.upsertPatient(Patient(id = "p-2", name = "Kavitha", sex = "F"))
            assertTrue(old.service.dirty.isDirty)
            assertEquals(BackupStatus.Phase.PENDING, run { old.service.tick(); old.service.status.value.phase })
            old.service.backupNow().getOrThrow()
            assertEquals(2, DriveScan.generationFiles(vault).size)
            assertEquals(2L, old.settings.getLongOrNull(BackupPrefs.K_SEQ))
            assertEquals(BackupStatus.Phase.OK, old.service.status.value.phase)

            // ── what the restore dialog sees ──
            val gens = old.service.findBackupsAt(drive.absolutePath).getOrThrow()
            assertEquals(listOf(2L, 1L), gens.map { it.seq }, "newest first, by seq")
            assertTrue(gens.none { it.damaged })
            assertEquals("Sunrise Diagnostics", gens[0].labName)
            val newest = gens[0]
            val viaThisPc = old.service.preview(newest, Unlock.ThisPc).getOrThrow()
            assertEquals(2, viaThisPc.patients)
            assertEquals(1, viaThisPc.orders)
            assertEquals(true, viaThisPc.sameLicence)
            assertFalse(viaThisPc.newerThanThisApp)
            assertEquals(2, viaThisPc.currentPatientsHere)
            assertEquals(2, old.service.preview(newest, Unlock.RecoveryCode(code)).getOrThrow().patients)
            assertTrue(old.service.preview(newest, Unlock.RecoveryCode(RecoveryCode.generate())).isFailure)

            // Nothing that must not travel did.
            val carriedNames = BackupContainer.open(File(newest.path), java.util.Base64.getDecoder().decode(old.settings.getStringOrNull(BackupPrefs.K_DEK)!!))
                .second.use { BackupBundle.readHead(it) }.prefs.keys
            assertTrue("report_lh_address" in carriedNames && "license_jwt" in carriedNames && "lab_license_fp" in carriedNames, "$carriedNames")
            assertTrue("session_token" !in carriedNames && "sync_pull_cursor" !in carriedNames && "license_device_id" !in carriedNames, "$carriedNames")

            // ── the new PC: not activated, empty data dir ──
            val found = new.service.findBackupsAt(File(drive, "BNM Lab Backup/snapshots").absolutePath).getOrThrow()
            assertEquals(2, found.size, "a picked sub-folder resolves to the vault")
            val target = found[0]
            assertTrue(new.service.preview(target, Unlock.ThisPc).isFailure, "no vault key on a fresh PC")
            val preview = new.service.preview(target, Unlock.LicenceKey(key)).getOrThrow()
            assertNull(preview.sameLicence, "not activated: nothing to compare with")
            assertEquals(0, preview.currentPatientsHere)

            val staged = new.service.stageRestore(target, Unlock.LicenceKey(key)).getOrThrow()
            val pending = File(new.dir, "bnm_chat.db.restore-pending")
            assertTrue(pending.isFile)
            assertEquals("ok", BackupSnapshot.quickCheck(pending))
            assertEquals("12 Main Rd, Salem", new.settings.getStringOrNull("report_lh_address"))
            assertEquals("SUN", new.settings.getStringOrNull("pref_accession_prefix"))
            assertEquals(sha256Hex(key), new.settings.getStringOrNull("lab_license_fp"), "the tenant guard will accept the same key")
            assertEquals("e30.e30.sig", new.settings.getStringOrNull("license_jwt"), "the signed token travels")
            assertNull(new.settings.getStringOrNull("license_device_id"), "a fresh device id is minted next launch")
            assertNull(new.settings.getStringOrNull("session_token"))
            assertNull(new.settings.getStringOrNull("sync_pull_cursor"))
            assertEquals(vault.absolutePath, new.settings.getStringOrNull(BackupPrefs.K_DIR), "the pendrive is adopted")
            assertEquals(old.settings.getStringOrNull(BackupPrefs.K_ID), new.settings.getStringOrNull(BackupPrefs.K_ID))
            assertEquals(old.settings.getStringOrNull(BackupPrefs.K_DEK), new.settings.getStringOrNull(BackupPrefs.K_DEK))
            assertEquals(2L, new.settings.getLongOrNull(BackupPrefs.K_SEQ), "numbering continues from the drive")
            assertEquals("device-old-pc", new.settings.getStringOrNull(BackupPrefs.K_RESTORED_FROM))
            assertTrue(new.service.status.value.restoredFromBackup)
            assertNull(new.settings.getStringOrNull(BackupPrefs.K_CODE), "unlocked with the key: the code is not on this PC")
            assertTrue(staged.notes.isEmpty(), staged.notes.toString())

            // ── next launch on the new PC: the swap, then the engine keeps writing to the same stick ──
            assertNotNull(RestoreStaging.applyPending(new.dbFile))
            assertEquals(2L, BackupSnapshot.countsOf(new.dbFile).patients)
            assertEquals(1L, BackupSnapshot.countsOf(new.dbFile).orders)
            new.settings.putString("license_device_id", "device-new-pc") // what LicenseManager mints on first read
            val repo2 = openDb(new)
            repo2.upsertPatient(Patient(id = "p-3", name = "Third", sex = "M"))
            new.service.tick() // absent→present, owner rebind, and — dirty on arrival — a snapshot at once
            val marker2 = DriveScan.readMarker(vault)!!
            assertEquals(DriveScan.ownerHash("device-new-pc"), marker2.ownerDeviceHash, "the stick now names the new PC")
            assertEquals(3L, DriveScan.maxSeq(vault), "generation 3 continues the drive's numbering")
            assertEquals(BackupStatus.Phase.OK, new.service.status.value.phase, new.service.status.value.toString())

            // ── the old PC, if it ever comes back, must not write over the moved records ──
            val refused = old.service.backupNow()
            assertTrue(refused.isFailure)
            assertTrue(refused.exceptionOrNull()!!.message!!.contains("moved to another computer"), refused.exceptionOrNull()!!.message)
            val oldStatus = old.service.status.value
            assertEquals(BackupStatus.Phase.FAILING, oldStatus.phase, oldStatus.toString())
            assertTrue(oldStatus.lastError!!.contains("moved to another computer"), oldStatus.lastError)
            assertEquals(3L, DriveScan.maxSeq(vault), "nothing written by the old PC")

            // ── headless rescue ──
            val out = File(Files.createTempDirectory("bnm-rescue").toFile(), "rescued.db")
            val lines = ArrayList<String>()
            val exit = BackupCli.run(arrayOf("--export-backup", target.path, out.absolutePath, "--key", key), BackupPrefs(PropertiesSettings(Properties()), flush = {})) { lines += it }
            assertEquals(0, exit, lines.toString())
            assertEquals("ok", BackupSnapshot.quickCheck(out))
            assertTrue(File(out.parentFile, "prefs.json").isFile)
            assertTrue(lines.any { it.startsWith("counts: patients=2") }, lines.toString())
            assertEquals(1, BackupCli.run(arrayOf("--export-backup", target.path, out.absolutePath, "--code", RecoveryCode.generate()), BackupPrefs(PropertiesSettings(Properties()), flush = {})) { lines += it })
            assertNull(BackupCli.run(arrayOf(), BackupPrefs(PropertiesSettings(Properties()), flush = {})) { }, "no flag: start the app")
            out.parentFile.deleteRecursively()
        } finally {
            drive.deleteRecursively()
            old.dir.deleteRecursively()
            new.dir.deleteRecursively()
        }
    }

    @Test
    fun `a newer backup and a foreign licence are refused - a same-PC roll-back is not`(): Unit = runBlocking {
        val drive = Files.createTempDirectory("bnm-pendrive").toFile()
        val old = pc(activated = true, appVersion = "1.3.0")
        val stale = pc(activated = true, appVersion = "1.2.0")
        val other = pc(activated = true, appVersion = "1.3.0").also { it.settings.putString("lab_license_fp", sha256Hex("BNMD-OTHER-LAB0-KEY0-0000")) }
        try {
            val repo = openDb(old)
            repo.upsertPatient(Patient(id = "p-1", name = "One", sex = "M"))
            old.service.setUp(drive.absolutePath, key).getOrThrow()
            val gen = old.service.findBackupsAt(drive.absolutePath).getOrThrow().first()

            openDb(stale)
            val newer = stale.service.stageRestore(gen, Unlock.LicenceKey(key))
            assertTrue(newer.isFailure)
            assertTrue(newer.exceptionOrNull()!!.message!!.contains("Update BNM Lab first"))
            assertTrue(stale.service.preview(gen, Unlock.LicenceKey(key)).getOrThrow().newerThanThisApp)

            openDb(other)
            val foreign = other.service.stageRestore(gen, Unlock.LicenceKey(key))
            assertTrue(foreign.isFailure)
            assertTrue(foreign.exceptionOrNull()!!.message!!.contains("different lab licence"))
            assertEquals(false, other.service.preview(gen, Unlock.LicenceKey(key)).getOrThrow().sameLicence)

            // Same PC, roll back to generation 1 after more work: no secret asked, a before-restore generation first.
            repo.upsertPatient(Patient(id = "p-2", name = "Two", sex = "F"))
            old.service.stageRestore(gen, Unlock.ThisPc).getOrThrow()
            val vault = File(drive, BackupNaming.VAULT_DIR)
            assertEquals(2L, DriveScan.maxSeq(vault), "the current records went to the pendrive before the roll-back")
            assertTrue(File(old.dir, "bnm_chat.db.restore-pending").isFile)
            assertNull(old.settings.getStringOrNull("license_device_id"), "a roll-back also mints a fresh device id — numbers issued after generation 1 can never repeat")
            assertTrue(old.settings.getBoolean(BackupPrefs.K_EXPECT_DROP, false), "the next generation is smaller on purpose")
            assertNotNull(old.settings.getStringOrNull(BackupPrefs.K_CODE), "own vault: the code stays")
        } finally {
            drive.deleteRecursively()
            old.dir.deleteRecursively()
            stale.dir.deleteRecursively()
            other.dir.deleteRecursively()
        }
    }

    @Test
    fun `tenant hooks - a snapshot before the wipe, then the vault is retired`(): Unit = runBlocking {
        val drive = Files.createTempDirectory("bnm-pendrive").toFile()
        val lab = pc(activated = true)
        try {
            val repo = openDb(lab)
            repo.upsertPatient(Patient(id = "p-1", name = "One", sex = "M"))
            lab.service.setUp(drive.absolutePath, key).getOrThrow()
            repo.upsertPatient(Patient(id = "p-2", name = "Two", sex = "M"))
            lab.service.beforeTenantWipe()
            val vault = File(drive, BackupNaming.VAULT_DIR)
            assertEquals(2L, DriveScan.maxSeq(vault))
            assertTrue(lab.settings.getBoolean(BackupPrefs.K_EXPECT_DROP, false))
            lab.service.afterTenantWipe()
            assertEquals(BackupStatus.Phase.NOT_SET_UP, lab.service.status.value.phase)
            assertTrue(lab.settings.keys.none { it.startsWith("lab_backup_") }, lab.settings.keys.toString())
            assertEquals(2, DriveScan.generationFiles(vault).size, "the old lab's generations stay on the old stick")
            assertNull(lab.service.recoveryCode())
        } finally {
            drive.deleteRecursively()
            lab.dir.deleteRecursively()
        }
    }

    @Test
    fun `restore offer at launch - only for an empty database with a bound pendrive present`(): Unit = runBlocking {
        val drive = Files.createTempDirectory("bnm-pendrive").toFile()
        val lab = pc(activated = true)
        val reinstalled = pc(activated = true)
        try {
            val repo = openDb(lab)
            repo.upsertPatient(Patient(id = "p-1", name = "One", sex = "M"))
            lab.service.setUp(drive.absolutePath, key).getOrThrow()
            assertNull(lab.service.restoreOfferAtLaunch(), "records present: nothing to offer")

            // Same prefs (the registry survived), no database (a reinstall wiped %APPDATA%).
            for (k in lab.settings.keys) reinstalled.settings.putString(k, lab.settings.getStringOrNull(k)!!)
            assertTrue(BackupHooks.boundPendriveReachable(BackupPrefs(reinstalled.settings, flush = {})), "DriverFactory skips legacy adoption")
            openDb(reinstalled)
            val offer = reinstalled.service.restoreOfferAtLaunch()
            assertNotNull(offer)
            assertEquals(1L, offer.seq)
            val restoredStatus = reinstalled.service.status.value
            assertEquals(BackupStatus.Phase.NOT_SET_UP, restoredStatus.phase, "no tick yet: still the initial status")
            reinstalled.service.tick()
            assertEquals(BackupStatus.Phase.OK, reinstalled.service.status.value.phase, reinstalled.service.status.value.toString())
        } finally {
            drive.deleteRecursively()
            lab.dir.deleteRecursively()
            reinstalled.dir.deleteRecursively()
        }
    }
}
