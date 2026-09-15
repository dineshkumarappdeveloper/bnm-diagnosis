package com.bnm.lab.backup

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `.bnmlab` container is the whole promise: a generation on a pendrive that
 * fell into the wrong hands says nothing without the licence key or the card,
 * and a generation that was cut short or bit-flipped is REFUSED rather than
 * restored as a database with silent holes in it.
 */
class BackupContainerTest {

    private val key = "BNMD-2345-6789-ABCD-EFGH"
    private val code = RecoveryCode.generate()
    private val iter = 1_000 // 600k in production; the count travels in the header
    private val vault = BackupContainer.newVault(key, code, iter)

    private fun header(): ContainerHeader = ContainerHeader(
        backupId = "vault-1", lab = "Test Lab", created = "2026-09-15T09:12:00+05:30", seq = 7,
        counts = BackupCounts(patients = 1204, orders = 3410, results = 22118, staff = 6, tests = 223),
        appVersion = "1.2.0", kdf = vault.slots.kdf, slots = vault.slots.slots, check = vault.slots.check,
        noncePrefix = BackupContainer.newNoncePrefix(),
    )

    private fun write(payload: ByteArray, chunk: Int = BackupPolicy.CHUNK_BYTES, headerOnly: Boolean = false): File {
        val f = Files.createTempFile("gen", ".bnmlab").toFile().apply { deleteOnExit() }
        val h = header()
        f.outputStream().buffered().use { raw ->
            val hb = BackupContainer.writeHeader(raw, h)
            if (!headerOnly) BackupContainer.encryptingStream(raw, vault.dek, hb, h.noncePrefix, chunk).use { it.write(payload) }
            raw.flush()
        }
        return f
    }

    private fun readAll(f: File, dek: ByteArray = vault.dek, chunk: Int = BackupPolicy.CHUNK_BYTES): ByteArray =
        BackupContainer.open(f, dek, chunk).second.use { it.readBytes() }

    private fun random(n: Int) = ByteArray(n).also { Random(n).nextBytes(it) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `round trip through either slot - licence key or recovery code`() {
        val payload = random(300_000)
        val f = write(payload)
        val (h, _) = BackupContainer.readHeader(f)
        val viaKey = BackupContainer.unlock(h, Unlock.LicenceKey(" bnmd-2345-6789-abcd-efgh "), null)
        val viaCode = BackupContainer.unlock(h, Unlock.RecoveryCode(RecoveryCode.format(code).lowercase()), null)
        assertNotNull(viaKey); assertNotNull(viaCode)
        assertContentEquals(vault.dek, viaKey)
        assertContentEquals(vault.dek, viaCode)
        assertContentEquals(payload, BackupContainer.open(f, viaKey).second.use { it.readBytes() })
        assertContentEquals(payload, BackupContainer.open(f, viaCode).second.use { it.readBytes() })
        assertEquals(payload.size.toLong(), BackupContainer.verifyFile(f, vault.dek))
    }

    @Test
    fun `a wrong key, wrong code or foreign vault key is refused before any chunk is read`() {
        val f = write(ByteArray(0), headerOnly = true) // header only: touching a chunk would throw "cut short"
        val (h, _) = BackupContainer.readHeader(f)
        assertNull(BackupContainer.unlock(h, Unlock.LicenceKey("BNMD-0000-0000-0000-0000"), null))
        assertNull(BackupContainer.unlock(h, Unlock.RecoveryCode(RecoveryCode.generate()), null))
        assertNull(BackupContainer.unlock(h, Unlock.RecoveryCode("not a code"), null))
        assertNull(BackupContainer.unlock(h, Unlock.ThisPc, random(32)))
        assertNull(BackupContainer.unlock(h, Unlock.ThisPc, null))
        val refused = assertFailsWith<BackupDamagedException> { BackupContainer.open(f, random(32)) }
        assertTrue(refused.message!!.contains("does not open"), refused.message)
    }

    @Test
    fun `the header is readable before unlocking - the restore list shows lab, date and counts`() {
        val f = write(random(10))
        val (h, _) = BackupContainer.readHeader(f)
        assertEquals("Test Lab", h.lab)
        assertEquals(7, h.seq)
        assertEquals(1204, h.counts.patients)
        assertEquals("1.2.0", h.appVersion)
        assertEquals(iter, h.kdf.iter)
    }

    @Test
    fun `a flipped byte inside a chunk fails the tag`() {
        val payload = random(20_000)
        val f = write(payload, chunk = 4096)
        val bytes = f.readBytes()
        val at = bytes.size - 2_000 // well inside the ciphertext, past the header
        bytes[at] = (bytes[at].toInt() xor 0x01).toByte()
        f.writeBytes(bytes)
        assertFailsWith<BackupDamagedException> { readAll(f, chunk = 4096) }
    }

    @Test
    fun `a file cut at a chunk boundary or mid-chunk is damaged, never a shorter database`() {
        val chunk = 4096
        val payload = random(chunk * 2 + chunk / 2) // 3 chunks: full, full, half (flagged last)
        val f = write(payload, chunk = chunk)
        val whole = f.readBytes()
        val headerLen = f.inputStream().buffered().use { BackupContainer.readHeader(it) }.second.size + 8 + 1 + 4
        val chunkOnDisk = 4 + chunk + 16
        // Exactly two whole chunks remain: the second was written with last=0, so it cannot verify as the last.
        f.writeBytes(whole.copyOf(headerLen + 2 * chunkOnDisk))
        assertFailsWith<BackupDamagedException> { readAll(f, chunk = chunk) }
        // Mid-chunk.
        f.writeBytes(whole.copyOf(headerLen + chunkOnDisk + 100))
        assertFailsWith<BackupDamagedException> { readAll(f, chunk = chunk) }
        // Nothing after the header at all.
        f.writeBytes(whole.copyOf(headerLen))
        assertFailsWith<BackupDamagedException> { readAll(f, chunk = chunk) }
        // And the untouched file still reads back whole.
        f.writeBytes(whole)
        assertContentEquals(payload, readAll(f, chunk = chunk))
    }

    @Test
    fun `payloads on and around the chunk boundary round-trip`() {
        val mib = BackupPolicy.CHUNK_BYTES
        for (size in listOf(0, 1, mib, mib + 1, mib * 3 + mib / 2)) {
            val payload = random(size)
            val f = write(payload)
            val back = readAll(f)
            assertEquals(size, back.size, "size $size")
            assertEquals(sha(payload), sha(back), "size $size")
            assertEquals(size.toLong(), BackupContainer.verifyFile(f, vault.dek), "verify $size")
            f.delete()
        }
    }

    @Test
    fun `reading is stream-based - nothing beyond one chunk is ever held`() {
        val payload = random(9 * BackupPolicy.CHUNK_BYTES + 12_345)
        val f = write(payload)
        val (_, stream) = BackupContainer.open(f, vault.dek)
        val reader = stream as ChunkedGcmInputStream
        val md = MessageDigest.getInstance("SHA-256")
        reader.use {
            val buf = ByteArray(8192)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        assertEquals(sha(payload), md.digest().joinToString("") { "%02x".format(it) })
        assertTrue(reader.maxBuffered <= 2 * BackupPolicy.CHUNK_BYTES + 16, "held ${reader.maxBuffered} bytes")
    }

    @Test
    fun `the bundle inside carries manifest, prefs and the database in that order`() {
        val db = Files.createTempFile("snap", ".db").toFile().apply { deleteOnExit(); writeBytes(random(50_000)) }
        val manifest = BackupManifest(
            appVersion = "1.2.0", createdAt = "2026-09-15T09:12:00+05:30", seq = 7, reason = "manual",
            labName = "Test Lab", backupId = "vault-1", previousDeviceId = "dev-old", dbBytes = db.length(),
            counts = BackupCounts(patients = 3),
        )
        val prefs = mapOf("report_lh_address" to "12 Main Rd", "license_jwt" to "x.y.z")
        val f = Files.createTempFile("gen", ".bnmlab").toFile().apply { deleteOnExit() }
        val h = header()
        f.outputStream().buffered().use { raw ->
            val hb = BackupContainer.writeHeader(raw, h)
            val enc = BackupContainer.encryptingStream(raw, vault.dek, hb, h.noncePrefix)
            BackupBundle.write(enc, manifest, prefs, db)
            enc.close()
        }
        val head = BackupContainer.open(f, vault.dek).second.use { BackupBundle.readHead(it) }
        assertEquals(manifest, head.manifest)
        assertEquals(prefs, head.prefs)
        val out = Files.createTempFile("restored", ".db").toFile().apply { deleteOnExit() }
        val full = BackupContainer.open(f, vault.dek).second.use { BackupBundle.extract(it, out) }
        assertEquals("dev-old", full.manifest.previousDeviceId)
        assertContentEquals(db.readBytes(), out.readBytes())
    }
}
