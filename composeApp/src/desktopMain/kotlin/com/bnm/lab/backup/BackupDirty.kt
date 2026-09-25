package com.bnm.lab.backup

import java.util.concurrent.atomic.AtomicLong

/**
 * "Something changed since the last generation" — belt and braces.
 *
 * Marked by the driver listener on every generated write (O(1), on the writing
 * thread: one compare-and-set, one volatile store — it must never slow a result
 * entry or an analyzer frame), by the engine's `PRAGMA data_version` probe
 * (raw writes the listener cannot see) and by the preferences hash. Cleared
 * only when a generation that INCLUDES every marked write is on the pendrive:
 * [clearIfNoWritesSince] compares the write counter captured before the
 * snapshot began.
 *
 * Intervals are monotonic ([nanos]); [dirtySinceWall] is what staff see. The
 * wall time is persisted on the clean→dirty transition only, so a crash before
 * the snapshot still shows "changes waiting" after relaunch without a
 * preference write per statement.
 */
internal class DirtyTracker(
    private val nanos: () -> Long,
    private val wall: () -> Long,
    private val persistDirtySince: (Long?) -> Unit = {},
) {
    private val dirtySinceNanos = AtomicLong(0L)
    @Volatile var lastWriteNanos: Long = 0L
        private set
    @Volatile var dirtySinceWall: Long? = null
        private set
    val writes = AtomicLong(0L)

    val isDirty: Boolean get() = dirtySinceNanos.get() != 0L

    fun markDirty() {
        val now = nanos().coerceAtLeast(1L)
        writes.incrementAndGet()
        lastWriteNanos = now
        if (dirtySinceNanos.compareAndSet(0L, now)) {
            val w = wall()
            dirtySinceWall = w
            runCatching { persistDirtySince(w) }
        }
    }

    /** A previous session left changes unsaved: carry its wall time so the chip says since when. */
    fun restore(dirtySinceWallMillis: Long) {
        val now = nanos().coerceAtLeast(1L)
        lastWriteNanos = now
        if (dirtySinceNanos.compareAndSet(0L, now)) dirtySinceWall = dirtySinceWallMillis
    }

    /** Quiet for [quietMs] since the last write, or dirty for [maxDirtyMs] regardless. */
    fun due(nowNanos: Long, quietMs: Long = BackupPolicy.QUIET_MS, maxDirtyMs: Long = BackupPolicy.MAX_DIRTY_MS): Boolean {
        val since = dirtySinceNanos.get()
        if (since == 0L) return false
        return nowNanos - lastWriteNanos >= quietMs * 1_000_000L || nowNanos - since >= maxDirtyMs * 1_000_000L
    }

    /**
     * After a successful generation: clean when nothing was written since
     * [writesBefore] was captured; otherwise the newer writes start a fresh
     * dirty period. Returns true when clean.
     */
    fun clearIfNoWritesSince(writesBefore: Long): Boolean {
        if (writes.get() == writesBefore) {
            dirtySinceNanos.set(0L)
            dirtySinceWall = null
            runCatching { persistDirtySince(null) }
            return true
        }
        val now = nanos().coerceAtLeast(1L)
        dirtySinceNanos.set(now)
        val w = wall()
        dirtySinceWall = w
        runCatching { persistDirtySince(w) }
        return false
    }

    /** The vault is gone (tenant wipe): nothing is "waiting" for a pendrive that no longer exists. */
    fun reset() {
        dirtySinceNanos.set(0L)
        dirtySinceWall = null
        runCatching { persistDirtySince(null) }
    }
}
