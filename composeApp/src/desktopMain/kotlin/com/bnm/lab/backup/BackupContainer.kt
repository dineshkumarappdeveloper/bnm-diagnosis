package com.bnm.lab.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** JSON for every backup structure: lenient on the way in (a newer build may add fields). */
internal val backupJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Row counts a generation carries in its plaintext header and its manifest. */
@Serializable
internal data class BackupCounts(
    val patients: Long = 0,
    val orders: Long = 0,
    val results: Long = 0,
    val staff: Long = 0,
    val tests: Long = 0,
)

/**
 * The vault's key material as it appears in every container header: how the
 * two unlock secrets are stretched, the data key wrapped under each, and a
 * short check that tells a wrong secret from a damaged file before any chunk
 * is touched. Regenerated per vault (set-up), persisted in prefs on the lab PC.
 */
@Serializable
internal data class VaultSlots(
    val kdf: Kdf,
    val slots: Slots,
    val check: String,
) {
    @Serializable
    data class Kdf(
        val alg: String = "PBKDF2WithHmacSHA256",
        val iter: Int,
        @SerialName("salt_licence") val saltLicence: String,
        @SerialName("salt_code") val saltCode: String,
    )

    @Serializable
    data class Slots(val licence: String, val code: String)
}

/** The plaintext header — non-PHI, readable BEFORE unlocking (the restore list shows it). */
@Serializable
internal data class ContainerHeader(
    val v: Int = BackupContainer.VERSION,
    @SerialName("backup_id") val backupId: String,
    val lab: String? = null,
    val created: String,
    val seq: Long,
    val counts: BackupCounts = BackupCounts(),
    @SerialName("app_version") val appVersion: String,
    val kdf: VaultSlots.Kdf,
    val slots: VaultSlots.Slots,
    val check: String,
    @SerialName("nonce_prefix") val noncePrefix: String,
) {
    val vaultSlots: VaultSlots get() = VaultSlots(kdf, slots, check)
}

/** The file is not a readable generation: bad magic, cut short, or a chunk that fails its check. */
internal class BackupDamagedException(message: String) : IOException(message)

/**
 * The `.bnmlab` container: `"BNMLABBK"` + version byte + length-prefixed
 * header JSON, then chunks of AES-256-GCM ciphertext (`int32 BE length`,
 * `ciphertext||tag`), 1 MiB of plaintext per chunk.
 *
 * Each chunk is bound to its position and to the header: nonce =
 * `nonce_prefix(4) || chunk index (int64 BE)`, AAD = `SHA-256(header bytes) ||
 * chunk index || last flag`. The last chunk is flagged, so a file cut at a
 * chunk boundary is detected the same way as one cut mid-chunk. Nothing here
 * loads a payload into memory: encryption is an [OutputStream], decryption an
 * [InputStream], each holding one chunk.
 *
 * Two unlock secrets wrap the one data key: the lab's licence key and the
 * recovery code, each stretched with PBKDF2 (600k iterations in production;
 * the count travels in the header so tests can run with fewer). Pure — no
 * preferences, no files of its own; [BackupService] owns those.
 */
internal object BackupContainer {
    const val VERSION = 2
    private val MAGIC = "BNMLABBK".toByteArray(Charsets.US_ASCII)
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val DEK_BYTES = 32
    private const val NONCE_BYTES = 12
    private val CHECK_LABEL = "bnm-lab-backup-check".toByteArray(Charsets.US_ASCII)

    // ── the vault ──

    class Vault(val dek: ByteArray, val slots: VaultSlots)

    /** A fresh data key wrapped under both secrets. [recoveryCode] must already be normalised. */
    fun newVault(
        licenceKey: String,
        recoveryCode: String,
        iterations: Int = BackupPolicy.KDF_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): Vault {
        val dek = ByteArray(DEK_BYTES).also { random.nextBytes(it) }
        val saltLicence = ByteArray(16).also { random.nextBytes(it) }
        val saltCode = ByteArray(16).also { random.nextBytes(it) }
        val kekLicence = kdf(normaliseLicenceKey(licenceKey), saltLicence, iterations)
        val kekCode = kdf(recoveryCode, saltCode, iterations)
        val slots = VaultSlots(
            kdf = VaultSlots.Kdf(iter = iterations, saltLicence = b64(saltLicence), saltCode = b64(saltCode)),
            slots = VaultSlots.Slots(licence = b64(wrap(dek, kekLicence, random)), code = b64(wrap(dek, kekCode, random))),
            check = checkOf(dek),
        )
        return Vault(dek, slots)
    }

    /** The data key, or null when [secret] does not open this header. Never touches a chunk. */
    fun unlock(header: ContainerHeader, secret: Unlock, thisPcDek: ByteArray?): ByteArray? {
        val dek = when (secret) {
            is Unlock.ThisPc -> thisPcDek
            is Unlock.LicenceKey -> {
                val kek = kdf(normaliseLicenceKey(secret.key), unb64(header.kdf.saltLicence), header.kdf.iter)
                unwrap(unb64(header.slots.licence), kek)
            }
            is Unlock.RecoveryCode -> {
                val code = RecoveryCode.normalise(secret.code) ?: return null
                val kek = kdf(code, unb64(header.kdf.saltCode), header.kdf.iter)
                unwrap(unb64(header.slots.code), kek)
            }
        } ?: return null
        return dek.takeIf { verifyDek(header, it) }
    }

    /** Does [dek] belong to this header? The check is what tells a wrong key from a damaged file. */
    fun verifyDek(header: ContainerHeader, dek: ByteArray): Boolean =
        dek.size == DEK_BYTES && MessageDigest.isEqual(checkOf(dek).toByteArray(), header.check.toByteArray())

    fun normaliseLicenceKey(key: String): String = key.trim().uppercase()

    fun newNoncePrefix(random: SecureRandom = SecureRandom()): String = b64(ByteArray(4).also { random.nextBytes(it) })

    // ── writing ──

    /** Magic, version and the header; returns the header bytes the chunks are bound to. */
    fun writeHeader(out: OutputStream, header: ContainerHeader): ByteArray {
        val bytes = backupJson.encodeToString(ContainerHeader.serializer(), header).toByteArray(Charsets.UTF_8)
        out.write(MAGIC)
        out.write(VERSION)
        out.write(int32(bytes.size))
        out.write(bytes)
        return bytes
    }

    /**
     * An encrypting stream over [out]. Closing it writes the flagged last chunk
     * and FLUSHES [out] without closing it, so the caller can `force()` the
     * channel before the file is closed.
     */
    fun encryptingStream(
        out: OutputStream,
        dek: ByteArray,
        headerBytes: ByteArray,
        noncePrefixB64: String,
        chunkBytes: Int = BackupPolicy.CHUNK_BYTES,
    ): OutputStream = ChunkedGcmOutputStream(out, dek, sha256(headerBytes), unb64(noncePrefixB64), chunkBytes)

    // ── reading ──

    /** The header from the start of [input], with the raw bytes the chunks are bound to. */
    fun readHeader(input: InputStream): Pair<ContainerHeader, ByteArray> {
        val magic = ByteArray(MAGIC.size)
        if (readFully(input, magic) < magic.size || !magic.contentEquals(MAGIC)) {
            throw BackupDamagedException("not a BNM Lab backup")
        }
        val version = input.read()
        if (version != VERSION) throw BackupDamagedException("backup format $version is not readable by this build")
        val lenBytes = ByteArray(4)
        if (readFully(input, lenBytes) < 4) throw BackupDamagedException("header cut short")
        val len = int32(lenBytes)
        if (len <= 0 || len > MAX_HEADER_BYTES) throw BackupDamagedException("header length $len")
        val bytes = ByteArray(len)
        if (readFully(input, bytes) < len) throw BackupDamagedException("header cut short")
        val header = try {
            backupJson.decodeFromString(ContainerHeader.serializer(), String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupDamagedException("header unreadable")
        }
        return header to bytes
    }

    fun readHeader(file: File): Pair<ContainerHeader, ByteArray> =
        BufferedInputStream(file.inputStream(), 16 * 1024).use { readHeader(it) }

    /** A decrypting stream positioned after the header (call right after [readHeader] on the same [input]). */
    fun decryptingStream(
        input: InputStream,
        dek: ByteArray,
        headerBytes: ByteArray,
        noncePrefixB64: String,
        chunkBytes: Int = BackupPolicy.CHUNK_BYTES,
    ): ChunkedGcmInputStream = ChunkedGcmInputStream(input, dek, sha256(headerBytes), unb64(noncePrefixB64), chunkBytes)

    /** Header + plaintext stream of [file]; the caller closes the stream. Throws when [dek] is not the file's key. */
    fun open(file: File, dek: ByteArray, chunkBytes: Int = BackupPolicy.CHUNK_BYTES): Pair<ContainerHeader, InputStream> {
        val raw = BufferedInputStream(file.inputStream(), 256 * 1024)
        try {
            val (header, bytes) = readHeader(raw)
            if (!verifyDek(header, dek)) throw BackupDamagedException("this key does not open the backup")
            return header to decryptingStream(raw, dek, bytes, header.noncePrefix, chunkBytes)
        } catch (e: Throwable) {
            raw.close()
            throw e
        }
    }

    /** Read every chunk of [file] back and check its tag; returns the plaintext byte count. */
    fun verifyFile(file: File, dek: ByteArray, chunkBytes: Int = BackupPolicy.CHUNK_BYTES): Long {
        val (_, stream) = open(file, dek, chunkBytes)
        stream.use {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
            }
            return total
        }
    }

    // ── primitives ──

    private fun kdf(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun wrap(dek: ByteArray, kek: ByteArray, random: SecureRandom): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return nonce + cipher.doFinal(dek)
    }

    private fun unwrap(slot: ByteArray, kek: ByteArray): ByteArray? {
        if (slot.size != NONCE_BYTES + DEK_BYTES + TAG_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, slot, 0, NONCE_BYTES))
        return try {
            cipher.doFinal(slot, NONCE_BYTES, slot.size - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            null
        }
    }

    private fun checkOf(dek: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(CHECK_LABEL)
        md.update(dek)
        return b64(md.digest().copyOf(8))
    }

    internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    internal fun unb64(s: String): ByteArray = try {
        Base64.getDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        throw BackupDamagedException("header field is not base64")
    }

    internal fun int32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    internal fun int32(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    internal fun int64(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

    /** Reads until [buf] is full or EOF; returns the count read. */
    internal fun readFully(input: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    internal fun nonceFor(prefix: ByteArray, index: Long): ByteArray = prefix + int64(index)
    internal fun aadFor(headerHash: ByteArray, index: Long, last: Boolean): ByteArray =
        headerHash + int64(index) + byteArrayOf(if (last) 1 else 0)
}

/** Buffers one plaintext chunk; a full chunk is written only when MORE data follows it, so the last one is always the flagged one. */
private class ChunkedGcmOutputStream(
    private val out: OutputStream,
    dek: ByteArray,
    private val headerHash: ByteArray,
    private val noncePrefix: ByteArray,
    chunkBytes: Int,
) : OutputStream() {
    private val key = SecretKeySpec(dek, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val buf = ByteArray(chunkBytes)
    private var len = 0
    private var index = 0L
    private var closed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, n: Int) {
        check(!closed) { "stream closed" }
        var o = off
        var remaining = n
        while (remaining > 0) {
            if (len == buf.size) emit(last = false)
            val take = minOf(remaining, buf.size - len)
            System.arraycopy(b, o, buf, len, take)
            len += take; o += take; remaining -= take
        }
    }

    private fun emit(last: Boolean) {
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, BackupContainer.nonceFor(noncePrefix, index)))
        cipher.updateAAD(BackupContainer.aadFor(headerHash, index, last))
        val ct = cipher.doFinal(buf, 0, len)
        out.write(BackupContainer.int32(ct.size))
        out.write(ct)
        index++
        len = 0
    }

    override fun flush() = out.flush()

    /** Writes the flagged last chunk and flushes; the underlying stream stays open for the caller to force and close. */
    override fun close() {
        if (closed) return
        closed = true
        emit(last = true)
        out.flush()
    }
}

/** Serves one decrypted chunk at a time; [maxBuffered] lets a test confirm nothing larger is ever held. */
internal class ChunkedGcmInputStream(
    input: InputStream,
    dek: ByteArray,
    private val headerHash: ByteArray,
    private val noncePrefix: ByteArray,
    private val chunkBytes: Int,
) : InputStream() {
    private val src = PushbackInputStream(input, 4)
    private val key = SecretKeySpec(dek, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var plain = ByteArray(0)
    private var pos = 0
    private var index = 0L
    private var done = false
    var maxBuffered = 0
        private set

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, n: Int): Int {
        if (n == 0) return 0
        while (pos >= plain.size) {
            if (done) return -1
            fill()
        }
        val take = minOf(n, plain.size - pos)
        System.arraycopy(plain, pos, b, off, take)
        pos += take
        return take
    }

    private fun fill() {
        val lenBytes = ByteArray(4)
        val got = BackupContainer.readFully(src, lenBytes)
        if (got < 4) throw BackupDamagedException(if (index == 0L) "no data after the header" else "cut short at chunk $index")
        val len = BackupContainer.int32(lenBytes)
        if (len < 16 || len > chunkBytes + 16) throw BackupDamagedException("chunk $index has an impossible length")
        val ct = ByteArray(len)
        if (BackupContainer.readFully(src, ct) < len) throw BackupDamagedException("cut short inside chunk $index")
        // The last chunk is the one nothing follows; its flag is part of what the tag covers.
        val peek = src.read()
        val last = peek == -1
        if (!last) src.unread(peek)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, BackupContainer.nonceFor(noncePrefix, index)))
        cipher.updateAAD(BackupContainer.aadFor(headerHash, index, last))
        plain = try {
            cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw BackupDamagedException("chunk $index failed its check")
        }
        maxBuffered = maxOf(maxBuffered, ct.size + plain.size)
        pos = 0
        index++
        done = last
    }

    override fun close() = src.close()
}
