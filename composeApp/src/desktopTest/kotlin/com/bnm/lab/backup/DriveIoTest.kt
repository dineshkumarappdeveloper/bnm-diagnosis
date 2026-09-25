package com.bnm.lab.backup

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The pendrive retry: a sharing violation from an on-access scanner is
 * transient and worth another try; a chunk that fails its tag is the same
 * bytes on every read, and retrying it only delays the verdict — and used to
 * turn a one-off failed morning read-back into ten of them.
 */
class DriveIoTest {

    @Test
    fun `a sharing violation is retried until it clears`() {
        var calls = 0
        val v = DriveIo.retry("read", times = 5, delayMs = 1) {
            calls++
            if (calls < 3) throw IOException("The process cannot access the file") else "ok"
        }
        assertEquals("ok", v)
        assertEquals(3, calls)
    }

    @Test
    fun `a damaged file is not retried - the bytes will not change`() {
        var calls = 0
        assertFailsWith<BackupDamagedException> {
            DriveIo.retry("read", times = 5, delayMs = 1) { calls++; throw BackupDamagedException("chunk 3 failed its check") }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `an error that never clears is given up on after the last try`() {
        var calls = 0
        assertFailsWith<IOException> {
            DriveIo.retry("delete", times = 3, delayMs = 1) { calls++; throw IOException("still held") }
        }
        assertEquals(3, calls)
    }
}
