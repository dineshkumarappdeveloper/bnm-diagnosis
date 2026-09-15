package com.bnm.lab.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a generation says about itself, inside the ciphertext. Counts only — never a record. */
@Serializable
internal data class BackupManifest(
    val format: Int = 1,
    @SerialName("app_version") val appVersion: String,
    @SerialName("created_at") val createdAt: String,
    val seq: Long,
    /** "scheduled" | "manual" | "close" | "before-tenant-wipe" | "before-restore" */
    val reason: String,
    @SerialName("lab_name") val labName: String? = null,
    val edition: String? = null,
    @SerialName("lab_license_fp") val labLicenseFp: String? = null,
    @SerialName("backup_id") val backupId: String,
    /** The writing PC's device id — a restored PC records it as where it came from. */
    @SerialName("previous_device_id") val previousDeviceId: String? = null,
    @SerialName("db_bytes") val dbBytes: Long = 0,
    val counts: BackupCounts = BackupCounts(),
)

/**
 * The plaintext inside a container: a ZIP with `manifest.json`, `prefs.json`
 * (the allow-listed preferences, see [BackupAllowList]) and the database
 * snapshot `bnm_chat.db`, in that order — so a restore preview reads the
 * manifest and stops without streaming the database.
 */
internal object BackupBundle {
    const val MANIFEST = "manifest.json"
    const val PREFS = "prefs.json"
    const val DB = "bnm_chat.db"
    private val prefsSerializer = MapSerializer(String.serializer(), String.serializer())

    fun encodePrefs(prefs: Map<String, String>): String = backupJson.encodeToString(prefsSerializer, prefs)

    /** Writes the whole bundle and releases the zip's deflater; [out] itself is left open for the caller. */
    fun write(out: OutputStream, manifest: BackupManifest, prefs: Map<String, String>, db: File) {
        // FilterOutputStream's bulk write is byte-at-a-time unless overridden.
        val keepOpen = object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = out.flush()
        }
        ZipOutputStream(keepOpen).use { zip ->
            zip.setLevel(6)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(backupJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PREFS))
            zip.write(backupJson.encodeToString(prefsSerializer, prefs).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DB))
            db.inputStream().buffered(256 * 1024).use { it.copyTo(zip, 256 * 1024) }
            zip.closeEntry()
            zip.finish()
        }
    }

    class Head(val manifest: BackupManifest, val prefs: Map<String, String>)

    /** Manifest and prefs only; returns before the database entry is read. */
    fun readHead(input: InputStream): Head {
        val zip = ZipInputStream(input)
        var manifest: BackupManifest? = null
        var prefs: Map<String, String>? = null
        while (true) {
            val e = zip.nextEntry ?: break
            when (e.name) {
                MANIFEST -> manifest = backupJson.decodeFromString(BackupManifest.serializer(), zip.readBytes().toString(Charsets.UTF_8))
                PREFS -> prefs = backupJson.decodeFromString(prefsSerializer, zip.readBytes().toString(Charsets.UTF_8))
                else -> break
            }
            if (manifest != null && prefs != null) break
        }
        return Head(manifest ?: throw BackupDamagedException("no manifest"), prefs ?: emptyMap())
    }

    /** Everything: the head, and the database streamed into [dbTarget]. */
    fun extract(input: InputStream, dbTarget: File): Head {
        val zip = ZipInputStream(input)
        var manifest: BackupManifest? = null
        var prefs: Map<String, String> = emptyMap()
        var dbWritten = false
        while (true) {
            val e = zip.nextEntry ?: break
            when (e.name) {
                MANIFEST -> manifest = backupJson.decodeFromString(BackupManifest.serializer(), zip.readBytes().toString(Charsets.UTF_8))
                PREFS -> prefs = backupJson.decodeFromString(prefsSerializer, zip.readBytes().toString(Charsets.UTF_8))
                DB -> {
                    dbTarget.outputStream().buffered(256 * 1024).use { zip.copyTo(it, 256 * 1024) }
                    dbWritten = true
                }
            }
        }
        if (!dbWritten) throw BackupDamagedException("no database in the backup")
        return Head(manifest ?: throw BackupDamagedException("no manifest"), prefs)
    }
}
