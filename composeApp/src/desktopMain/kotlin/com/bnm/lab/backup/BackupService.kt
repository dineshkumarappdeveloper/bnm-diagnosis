package com.bnm.lab.backup

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import com.bnm.lab.BuildInfo
import com.bnm.lab.db.CHAT_DB_NAME
import com.bnm.lab.db.appDataDir
import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.diagnostics.DiagnosticsContext
import com.bnm.lab.platform.exitApp
import com.bnm.lab.staff.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The desktop Backup pendrive engine — the only [BackupController].
 *
 * One daemon thread ticks every [BackupPolicy.TICK_MS]: probes the pendrive
 * (`Files.exists` on its marker), probes the database for changes the driver
 * listener could not see, hashes the carried preferences once a minute, and
 * when the database has been dirty long enough — 20 s of quiet, or 2 min of
 * continuous writing — takes a snapshot with `VACUUM INTO`, bundles it with
 * the manifest and preferences, encrypts it and writes it to the pendrive as
 * one new generation, reads it back, and prunes. Never on a Compose scope;
 * never a file copy of the open database; never anything that blocks a
 * writer beyond its own busy_timeout.
 *
 * Started from `main()` before the window exists, but idle until
 * [attachDatabase] (called by DriverFactory once the database is open) — so
 * a restore staged for THIS launch is applied before the engine ever reads.
 *
 * Every constructor argument is a test seam: an in-memory prefs store, a temp
 * data directory, a fake clock, fewer KDF iterations. Production uses
 * [shared].
 */
internal class BackupService(
    private val prefs: BackupPrefs = BackupPrefs(),
    private val dataDir: () -> File = { appDataDir() },
    private val nanos: () -> Long = System::nanoTime,
    private val wall: () -> Long = System::currentTimeMillis,
    private val appVersion: String = BuildInfo.VERSION,
    private val kdfIterations: Int = BackupPolicy.KDF_ITERATIONS,
    /** "That is this computer's own disk" — a seam only because a test's two temp folders share one volume. */
    private val sameVolumeAsData: (folder: File, dataDir: File) -> Boolean = { f, d -> DriveScan.sameVolumeAsData(f, d) },
) : BackupController {

    companion object {
        val shared: BackupService by lazy { BackupService() }

        /** Every table in the .sq files except `instrument_log` (analyzer chatter, trimmed to 500 rows). */
        internal val WATCHED_TABLES = listOf(
            "accession_series", "billing_outbox", "referrer_commission_rates", "referrer_payouts", "lab_settings",
            "counter_series", "ecom_entity", "emr_inbox", "instruments", "instrument_results", "lab_result_graphs",
            "patients", "lab_reports", "lab_orders", "lab_order_tests", "referrer_rates", "staff", "referrers",
            "lab_results", "sync_state", "lab_tests", "lab_panels",
        )

        const val MSG_DB_PROBLEM = "Database problem detected — contact BNM"
        const val MSG_SMALLER = "Data looks smaller than before — older backups are being kept. If this is expected, press Back up now."
        const val MSG_FULL = "Pendrive full — use a bigger pendrive"
        const val MSG_UNREADABLE = "Backup could not be read back — replace the pendrive"
        private const val REASON_SCHEDULED = "scheduled"
        private const val REASON_MANUAL = "manual"
        private const val REASON_CLOSE = "close"
        private const val REASON_BEFORE_WIPE = "before-tenant-wipe"
        private const val REASON_BEFORE_RESTORE = "before-restore"
    }

    private val _status = MutableStateFlow(BackupStatus())
    override val status: StateFlow<BackupStatus> = _status.asStateFlow()

    /** Serialises every snapshot / restore / set-up against the tick. */
    private val engineLock = ReentrantLock()
    private var executor: ScheduledExecutorService? = null
    internal val dirty = DirtyTracker(nanos, wall) { prefs.dirtySince = it }

    @Volatile private var dbFile: File? = null
    @Volatile private var driver: SqlDriver? = null
    private var probe: Connection? = null
    private var lastDataVersion: Long? = null
    private var lastPrefsHash: String? = null
    private var lastPrefsHashAt = 0L
    private var startedAt = 0L
    private var lastRootScanAt = 0L
    private var lastSnapshotAt = 0L
    private var catchUpChecked = false
    private var failUntilNanos = 0L
    private var consecutiveFailures = 0
    @Volatile private var started = false
    @Volatile private var present = false
    @Volatile private var working = false
    @Volatile private var dbProblem = false
    @Volatile private var verifyFailed = false
    @Volatile private var lastError: String? = null
    /** The marker's `created` when another PC adopted this pendrive — writes are refused until an owner re-binds. */
    @Volatile private var movedOn: String? = null
    @Volatile private var bannerDismissed = false
    @Volatile private var driveName: String? = null
    @Volatile private var generations = 0
    @Volatile private var bytesOnDrive = 0L

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /** Called from `main()` right after diagnostics: registers the report section and starts the ticker. */
    fun start() {
        if (started) return
        started = true
        startedAt = nanos()
        prefs.dirtySince?.let { dirty.restore(it) }
        DiagnosticsContext.register("Backup") { diagnostics() }
        // One probe now, so the chip does not say "not connected" for the five
        // seconds until the first tick when the stick is in fact plugged in.
        runCatching { if (prefs.isBound) probeDrive(startedAt) }
        publish()
        executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "backup-engine").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ safeTick() }, BackupPolicy.TICK_MS, BackupPolicy.TICK_MS, TimeUnit.MILLISECONDS) }
        AppLog.i("Backup", "engine started (set up=${prefs.isBound})")
    }

    /**
     * The database is open: from here on the engine has something to back up.
     * The listener marks dirty on every generated write, synchronously on the
     * writer's thread — one compare-and-set, nothing else.
     */
    fun attachDatabase(driver: SqlDriver, dbFile: File) {
        this.dbFile = dbFile
        if (this.driver !== driver) {
            this.driver = driver
            driver.addListener(*WATCHED_TABLES.toTypedArray(), listener = Query.Listener { dirty.markDirty() })
        }
        runCatching { stagingFile().delete() }
        AppLog.i("Backup", "engine attached to the database (set up=${prefs.isBound}, dirty=${dirty.isDirty})")
    }

    /**
     * The JVM shutdown hook: Windows gives a closing app about five seconds
     * and shows "preventing shutdown" if it blocks, so this only persists what
     * is unsaved and removes a half-written file — NEVER a snapshot. The
     * close-window flush ([flushOnExit]) is the one long path, and the window
     * runs it before asking the JVM to exit.
     */
    fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread({
            runCatching { if (dirty.isDirty) prefs.dirtySince = dirty.dirtySinceWall ?: wall() }
            runCatching { stagingFile().delete() }
            runCatching { File(stagingFile().parentFile, "verify.db").delete() }
            runCatching {
                vaultDir()?.let { v ->
                    DriveScan.snapshotsDir(v).listFiles()?.forEach { m ->
                        m.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
                    }
                }
            }
            runCatching { probe?.close() }
            runCatching { prefs.flushNow() }
        }, "backup-shutdown"))
    }

    // ── the tick ──────────────────────────────────────────────────────────────

    private fun safeTick() {
        try {
            tick()
        } catch (t: Throwable) {
            AppLog.e("Backup", "tick failed", t)
        }
    }

    /** One pass of the engine; public so a test can drive it with a fake clock. Skips when a manual operation holds the lock. */
    fun tick() {
        if (!engineLock.tryLock()) return
        try {
            tickLocked()
        } finally {
            engineLock.unlock()
        }
    }

    private fun tickLocked() {
        val now = nanos()
        if (dbFile == null) {
            publish()
            return
        }
        val cameBack = probeDrive(now)
        probeDataVersion()
        if (now - lastPrefsHashAt >= ms(BackupPolicy.PREFS_HASH_MS)) probePrefsHash(now)

        if (prefs.isBound && present && movedOn == null && !dbProblem) {
            if (prefs.ownerRebindPending) rebindOwner()
            val backingOff = now < failUntilNanos
            val safety = dirty.isDirty && now - lastSnapshotAt >= ms(BackupPolicy.SAFETY_MS)
            when {
                backingOff -> Unit
                dirty.isDirty && (cameBack || dirty.due(now) || safety) -> snapshotLocked(REASON_SCHEDULED)
                !catchUpChecked && now - startedAt >= ms(BackupPolicy.CATCH_UP_AFTER_START_MS) -> {
                    catchUpChecked = true
                    if (newestOlderThan(BackupPolicy.CATCH_UP_STALE_MS)) {
                        AppLog.i("Backup", "newest generation is older than a day — writing one")
                        snapshotLocked(REASON_SCHEDULED)
                    }
                }
            }
            if (now - startedAt >= ms(BackupPolicy.VERIFY_AFTER_START_MS) && prefs.lastVerifiedDay != today()) verifyNewest()
        }
        publish()
    }

    /** True on the absent→present transition of the bound pendrive. */
    internal fun probeDrive(now: Long): Boolean {
        val vault = vaultDir()
        val id = prefs.vaultId
        if (vault == null || id == null) {
            present = false
            return false
        }
        if (!DriveScan.markerPresent(vault)) {
            if (present) AppLog.w("Backup", "pendrive disconnected")
            present = false
            movedOn = null
            // Drive-letter shuffle: the same stick may now sit under another root.
            if (now - lastRootScanAt >= ms(BackupPolicy.ROOT_SCAN_MS)) {
                lastRootScanAt = now
                runCatching { DriveScan.findVaultById(id) }.getOrNull()?.let { found ->
                    AppLog.i("Backup", "pendrive found under a different path — re-bound")
                    prefs.dir = found.absolutePath
                }
            }
            return false
        }
        if (present) return false
        val marker = DriveScan.readMarker(vault)
        if (marker == null || marker.id != id) {
            // Another stick under our letter: not ours, so for us it is absent.
            present = false
            return false
        }
        present = true
        checkOwner(marker)
        refreshDriveFacts(vault)
        AppLog.i("Backup", "pendrive connected ($generations generations)")
        return true
    }

    /** The staff-facing reason a write must not happen right now, or null when the bound stick is ours and here. */
    private fun recheckMarker(vault: File): String? {
        val marker = DriveScan.readMarker(vault)
        if (marker == null || marker.id != prefs.vaultId) {
            present = false
            return "The backup pendrive is not connected"
        }
        checkOwner(marker)
        return movedOn?.let(::movedMessage)
    }

    private fun checkOwner(marker: BackupMarker) {
        val mine = deviceId()?.let(DriveScan::ownerHash)
        val theirs = marker.ownerDeviceHash
        movedOn = if (mine != null && theirs != null && theirs != mine && !prefs.ownerRebindPending) {
            AppLog.w("Backup", "pendrive marker names another computer — writes refused")
            marker.created
        } else null
    }

    /** After a restore the stick still names the old PC; claim it once this PC has a device id. */
    private fun rebindOwner() {
        val id = deviceId() ?: return
        val vault = vaultDir() ?: return
        val old = DriveScan.readMarker(vault) ?: return
        runCatching {
            DriveScan.writeMarker(vault, old.copy(created = DriveScan.nowIso(), app = "BNM Lab $appVersion", ownerDeviceHash = DriveScan.ownerHash(id)))
            prefs.ownerRebindPending = false
            movedOn = null
            AppLog.i("Backup", "pendrive marker now names this computer")
        }.onFailure { AppLog.w("Backup", "could not rewrite the pendrive marker: ${it::class.simpleName}") }
    }

    internal fun probeDataVersion() {
        val db = dbFile ?: return
        try {
            val dv = BackupSnapshot.dataVersion(probeConnection(db))
            val prev = lastDataVersion
            lastDataVersion = dv
            if (prev != null && prev != dv) dirty.markDirty()
        } catch (e: SQLException) {
            runCatching { probe?.close() }
            probe = null
            AppLog.w("Backup", "data_version probe failed: ${e::class.simpleName}")
        }
    }

    internal fun probePrefsHash(now: Long = nanos()) {
        lastPrefsHashAt = now
        val h = runCatching { BackupAllowList.fingerprint(prefs.store) }.getOrNull() ?: return
        val prev = lastPrefsHash
        lastPrefsHash = h
        if (prev != null && prev != h) dirty.markDirty()
    }

    private fun probeConnection(db: File): Connection =
        probe ?: BackupSnapshot.connect(db, BackupPolicy.SNAPSHOT_BUSY_TIMEOUT_MS, readOnly = true).also { probe = it }

    private fun newestOlderThan(ageMs: Long): Boolean {
        val vault = vaultDir() ?: return false
        val newest = DriveScan.listGenerations(vault).firstOrNull { !it.damaged } ?: return true
        return wall() - newest.createdAt > ageMs
    }

    // ── the snapshot ──────────────────────────────────────────────────────────

    /** Take one generation now. Caller holds [engineLock]. */
    internal fun snapshotLocked(reason: String): Result<Unit> {
        val db = dbFile ?: return failure("The database is not open yet")
        if (!prefs.isBound) return failure("Backup pendrive is not set up")
        val vault = vaultDir() ?: return failure("Backup pendrive is not set up")
        if (!present) return failure("The backup pendrive is not connected")
        // Re-read the marker before EVERY write: a stick that went to another PC
        // and came back between two probes, or a different stick under the same
        // letter, must be caught here and not only on an observed absence.
        recheckMarker(vault)?.let { return failure(it) }
        val dek = dekBytes() ?: return failure("The backup key is missing on this computer")
        val slots = vaultSlots() ?: return failure("The backup key is missing on this computer")

        working = true
        publish()
        val staging = stagingFile()
        var part: File? = null
        try {
            val writesBefore = dirty.writes.get()
            val conn = probeConnection(db)
            val dvBefore = BackupSnapshot.dataVersion(conn)
            val took = try {
                BackupSnapshot.vacuumInto(conn, staging)
            } catch (e: BackupSnapshot.DatabaseCorrupt) {
                dbProblem = true
                lastError = MSG_DB_PROBLEM
                AppLog.e("Backup", "snapshot refused: the live database is corrupt", e)
                return failure(MSG_DB_PROBLEM)
            }
            val dvAfter = BackupSnapshot.dataVersion(conn)
            lastDataVersion = dvAfter
            if (took > BackupPolicy.SLOW_SNAPSHOT_MS) AppLog.w("Backup", "snapshot took $took ms (${staging.length() / 1024} KB)")
            else AppLog.i("Backup", "snapshot took $took ms (${staging.length() / 1024} KB)")
            val counts = BackupSnapshot.prepareStaging(staging)
            val previous = prefs.lastCounts?.let { runCatching { backupJson.decodeFromString(BackupCounts.serializer(), it) }.getOrNull() }
            val dropped = previous != null && !prefs.expectDrop && BackupSnapshot.dropped(previous, counts)

            val seq = BackupNaming.nextSeq(prefs.seq, DriveScan.maxSeq(vault))
            val created = ZonedDateTime.now()
            val manifest = BackupManifest(
                appVersion = appVersion, createdAt = created.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                seq = seq, reason = reason, labName = labName(), edition = edition(), labLicenseFp = licenceFp(),
                backupId = prefs.vaultId!!, previousDeviceId = deviceId(), dbBytes = staging.length(), counts = counts,
            )
            val header = ContainerHeader(
                backupId = manifest.backupId, lab = manifest.labName, created = manifest.createdAt, seq = seq,
                counts = counts, appVersion = appVersion, kdf = slots.kdf, slots = slots.slots, check = slots.check,
                noncePrefix = BackupContainer.newNoncePrefix(),
            )
            val carried = BackupAllowList.collect(prefs.store)

            ensureSpace(vault, staging.length())
            val monthDir = File(DriveScan.snapshotsDir(vault), BackupNaming.monthDir(created))
            val final = File(monthDir, BackupNaming.fileName(created, seq))
            val partFile = File(monthDir, final.name + ".part").also { part = it }
            DriveIo.retry("write generation") {
                Files.createDirectories(monthDir.toPath())
                writeContainer(partFile, header, dek, manifest, carried, staging)
            }
            try {
                DriveIo.retry("read generation back") { BackupContainer.verifyFile(partFile, dek) }
            } catch (e: Exception) {
                runCatching { DriveIo.move(partFile.toPath(), File(monthDir, final.name + ".bad").toPath()) }
                part = null
                throw e
            }
            DriveIo.retry("finish generation") { DriveIo.move(partFile.toPath(), final.toPath()) }
            part = null

            prefs.seq = seq
            prefs.lastOkAt = wall()
            prefs.lastCounts = backupJson.encodeToString(BackupCounts.serializer(), counts)
            if (prefs.expectDrop) prefs.expectDrop = false
            lastSnapshotAt = nanos()
            consecutiveFailures = 0
            failUntilNanos = 0L
            lastError = null
            dbProblem = false
            if (dropped) {
                prefs.retentionPaused = true
                lastError = MSG_SMALLER
                AppLog.w("Backup", "row counts dropped against the previous generation — retention paused")
            }
            // A commit that landed between the probe and the snapshot may or may
            // not be in it: keep dirty and let the next quiet period settle it.
            if (dvBefore != dvAfter) dirty.markDirty()
            dirty.clearIfNoWritesSince(writesBefore)
            lastPrefsHash = runCatching { BackupAllowList.fingerprint(prefs.store) }.getOrNull()
            runCatching { prune(vault) }.onFailure { AppLog.w("Backup", "pruning failed: ${it::class.simpleName}") }
            refreshDriveFacts(vault)
            AppLog.i("Backup", "generation $seq written ($reason, ${final.length() / 1024} KB, patients=${counts.patients} orders=${counts.orders})")
            return Result.success(Unit)
        } catch (e: Exception) {
            part?.let { p -> runCatching { p.delete() } }
            onWriteFailure(e, reason, vault)
            return failure(lastError ?: "Backup failed")
        } finally {
            working = false
            runCatching { staging.delete() }
            publish()
        }
    }

    private fun onWriteFailure(e: Exception, reason: String, vault: File) {
        consecutiveFailures++
        prefs.writeErrors = prefs.writeErrors + 1
        val step = BackupPolicy.RETRY_BACKOFF_MS[minOf(consecutiveFailures - 1, BackupPolicy.RETRY_BACKOFF_MS.size - 1)]
        failUntilNanos = nanos() + ms(step)
        lastError = when {
            DriveIo.isDiskFull(e) -> {
                runCatching { prune(vault, toFloor = true) }
                MSG_FULL
            }
            consecutiveFailures >= BackupPolicy.REPLACE_AFTER_FAILURES ->
                "Replace the pendrive; the last good backup is ${prefs.lastOkAt?.let(::clock) ?: "unknown"}"
            e is BackupDamagedException -> "The backup could not be read back — retrying"
            else -> "Could not write to the pendrive (${e::class.simpleName})"
        }
        AppLog.e("Backup", "generation write failed ($reason, failure #$consecutiveFailures, retry in ${step / 1000} s)", e)
    }

    private fun writeContainer(
        part: File, header: ContainerHeader, dek: ByteArray, manifest: BackupManifest,
        carried: Map<String, String>, staging: File,
    ) {
        FileChannel.open(part.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { ch ->
            val raw = BufferedOutputStream(Channels.newOutputStream(ch), 256 * 1024)
            val headerBytes = BackupContainer.writeHeader(raw, header)
            val enc = BackupContainer.encryptingStream(raw, dek, headerBytes, header.noncePrefix)
            BackupBundle.write(enc, manifest, carried, staging)
            enc.close()
            raw.flush()
            // Reaches the stick's controller; what it does with it is the stick's business.
            ch.force(true)
        }
    }

    /** Room for the new generation, pruning to the floor when there is not; still short ⇒ "pendrive full". */
    private fun ensureSpace(vault: File, needed: Long) {
        val margin = 16L * 1024 * 1024
        if (DriveScan.freeBytes(vault) >= needed + margin) return
        prune(vault, toFloor = true)
        if (DriveScan.freeBytes(vault) < needed + margin) throw IOException("not enough space on the pendrive")
    }

    private fun prune(vault: File, toFloor: Boolean = false) {
        val paused = prefs.retentionPaused
        if (paused) return
        val files = DriveScan.generationFiles(vault).associateBy { it.name }
        val entries = files.keys.mapNotNull { BackupRetention.toEntry(it) }
        val plan = if (toFloor) {
            BackupRetention.plan(entries, BackupRetention.today(), paused = false, keepNewest = BackupPolicy.KEEP_FLOOR, dailyDays = 0, monthlyMonths = 0)
        } else {
            BackupRetention.plan(entries, BackupRetention.today(), paused = false)
        }
        for (e in plan.delete) {
            val f = files[e.name] ?: continue
            DriveIo.retry("delete old generation") { Files.deleteIfExists(f.toPath()) }
        }
        if (plan.delete.isNotEmpty()) AppLog.i("Backup", "pruned ${plan.delete.size} old generation(s), ${plan.keep.size} kept")
        val now = wall()
        DriveScan.snapshotsDir(vault).listFiles { d -> d.isDirectory }?.forEach { month ->
            month.listFiles()?.forEach { f ->
                if (f.isFile && BackupRetention.isStaleScrap(f.name, f.lastModified(), now)) runCatching { f.delete() }
            }
            if (month.listFiles()?.isEmpty() == true) runCatching { month.delete() }
        }
    }

    // ── daily read-back ───────────────────────────────────────────────────────

    private fun verifyNewest() {
        val vault = vaultDir() ?: return
        val dek = dekBytes() ?: return
        prefs.lastVerifiedDay = today()
        val newest = DriveScan.listGenerations(vault).firstOrNull { !it.damaged } ?: return
        val temp = File(stagingFile().parentFile, "verify.db")
        try {
            temp.parentFile?.mkdirs()
            temp.delete()
            val (_, stream) = BackupContainer.open(File(newest.path), dek)
            stream.use { BackupBundle.extract(it, temp) }
            val verdict = BackupSnapshot.quickCheck(temp)
            if (verdict != "ok") throw BackupDamagedException("quick_check: $verdict")
            prefs.lastVerifiedAt = wall()
            if (verifyFailed) lastError = null
            verifyFailed = false
            AppLog.i("Backup", "generation ${newest.seq} verified restorable")
        } catch (e: Exception) {
            verifyFailed = true
            lastError = MSG_UNREADABLE
            AppLog.e("Backup", "generation ${newest.seq} failed read-back", e)
        } finally {
            runCatching { temp.delete() }
        }
    }

    // ── BackupController: set-up / everyday ───────────────────────────────────

    override suspend fun listDriveCandidates(): List<DriveCandidate> = withContext(Dispatchers.IO) {
        runCatching { DriveScan.candidates(dataDir()) }.getOrElse { DriveScan.logScanFailure("listing volumes", it); emptyList() }
    }

    override suspend fun pickFolder(title: String): String? = withContext(Dispatchers.Swing) {
        com.bnm.lab.report.pickFolder(title)
    }

    override suspend fun setUp(folder: String, licenceKey: String): Result<String> = locked {
        val storedFp = licenceFp()
        if (storedFp.isNullOrBlank()) error("Activate BNM Lab before setting up the backup pendrive")
        if (sha256Hex(BackupContainer.normaliseLicenceKey(licenceKey)) != storedFp) error("That is not this lab's licence key")
        val picked = File(folder)
        if (!picked.isDirectory) error("That folder does not exist")
        if (sameVolumeAsData(picked, dataDir())) error("That is this computer's own disk — a backup there is lost with it")
        val vault = DriveScan.vaultDirFor(picked)
        val existing = DriveScan.readMarker(vault)
        val rebind = existing != null && existing.id == prefs.vaultId && prefs.hasVault && prefs.recoveryCode != null
        if (existing != null && !rebind) {
            error("This pendrive already holds backups of ${existing.lab ?: "another lab"}. Use Restore, or choose another pendrive.")
        }
        if (existing == null && DriveScan.freeBytes(picked) < BackupPolicy.MIN_FREE_BYTES) error("Less than 1 GB free — use a bigger pendrive")

        val code: String
        if (rebind) {
            code = prefs.recoveryCode!!
            AppLog.i("Backup", "re-binding this computer's own pendrive")
        } else {
            code = RecoveryCode.generate()
            val vaultKeys = BackupContainer.newVault(licenceKey, code, kdfIterations)
            prefs.retireVault()
            prefs.vaultId = UUID.randomUUID().toString()
            prefs.dek = Base64.getEncoder().encodeToString(vaultKeys.dek)
            prefs.slots = backupJson.encodeToString(VaultSlots.serializer(), vaultKeys.slots)
            prefs.recoveryCode = code
            AppLog.i("Backup", "new vault created")
        }
        prefs.dir = vault.absolutePath
        DriveScan.writeMarker(vault, BackupMarker(
            id = prefs.vaultId!!, lab = labName(), created = DriveScan.nowIso(), app = "BNM Lab $appVersion",
            ownerDeviceHash = deviceId()?.let(DriveScan::ownerHash),
        ))
        prefs.ownerRebindPending = false
        movedOn = null
        dbProblem = false
        verifyFailed = false
        consecutiveFailures = 0
        failUntilNanos = 0L
        lastError = null
        present = true
        prefs.seq = maxOf(prefs.seq, DriveScan.maxSeq(vault))
        refreshDriveFacts(vault)
        prefs.flushNow()
        // The first generation, inside the wizard: "First backup done — 12 MB, 1,204 patients".
        dirty.markDirty()
        snapshotLocked(REASON_MANUAL).getOrThrow()
        RecoveryCode.format(code)
    }

    override suspend fun backupNow(): Result<Unit> = locked {
        if (prefs.retentionPaused) {
            prefs.retentionPaused = false
            if (lastError == MSG_SMALLER) lastError = null
        }
        failUntilNanos = 0L
        probeDrive(nanos())
        dirty.markDirty()
        snapshotLocked(REASON_MANUAL).getOrThrow()
    }

    override suspend fun stop(): Result<Unit> = locked {
        prefs.unbind()
        present = false
        movedOn = null
        lastError = null
        consecutiveFailures = 0
        AppLog.i("Backup", "backing up stopped by the operator (vault key kept)")
    }

    override suspend fun recoveryCode(): String? =
        if (prefs.hasVault) prefs.recoveryCode?.let(RecoveryCode::format) else null

    override suspend fun printBackupCard(): Result<Unit> = withContext(Dispatchers.IO) {
        val code = prefs.recoveryCode
            ?: return@withContext failure("The recovery code is not on this computer. Set up a new backup pendrive to get one.")
        BackupCard.renderAndOpen(labName(), code, prefs.vaultId ?: "")
    }

    override suspend fun purgeDrive(): Result<Unit> = locked {
        val vault = vaultDir() ?: error("Backup pendrive is not set up")
        if (!DriveScan.markerPresent(vault)) error("The backup pendrive is not connected")
        var deleted = 0
        DriveScan.snapshotsDir(vault).listFiles { d -> d.isDirectory }?.forEach { month ->
            month.listFiles()?.forEach { f ->
                if (f.isFile) {
                    DriveIo.retry("delete generation") { Files.deleteIfExists(f.toPath()) }
                    deleted++
                }
            }
            runCatching { month.delete() }
        }
        prefs.lastOkAt = null
        prefs.lastVerifiedAt = null
        prefs.lastCounts = null
        refreshDriveFacts(vault)
        // The lab goes on: the next tick writes a fresh generation of what remains.
        dirty.markDirty()
        AppLog.w("Backup", "all $deleted generation(s) deleted from the pendrive by the owner")
    }

    // ── BackupController: restore / move ──────────────────────────────────────

    override suspend fun findBackupsAt(folder: String): Result<List<BackupGeneration>> = withContext(Dispatchers.IO) {
        runCatching {
            val vault = DriveScan.findVault(File(folder)) ?: error("No BNM Lab backups were found there")
            DriveScan.listGenerations(vault)
        }
    }

    override suspend fun preview(gen: BackupGeneration, unlock: Unlock): Result<RestorePreview> = withContext(Dispatchers.IO) {
        runCatching { previewOf(gen, unlock).first }
    }

    private fun previewOf(gen: BackupGeneration, unlock: Unlock): Pair<RestorePreview, ByteArray> {
        val file = File(gen.path)
        if (gen.damaged || !file.isFile) error("That backup is damaged and cannot be restored")
        val (header, headerBytes) = BackupContainer.readHeader(file)
        val dek = BackupContainer.unlock(header, unlock, dekBytes()) ?: error(
            when (unlock) {
                is Unlock.ThisPc -> "This backup was not made with this computer's backup key"
                is Unlock.LicenceKey -> "That licence key does not open this backup"
                is Unlock.RecoveryCode -> "That recovery code does not open this backup"
            },
        )
        val head = file.inputStream().buffered(256 * 1024).use { raw ->
            BackupContainer.readHeader(raw)
            BackupBundle.readHead(BackupContainer.decryptingStream(raw, dek, headerBytes, header.noncePrefix))
        }
        val m = head.manifest
        val here = dbFile?.let { runCatching { BackupSnapshot.countsOf(it) }.getOrNull() } ?: BackupCounts()
        val preview = RestorePreview(
            labName = m.labName ?: header.lab ?: "this lab",
            createdAt = runCatching { ZonedDateTime.parse(m.createdAt).toInstant().toEpochMilli() }.getOrDefault(gen.createdAt),
            seq = m.seq, appVersion = m.appVersion,
            patients = m.counts.patients.toInt(), orders = m.counts.orders.toInt(), results = m.counts.results.toInt(),
            staff = m.counts.staff.toInt(), tests = m.counts.tests.toInt(),
            sameLicence = RestoreGuards.sameLicence(licenceFp(), m.labLicenseFp),
            newerThanThisApp = RestoreGuards.isNewer(m.appVersion, appVersion),
            currentPatientsHere = here.patients.toInt(),
        )
        return preview to dek
    }

    override suspend fun stageRestore(gen: BackupGeneration, unlock: Unlock): Result<RestoreStaged> = locked {
        val (preview, dek) = previewOf(gen, unlock)
        if (preview.newerThanThisApp) error("This backup was made by a newer BNM Lab. Update BNM Lab first.")
        if (preview.sameLicence == false) error("This backup belongs to a different lab licence")
        val notes = ArrayList<String>()

        // What is on this PC right now goes to the pendrive first, when there is one.
        if (prefs.isBound) {
            probeDrive(nanos())
            if (present && movedOn == null) {
                snapshotLocked(REASON_BEFORE_RESTORE).onFailure {
                    notes += "The current records could not be backed up before the restore; they are set aside on this computer."
                }
            }
        }
        if (prefs.lastCounts != null) prefs.expectDrop = true

        val db = dbFile ?: File(dataDir(), CHAT_DB_NAME)
        val pending = RestoreStaging.pendingFile(db)
        val part = File(pending.parentFile, pending.name + ".part")
        part.delete()
        val file = File(gen.path)
        val (header, stream) = BackupContainer.open(file, dek)
        val head = stream.use { BackupBundle.extract(it, part) }
        val verdict = BackupSnapshot.quickCheck(part)
        if (verdict != "ok") {
            part.delete()
            error("The backup could not be read back — try an older one")
        }
        DriveIo.move(part.toPath(), pending.toPath())

        notes += RestoreStaging.applyCarriedPrefs(head.prefs, prefs.store)
        prefs.restoredFrom = head.manifest.previousDeviceId ?: "unknown"
        prefs.restoredAt = wall()

        // Adopt the vault this generation came from.
        val vault = DriveScan.vaultOf(gen.path)?.takeIf { DriveScan.markerPresent(it) }
        val sameVault = prefs.vaultId == header.backupId
        prefs.vaultId = header.backupId
        prefs.dek = Base64.getEncoder().encodeToString(dek)
        prefs.slots = backupJson.encodeToString(VaultSlots.serializer(), header.vaultSlots)
        prefs.recoveryCode = when (unlock) {
            is Unlock.RecoveryCode -> RecoveryCode.normalise(unlock.code)
            else -> if (sameVault) prefs.recoveryCode else null
        }
        if (vault != null) {
            prefs.dir = vault.absolutePath
            prefs.seq = maxOf(prefs.seq, DriveScan.maxSeq(vault))
            prefs.ownerRebindPending = true
        }
        prefs.retentionPaused = false
        dirty.reset()
        RestoreStaging.writeAppliedMarker(db, gen.seq)
        prefs.flushNow()
        AppLog.i("Backup", "restore of generation ${gen.seq} staged for the next launch")
        publish()
        RestoreStaged(notes)
    }

    override suspend fun restoreOfferAtLaunch(): BackupGeneration? = withContext(Dispatchers.IO) {
        runCatching {
            if (!prefs.isBound) return@runCatching null
            val vault = vaultDir() ?: return@runCatching null
            val id = prefs.vaultId ?: return@runCatching null
            if (!DriveScan.markerMatches(vault, id)) return@runCatching null
            val db = dbFile
            val here = if (db == null || !db.exists()) BackupCounts() else BackupSnapshot.countsOf(db)
            if (here.patients + here.orders + here.results + here.staff > 0) return@runCatching null
            DriveScan.listGenerations(vault).firstOrNull { !it.damaged }
        }.getOrNull()
    }

    override fun closeApp() {
        AppLog.i("Lifecycle", "closing after a staged restore")
        runCatching { prefs.flushNow() }
        exitApp()
    }

    // ── BackupController: lifecycle hooks ─────────────────────────────────────

    override suspend fun flushOnExit(maxWaitMs: Long): Boolean {
        if (!dirty.isDirty) return true
        if (!prefs.isBound) return false
        // The snapshot cannot be interrupted; past the deadline the window closes
        // and the shutdown hook removes the half-written file.
        return withTimeoutOrNull(maxWaitMs) {
            withContext(Dispatchers.IO) {
                engineLock.withLock {
                    probeDrive(nanos())
                    if (!present || movedOn != null) false
                    else snapshotLocked(REASON_CLOSE).isSuccess && !dirty.isDirty
                }
            }
        } ?: false
    }

    override suspend fun beforeTenantWipe() {
        withContext(Dispatchers.IO) {
            engineLock.withLock {
                if (prefs.isBound) {
                    probeDrive(nanos())
                    if (present && movedOn == null) snapshotLocked(REASON_BEFORE_WIPE)
                }
                prefs.expectDrop = true
            }
        }
    }

    override suspend fun afterTenantWipe() {
        withContext(Dispatchers.IO) {
            engineLock.withLock {
                prefs.retireVault()
                dirty.reset()
                present = false
                movedOn = null
                lastError = null
                consecutiveFailures = 0
                generations = 0
                bytesOnDrive = 0L
                driveName = null
                AppLog.i("Backup", "vault retired after the tenant wipe — the next lab sets up its own pendrive")
                publish()
            }
        }
    }

    override fun dismissBannerForSession() {
        bannerDismissed = true
        publish()
    }

    /** The Licence page's "Register this computer" succeeded online: the amber restore notice can go. Not on the interface. */
    fun markRegisteredOnline() {
        prefs.restoredFrom = null
        prefs.restoredAt = null
        publish()
    }

    // ── status ────────────────────────────────────────────────────────────────

    private fun publish() {
        val bound = prefs.isBound
        val phase = when {
            !bound -> BackupStatus.Phase.NOT_SET_UP
            dbProblem -> BackupStatus.Phase.DB_PROBLEM
            working -> BackupStatus.Phase.WORKING
            movedOn != null -> BackupStatus.Phase.FAILING
            !present -> BackupStatus.Phase.DRIVE_MISSING
            consecutiveFailures > 0 || prefs.retentionPaused || verifyFailed -> BackupStatus.Phase.FAILING
            dirty.isDirty -> BackupStatus.Phase.PENDING
            else -> BackupStatus.Phase.OK
        }
        val error = when (phase) {
            BackupStatus.Phase.DB_PROBLEM -> MSG_DB_PROBLEM
            BackupStatus.Phase.FAILING -> movedOn?.let(::movedMessage) ?: lastError ?: (if (prefs.retentionPaused) MSG_SMALLER else null)
            else -> null
        }
        _status.value = BackupStatus(
            phase = phase,
            driveName = if (bound) driveName else null,
            lastOkAt = prefs.lastOkAt,
            lastVerifiedAt = prefs.lastVerifiedAt,
            dirtySince = if (bound) dirty.dirtySinceWall else null,
            generations = generations,
            bytesOnDrive = bytesOnDrive,
            lastError = error,
            retentionPaused = prefs.retentionPaused,
            restoredFromBackup = prefs.restoredFrom != null,
            bannerDismissed = bannerDismissed,
        )
    }

    private fun refreshDriveFacts(vault: File) {
        runCatching {
            val files = DriveScan.generationFiles(vault)
            generations = files.size
            bytesOnDrive = files.sumOf { it.length() }
            driveName = DriveScan.displayName(volumeOf(vault))
        }
    }

    /** Counts and flags only — this goes into the emailed support report. */
    private fun diagnostics(): String {
        val s = _status.value
        return "phase=${s.phase} setUp=${prefs.isBound} present=$present generations=$generations " +
            "bytesOnDrive=${bytesOnDrive / 1024} KB seq=${prefs.seq}\n" +
            "lastOk=${prefs.lastOkAt?.let(::iso)} lastVerified=${prefs.lastVerifiedAt?.let(::iso)} " +
            "dirtySince=${dirty.dirtySinceWall?.let(::iso)}\n" +
            "writeErrors=${prefs.writeErrors} consecutiveFailures=$consecutiveFailures retentionPaused=${prefs.retentionPaused} " +
            "dbProblem=$dbProblem verifyFailed=$verifyFailed movedToAnotherPc=${movedOn != null}\n" +
            "restoredFrom=${prefs.restoredFrom?.take(8)} ownerRebindPending=${prefs.ownerRebindPending} " +
            "lastError=${s.lastError}"
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Every operator-driven operation: serialised against the tick, and the status republished whatever happened. */
    private suspend fun <T> locked(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        engineLock.withLock {
            try {
                runCatching(block).onFailure { AppLog.w("Backup", "operation failed: ${it::class.simpleName}: ${it.message}") }
            } finally {
                publish()
            }
        }
    }

    private fun <T> failure(message: String): Result<T> = Result.failure(IllegalStateException(message))

    private fun vaultDir(): File? = prefs.dir?.let(::File)
    private fun stagingFile(): File = File(File(dataDir(), "backup"), "staging.db")
    private fun dekBytes(): ByteArray? = prefs.dek?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    private fun vaultSlots(): VaultSlots? = prefs.slots?.let { runCatching { backupJson.decodeFromString(VaultSlots.serializer(), it) }.getOrNull() }
    private fun labName(): String? = prefs.store.getStringOrNull("license_lab_name")?.takeIf { it.isNotBlank() }
    private fun licenceFp(): String? = prefs.store.getStringOrNull("lab_license_fp")?.takeIf { it.isNotBlank() }
    private fun deviceId(): String? = prefs.store.getStringOrNull("license_device_id")?.takeIf { it.isNotBlank() }

    /** The `ed` claim of the stored licence token — no verification, display only. */
    private fun edition(): String? {
        val jwt = prefs.store.getStringOrNull("license_jwt") ?: return null
        return runCatching {
            var p = jwt.split(".")[1].replace('-', '+').replace('_', '/')
            while (p.length % 4 != 0) p += "="
            Json.parseToJsonElement(String(Base64.getDecoder().decode(p), Charsets.UTF_8)).jsonObject["ed"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun movedMessage(created: String): String =
        "These records moved to another computer on ${runCatching { ZonedDateTime.parse(created).format(DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrDefault(created)}. " +
            "Set up a new backup pendrive here, or use Restore."

    private fun volumeOf(vault: File): File {
        val p = vault.absoluteFile.toPath()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> (p.root ?: p).toFile()
            os.contains("mac") && p.nameCount >= 2 && p.getName(0).toString() == "Volumes" -> p.root.resolve(p.subpath(0, 2)).toFile()
            p.nameCount >= 3 && (p.getName(0).toString() == "media" || p.getName(0).toString() == "mnt") -> p.root.resolve(p.subpath(0, 3)).toFile()
            p.nameCount >= 4 && p.getName(0).toString() == "run" -> p.root.resolve(p.subpath(0, 4)).toFile()
            else -> vault.parentFile ?: vault
        }
    }

    private fun ms(millis: Long): Long = millis * 1_000_000L
    private fun today(): String = LocalDate.now().toString()
    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString()
    private fun clock(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
}
