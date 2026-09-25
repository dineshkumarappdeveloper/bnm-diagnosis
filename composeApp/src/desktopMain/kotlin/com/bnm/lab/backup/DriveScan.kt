package com.bnm.lab.backup

import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.staff.sha256Hex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.FileStore
import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.swing.filechooser.FileSystemView

/**
 * `backup-id.json` at the root of the vault folder — how a pendrive is
 * recognised after a drive-letter shuffle, and how a stick that was adopted
 * by ANOTHER PC (through a restore) is told apart from this PC's own.
 */
@Serializable
internal data class BackupMarker(
    val id: String,
    val lab: String? = null,
    val created: String,
    val app: String,
    /** sha256(license_device_id)[0..16] of the PC that writes here; null before a device id exists. */
    @SerialName("owner_device_hash") val ownerDeviceHash: String? = null,
)

/**
 * Finding pendrives and reading what is on them. Nothing here needs more than
 * java.base and `javax.swing.filechooser` (java.desktop) — the packaged jlink
 * image has no WMI, no JNA, no java.management. "Removable" is therefore a
 * shape, not a fact: a FAT-family volume that is not the system disk.
 */
internal object DriveScan {
    private val FAT_TYPES = setOf("FAT", "FAT12", "FAT16", "FAT32", "EXFAT", "MSDOS", "VFAT")
    private val os = System.getProperty("os.name").orEmpty().lowercase()

    // ── layout ──

    /** The vault folder for a picked path: the folder itself when it already is one, else `BNM Lab Backup` inside it. */
    fun vaultDirFor(picked: File): File =
        if (picked.name == BackupNaming.VAULT_DIR) picked else File(picked, BackupNaming.VAULT_DIR)

    fun markerFile(vault: File): File = File(vault, BackupNaming.MARKER)
    fun snapshotsDir(vault: File): File = File(vault, BackupNaming.SNAPSHOTS_DIR)

    /** The vault a generation file lives in: `<vault>/snapshots/<month>/<file>`. */
    fun vaultOf(generationPath: String): File? = File(generationPath).parentFile?.parentFile?.parentFile

    // ── the marker ──

    fun readMarker(vault: File): BackupMarker? {
        val f = markerFile(vault)
        if (!Files.exists(f.toPath())) return null
        return runCatching { backupJson.decodeFromString(BackupMarker.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun writeMarker(vault: File, marker: BackupMarker) {
        DriveIo.retry("write marker") {
            Files.createDirectories(vault.toPath())
            val part = File(vault, BackupNaming.MARKER + ".part")
            part.writeText(backupJson.encodeToString(BackupMarker.serializer(), marker), Charsets.UTF_8)
            DriveIo.move(part.toPath(), markerFile(vault).toPath())
        }
    }

    /** The cheap per-tick probe: `Files.exists` answers fast (and false) on a yanked drive. */
    fun markerPresent(vault: File): Boolean = runCatching { Files.exists(markerFile(vault).toPath()) }.getOrDefault(false)

    fun markerMatches(vault: File, id: String): Boolean = markerPresent(vault) && readMarker(vault)?.id == id

    fun ownerHash(deviceId: String): String = sha256Hex(deviceId).take(16)

    fun nowIso(): String = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    // ── generations ──

    fun generationFiles(vault: File): List<File> {
        val months = snapshotsDir(vault).listFiles { f -> f.isDirectory } ?: return emptyList()
        return months.flatMap { m -> m.listFiles { f -> f.isFile && f.name.endsWith(BackupNaming.EXT) }?.toList() ?: emptyList() }
    }

    fun maxSeq(vault: File): Long =
        generationFiles(vault).mapNotNull { BackupNaming.parse(it.name)?.seq }.maxOrNull() ?: 0L

    /** Newest first by generation number; a file whose header cannot be read is listed damaged. */
    fun listGenerations(vault: File): List<BackupGeneration> =
        generationFiles(vault).map { toGeneration(it) }.sortedByDescending { it.seq }

    fun toGeneration(file: File): BackupGeneration {
        val parsed = BackupNaming.parse(file.name)
        val header = runCatching { BackupContainer.readHeader(file).first }.getOrNull()
        val createdAt = header?.created?.let { runCatching { ZonedDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            ?: parsed?.date?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: file.lastModified()
        return BackupGeneration(
            seq = header?.seq ?: parsed?.seq ?: 0L,
            createdAt = createdAt,
            bytes = file.length(),
            path = file.absolutePath,
            labName = header?.lab,
            damaged = header == null || parsed == null,
        )
    }

    fun bytesOnDrive(vault: File): Long = generationFiles(vault).sumOf { it.length() }

    // ── finding a vault ──

    /** The vault reachable from what an operator picked: the folder, `BNM Lab Backup` under it, its parent's, or the volume root's. */
    fun findVault(picked: File): File? {
        val tries = LinkedHashSet<File>()
        var f: File? = picked
        // A picked `snapshots` or month folder resolves up to its vault.
        while (f != null && (f.name == BackupNaming.SNAPSHOTS_DIR || (f.parentFile?.name == BackupNaming.SNAPSHOTS_DIR))) f = f.parentFile
        f?.let { tries += it }
        tries += vaultDirFor(picked)
        picked.parentFile?.let { tries += vaultDirFor(it) }
        picked.toPath().root?.toFile()?.let { tries += vaultDirFor(it) }
        return tries.firstOrNull { markerPresent(it) }
    }

    /** After a drive-letter shuffle: the bound vault under whichever root it now has. */
    fun findVaultById(id: String): File? =
        roots().asSequence().map { vaultDirFor(it) }.firstOrNull { runCatching { readMarker(it)?.id == id }.getOrDefault(false) }

    // ── volumes ──

    fun roots(): List<File> = when {
        os.contains("win") -> File.listRoots()?.toList() ?: emptyList()
        os.contains("mac") -> File("/Volumes").listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
        else -> listOf("/media", "/run/media", "/mnt").flatMap { base ->
            val dirs = File(base).listFiles()?.filter { it.isDirectory } ?: emptyList()
            // /media/<user>/<volume> on most desktops; /media/<volume> or /mnt/<volume> elsewhere.
            dirs.flatMap { d -> (d.listFiles()?.filter { it.isDirectory }?.takeIf { it.isNotEmpty() } ?: listOf(d)) }
        }
    }

    fun candidates(dataDir: File): List<DriveCandidate> {
        val dataStore = runCatching { Files.getFileStore(dataDir.toPath()) }.getOrNull()
        return roots().mapNotNull { root ->
            if (!root.isDirectory) return@mapNotNull null
            val store = runCatching { Files.getFileStore(root.toPath()) }.getOrNull()
            val type = store?.type()
            val same = sameVolume(root, store, dataDir, dataStore)
            DriveCandidate(
                path = root.absolutePath,
                displayName = displayName(root),
                fsType = type,
                totalBytes = runCatching { store?.totalSpace }.getOrNull() ?: root.totalSpace,
                freeBytes = runCatching { store?.usableSpace }.getOrNull() ?: root.usableSpace,
                removableLikely = type != null && type.uppercase() in FAT_TYPES && !same && !isSystemRoot(root),
                sameVolumeAsData = same,
                existingBackupOf = runCatching { readMarker(vaultDirFor(root))?.let { it.lab ?: "another lab" } }.getOrNull(),
            )
        }.sortedWith(compareByDescending<DriveCandidate> { it.removableLikely }.thenBy { it.sameVolumeAsData }.thenBy { it.displayName })
    }

    /** "That is this computer's own disk — a backup there is lost with it." */
    fun sameVolumeAsData(folder: File, dataDir: File): Boolean {
        val dataStore = runCatching { Files.getFileStore(dataDir.toPath()) }.getOrNull()
        val store = runCatching { Files.getFileStore(folder.toPath()) }.getOrNull()
        return sameVolume(folder, store, dataDir, dataStore)
    }

    private fun sameVolume(folder: File, store: FileStore?, dataDir: File, dataStore: FileStore?): Boolean {
        if (os.contains("win")) {
            val a = folder.absoluteFile.toPath().root?.toString()?.uppercase()
            val b = dataDir.absoluteFile.toPath().root?.toString()?.uppercase()
            if (a != null && b != null) return a == b
        }
        if (store == null || dataStore == null) return false
        val a = store.name().takeIf { it.isNotBlank() } ?: store.toString()
        val b = dataStore.name().takeIf { it.isNotBlank() } ?: dataStore.toString()
        return a == b
    }

    private fun isSystemRoot(root: File): Boolean = when {
        os.contains("win") -> {
            val sys = (System.getenv("SystemDrive") ?: "C:").trimEnd('\\') + "\\"
            root.absolutePath.equals(sys, ignoreCase = true)
        }
        else -> runCatching { Files.isSameFile(root.toPath(), File("/").toPath()) }.getOrDefault(false)
    }

    fun displayName(root: File): String {
        val fromOs = runCatching { FileSystemView.getFileSystemView().getSystemDisplayName(root) }.getOrNull()?.trim()
        if (!fromOs.isNullOrBlank()) return fromOs
        return root.name.ifBlank { root.absolutePath }
    }

    fun freeBytes(folder: File): Long = runCatching { Files.getFileStore(folder.toPath()).usableSpace }.getOrElse { folder.usableSpace }
    fun fsType(folder: File): String? = runCatching { Files.getFileStore(folder.toPath()).type() }.getOrNull()

    fun logScanFailure(what: String, e: Throwable) = AppLog.w("Backup", "$what failed: ${e::class.simpleName}")
}
