package com.bnm.diagnosis.staff

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.db.Staff as StaffRow
import com.russhwolf.settings.Settings
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Staff accounts + local RBAC (P4). Offline-first like the rest of the LIMS:
 * the `staff` table is the system of record, sign-in never touches the network,
 * and the sync spine only converges the same rows across the lab's other seats.
 *
 * ## Credential storage — read this before trusting it with anything
 * A person holds ONE secret in `pin_hash`, in one of two formats owned by
 * [SecretHash]: a numeric PIN (`s1`) or a typed password (`s2`). Both embed the
 * install salt ([StaffPrefs.pinSalt]) INSIDE the stored value — deliberate,
 * because pin_hash syncs between the lab's seats and a credential set on the
 * front-desk PC has to verify on the pathologist's laptop, which has a salt of
 * its own.
 *
 * **This is convenience access control for a shared lab PC, NOT a security
 * boundary.** The SQLite file sits on the same machine as the app and is fully
 * readable; a 4-digit PIN space is trivially brute-forced offline by anyone
 * holding that file, and `s2`'s iterated SHA-256 only makes a weak password slow
 * rather than safe. Its job is to stop the receptionist from approving results
 * under the pathologist's name by accident, and to keep the commission screens
 * off a technician's seat — nothing more. Never treat either as proof of
 * identity for anything with legal weight beyond the report's own attribution
 * trail, and never reuse this hashing for real secrets.
 *
 * ## Sign-in paths
 * Two, both offline and both landing on the same [Staff]:
 *  - **tap a tile** ([verifyPin]) — a PIN-less account opens on the tap, which is
 *    the documented contract for a shared bench PC where the person is standing
 *    at the machine.
 *  - **type a username** ([verifyLogin]) — which REQUIRES a real secret. A named
 *    account with no credential is refused rather than inheriting tap-to-enter;
 *    otherwise adding a username would quietly turn "no PIN" into "anyone who
 *    knows the name is that person".
 *
 * ## Anti-lockout
 * [seedOwnerIfEmpty] guarantees a fresh (or fully-retired) install always has one
 * `owner` with NO PIN, so nobody can ever be locked out of their own lab.
 */
@OptIn(ExperimentalUuidApi::class)
class StaffRepository(
    private val db: AppDatabase,
    @Suppress("unused") private val json: Json, // ctor parity with LabRepository/LabSyncEngine
    private val prefs: StaffPrefs = StaffPrefs(),
) {
    private val sQ get() = db.staffQueries

    /**
     * Consecutive failed sign-ins ON THIS SEAT, since the app started. Drives
     * [throttle]. One scalar rather than a per-account map: it also slows down
     * someone walking the username list, and there is nothing to keep in sync.
     *
     * Not a lockout — a lockout on an offline lab PC locks the lab out of its own
     * data, and the SQLite file is readable anyway. It is a speed bump against
     * standing at the keyboard guessing.
     */
    private var consecutiveFailures = 0

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Sign-in grid: people who can currently work, live. */
    fun listActiveFlow(): Flow<List<Staff>> =
        sQ.listActive().asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toModel() } }
            .catch { emit(emptyList()) }

    /** Staff management (owner-only): retired people included, listed last. */
    fun listAllFlow(): Flow<List<Staff>> =
        sQ.listAll().asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toModel() } }
            .catch { emit(emptyList()) }

    suspend fun listActive(): List<Staff> = withContext(Dispatchers.Default) {
        sQ.listActive().executeAsList().map { it.toModel() }
    }

    suspend fun listAll(): List<Staff> = withContext(Dispatchers.Default) {
        sQ.listAll().executeAsList().map { it.toModel() }
    }

    suspend fun byId(id: String): Staff? = withContext(Dispatchers.Default) {
        sQ.byId(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun countActive(): Long = withContext(Dispatchers.Default) {
        sQ.countActive().executeAsOne()
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Create or edit. `pinHash` is carried through untouched — change a secret
     * via [setPin] / [setPassword] / [clearCredential] so the caller never has
     * to know the hash format.
     *
     * THROWS on an invalid or already-taken username: `staff.username` has no
     * UNIQUE index behind it (SQLite cannot add one to a table every P4 lab
     * already has), so this method is the only thing standing between two people
     * and the same login. Call [save] from UI code to get that as a `Result`.
     */
    suspend fun upsert(staff: Staff): Staff = withContext(Dispatchers.Default) {
        val now = nowIso()
        val id = staff.id.ifBlank { newId() }
        val existing = sQ.byId(id).executeAsOneOrNull()
        val saved = staff.copy(
            id = id,
            name = staff.name.trim(),
            username = cleanUsername(staff.username, id),
            createdAt = existing?.created_at ?: staff.createdAt.ifBlank { now },
            updatedAt = now,
        )
        sQ.upsert(saved.id, saved.name, saved.role, saved.pinHash, if (saved.active) 1L else 0L,
            saved.createdAt, saved.updatedAt, saved.deletedAt,
            saved.username, saved.signaturePng, saved.qualifications, saved.registrationNo)
        saved
    }

    /** [upsert] with its validation surfaced instead of thrown — the UI path. */
    suspend fun save(staff: Staff): Result<Staff> = runCatching { upsert(staff) }

    /** Set (non-blank) or clear (null/blank) a person's numeric PIN. */
    suspend fun setPin(staffId: String, pin: String?): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val clean = pin?.trim().orEmpty()
            require(clean.isEmpty() || clean.length >= MIN_PIN) { "PIN must be at least $MIN_PIN digits" }
            writeSecret(staffId, if (clean.isEmpty()) null else SecretHash.hashPin(clean, prefs.pinSalt))
        }
    }

    /**
     * Set a typed password. Replaces any PIN the person had: one column, one
     * secret (see [Staff.credential]). Passwords are alphanumeric — no digit
     * filtering anywhere on this path.
     */
    suspend fun setPassword(staffId: String, password: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            // Not trimmed: a trailing space someone deliberately typed is part of
            // the password, and trimming it here would fail every later sign-in.
            require(password.length >= MIN_PASSWORD) { "Password must be at least $MIN_PASSWORD characters" }
            writeSecret(staffId, SecretHash.hashPassword(password, prefs.pinSalt))
        }
    }

    /** Back to tap-to-enter. Refused while the person still has a username — see
     *  [writeSecret]. */
    suspend fun clearCredential(staffId: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching { writeSecret(staffId, null) }
    }

    /**
     * True when [pin] matches the person's secret (PIN or password — [SecretHash]
     * dispatches on the stored scheme), and also true when they have NO secret at
     * all: tap-to-enter is the sign-in contract for a credential-less tile.
     * Unknown staff id ⇒ false.
     */
    suspend fun verifyPin(staffId: String, pin: String): Boolean = withContext(Dispatchers.Default) {
        val row = sQ.byId(staffId).executeAsOneOrNull() ?: return@withContext false
        val stored = row.pin_hash
        if (stored.isNullOrBlank()) return@withContext true
        throttle()
        SecretHash.verify(pin.trim(), stored).also { record(it) }
    }

    /**
     * Typed sign-in: the employee login the lab owner asked for. Returns the
     * person on success, null on any failure — caller shows ONE message for both
     * a wrong name and a wrong password, so the form does not confirm which
     * usernames exist.
     *
     * Zero network, by construction: `byUsername` is a local SELECT and
     * [SecretHash] is pure commonMain. This works on a lab PC that has never
     * seen the internet, forever.
     *
     * Unlike [verifyPin] a missing secret is a REFUSAL, not an open door — see
     * this class's KDoc.
     */
    suspend fun verifyLogin(username: String, password: String): Staff? = withContext(Dispatchers.Default) {
        val name = normalizeUsername(username) ?: return@withContext null
        throttle()
        val row = sQ.byUsername(name).executeAsOneOrNull()
        val stored = row?.pin_hash
        val ok = row != null && !stored.isNullOrBlank() && SecretHash.verify(password, stored)
        record(ok)
        if (ok && row != null) row.toModel() else null
    }

    /** Retire / un-retire. NEVER a delete — the person's name has to stay
     *  readable on every result they signed. */
    suspend fun setActive(staffId: String, active: Boolean): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val row = sQ.byId(staffId).executeAsOneOrNull() ?: error("Staff not found: $staffId")
            if (!active && row.role == StaffRole.OWNER && sQ.countActive().executeAsOne() <= 1L) {
                error("Can't deactivate the last active account — add another owner first")
            }
            sQ.upsert(row.id, row.name, row.role, row.pin_hash, if (active) 1L else 0L,
                row.created_at, nowIso(), row.deleted_at,
                row.username, row.signature_png, row.qualifications, row.registration_no)
            Unit
        }
    }

    /** Tombstone (sync-side removal only — the UI deactivates instead). */
    suspend fun softDelete(staffId: String) = withContext(Dispatchers.Default) {
        val now = nowIso()
        sQ.softDelete(now, now, staffId)
    }

    /**
     * Anti-lockout seed: when no active account exists (fresh install, or the
     * DB predates P4), create ONE `owner` named "Lab Owner" with **no PIN** so
     * the first person to open the app simply taps in and sets the lab up.
     *
     * The row uses a STABLE id so multi-seat labs converge on a single default
     * owner instead of each seat minting its own. Returns the seeded row, or
     * null when the table already had someone.
     *
     * [labName] is accepted for context (a future seed may personalise the
     * account); today the licence already carries the lab identity, so the row
     * is always plain "Lab Owner".
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun seedOwnerIfEmpty(labName: String): Staff? = withContext(Dispatchers.Default) {
        if (sQ.countActive().executeAsOne() > 0L) return@withContext null
        val now = nowIso()
        val owner = Staff(
            id = DEFAULT_OWNER_ID, name = "Lab Owner", role = StaffRole.OWNER,
            pinHash = null, active = true, createdAt = now, updatedAt = now,
        )
        sQ.upsert(owner.id, owner.name, owner.role, null, 1L, owner.createdAt, owner.updatedAt, null,
            null, null, null, null)
        owner
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Swap a person's secret without disturbing anything else on the row.
     *
     * Clearing (`hash == null`) is refused while they hold a username: tap-to-
     * enter is a property of the TILE, and a named account with no secret would
     * be an account with a published login and nothing behind it. [verifyLogin]
     * refuses such a row anyway; this stops the owner from creating one at all.
     */
    private fun writeSecret(staffId: String, hash: String?) {
        val row = sQ.byId(staffId).executeAsOneOrNull() ?: error("Staff not found: $staffId")
        require(hash != null || row.username.isNullOrBlank()) {
            "Remove ${row.name}'s username first — a username with no password is a login anyone could use"
        }
        sQ.upsert(row.id, row.name, row.role, hash, row.active, row.created_at, nowIso(), row.deleted_at,
            row.username, row.signature_png, row.qualifications, row.registration_no)
    }

    /**
     * Normalise, validate and de-duplicate a username. Blank ⇒ null (tile-only
     * sign-in, still fully supported). Throws with a message meant for the owner
     * setting the account up.
     */
    private fun cleanUsername(raw: String?, selfId: String): String? {
        val v = raw?.trim()?.lowercase().orEmpty()
        if (v.isEmpty()) return null
        usernameProblem(v)?.let { error(it) }
        require(sQ.countUsername(v, selfId).executeAsOne() == 0L) { "Username \"$v\" is already taken" }
        return v
    }

    /** Growing pause after failures — see [consecutiveFailures]. */
    private suspend fun throttle() {
        val n = consecutiveFailures
        if (n > 0) delay(minOf(n.toLong() * FAIL_DELAY_STEP_MS, FAIL_DELAY_MAX_MS))
    }

    private fun record(success: Boolean) {
        consecutiveFailures = if (success) 0 else consecutiveFailures + 1
    }

    private fun StaffRow.toModel() = Staff(
        id = id, name = name, role = role, pinHash = pin_hash, active = active == 1L,
        createdAt = created_at, updatedAt = updated_at, deletedAt = deleted_at,
        username = username,
        signaturePng = signature_png,
        qualifications = qualifications,
        registrationNo = registration_no,
    )

    private fun newId(): String = Uuid.random().toString()

    private fun nowIso(): String = kotlin.time.Clock.System.now().toString()

    companion object {
        /** Stable id for the anti-lockout owner — the same row on every seat. */
        const val DEFAULT_OWNER_ID = "staff-default-owner"
        const val MIN_PIN = 4

        /** Six, not eight: a lab password is typed at a bench many times a day
         *  and this is not a security boundary (see the class KDoc). Long enough
         *  that `s2`'s stretching is worth something, short enough to be used. */
        const val MIN_PASSWORD = 6

        const val MIN_USERNAME = 3
        const val MAX_USERNAME = 32

        /** Milliseconds added per consecutive failure, and the ceiling. */
        private const val FAIL_DELAY_STEP_MS = 400L
        private const val FAIL_DELAY_MAX_MS = 4_000L

        /**
         * Case-folded and trimmed, or null when it is not a usable username.
         * Case-folding matters twice: `countUsername` compares `lower()` on both
         * sides, and someone typing "Asha" at 7am must reach the row they created
         * as "asha".
         */
        fun normalizeUsername(raw: String?): String? =
            raw?.trim()?.lowercase()?.takeIf { USERNAME_SHAPE.matches(it) }

        /** Human explanation, or null when [raw] is fine (blank included: a blank
         *  username simply means "tile sign-in only"). */
        fun usernameProblem(raw: String?): String? {
            val v = raw?.trim()?.lowercase().orEmpty()
            return when {
                v.isEmpty() -> null
                v.length < MIN_USERNAME -> "Username needs at least $MIN_USERNAME characters"
                v.length > MAX_USERNAME -> "Username can be at most $MAX_USERNAME characters"
                !USERNAME_SHAPE.matches(v) ->
                    "Use letters, numbers, dot, dash or underscore — starting with a letter or number"
                else -> null
            }
        }

        /** Hoisted: a Regex rebuilt per keystroke would recompile on every edit. */
        private val USERNAME_SHAPE = Regex("^[a-z0-9][a-z0-9._-]{2,31}$")
    }
}

/**
 * Per-install staff prefs (multiplatform-settings, same store as the other
 * prefs holders). The salt is generated ONCE per install and then embedded in
 * every PIN hash this device writes — see [StaffRepository]'s KDoc for why it
 * has to travel with the hash rather than stay device-private.
 */
class StaffPrefs {
    private val s: Settings = Settings()

    val pinSalt: String
        get() = s.getStringOrNull(K_SALT)?.takeIf { it.isNotBlank() } ?: newSalt().also {
            s.putString(K_SALT, it)
        }

    private fun newSalt(): String {
        val b = ByteArray(8).also { Random.nextBytes(it) }
        return b.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    private companion object {
        const val K_SALT = "staff_pin_salt"
    }
}
