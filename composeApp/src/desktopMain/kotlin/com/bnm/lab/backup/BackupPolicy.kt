package com.bnm.lab.backup

/**
 * Every timing, size and count the Backup pendrive engine decides by, in one
 * place. Intervals are compared on `System.nanoTime()` (an NTP step at boot or
 * a dead CMOS battery must not stall or fire a timer); only what is SHOWN to
 * staff uses the wall clock.
 */
internal object BackupPolicy {
    /** The engine thread wakes this often; everything else is a multiple of it. */
    const val TICK_MS = 5_000L

    // ── when a dirty database becomes a generation ──
    /** No write for this long ⇒ the operator has paused; snapshot now. */
    const val QUIET_MS = 20_000L
    /** Continuous writing (an analyzer batch) still gets a generation this often. */
    const val MAX_DIRTY_MS = 120_000L
    /** Belt and braces: a dirty database never waits longer than this. */
    const val SAFETY_MS = 15 * 60_000L
    /** Letterhead/printer/prefix edits are preferences, not database writes. */
    const val PREFS_HASH_MS = 60_000L
    /** While the pendrive is absent, look for it under another drive letter this often. */
    const val ROOT_SCAN_MS = 30_000L
    /** Shortly after launch: if the newest generation is older than a day, write one regardless. */
    const val CATCH_UP_AFTER_START_MS = 2 * 60_000L
    const val CATCH_UP_STALE_MS = 24 * 3_600_000L
    /** Read the newest generation back once a day, starting a few minutes after launch. */
    const val VERIFY_AFTER_START_MS = 5 * 60_000L

    // ── failure handling ──
    /** After a failed write, wait this long before the next attempt: 1 / 5 / 15 min. */
    val RETRY_BACKOFF_MS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L)
    /** This many failures in a row and the advice becomes "replace the pendrive". */
    const val REPLACE_AFTER_FAILURES = 3
    /** Each file operation on the pendrive is retried this many times, this far apart. */
    const val DRIVE_RETRY_TIMES = 10
    const val DRIVE_RETRY_DELAY_MS = 500L

    // ── the snapshot ──
    /** `VACUUM INTO` holds a read lock; app writes wait on their own busy_timeout (10 s). */
    const val SNAPSHOT_BUSY_TIMEOUT_MS = 15_000
    /** A snapshot slower than this is logged as a warning — a sign of a huge database or a struggling disk. */
    const val SLOW_SNAPSHOT_MS = 5_000L
    /** Patients / orders / results shrinking by more than this against the previous generation pauses retention. */
    const val ROW_DROP_GUARD_PERCENT = 20

    // ── retention (by generation number and filename date only) ──
    const val KEEP_NEWEST = 12
    const val KEEP_DAILY_DAYS = 30
    const val KEEP_MONTHLY_MONTHS = 12
    /** Never prune below this many generations, whatever the tiers say. */
    const val KEEP_FLOOR = 3
    /** A `.part` or `.bad` older than this is a leftover of a dead process, not a write in progress. */
    const val STALE_PART_MS = 10 * 60_000L

    // ── the pendrive ──
    const val MIN_FREE_BYTES = 1L shl 30

    // ── the container ──
    /** Plaintext bytes per encrypted chunk. */
    const val CHUNK_BYTES = 1 shl 20
    const val KDF_ITERATIONS = 600_000
}
