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
 * ## PIN storage — read this before trusting it with anything
 * A PIN is stored as a **salted SHA-256**: `s1$<salt>$<sha256(salt + ":" + pin)>`,
 * where `<salt>` is this install's random salt ([StaffPrefs.pinSalt]) captured at
 * the moment the PIN was set. The salt travels INSIDE the stored value — that's
 * deliberate: pin_hash syncs between the lab's seats, so a PIN set on the front
 * desk PC has to verify on the pathologist's laptop, which has a different
 * install salt of its own.
 *
 * **This is convenience access control for a shared lab PC, NOT a security
 * boundary.** The SQLite file sits on the same machine as the app and is fully
 * readable; a 4-digit PIN space is trivially brute-forced offline by anyone
 * holding that file. Its job is to stop the receptionist from approving results
 * under the pathologist's name by accident — nothing more. Never treat a PIN as
 * proof of identity for anything with legal weight beyond the report's own
 * attribution trail, and never reuse this hashing for real secrets.
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

    /** Create or edit. `pinHash` is carried through untouched — change a PIN via
     *  [setPin] so the caller never has to know the hash format. */
    suspend fun upsert(staff: Staff): Staff = withContext(Dispatchers.Default) {
        val now = nowIso()
        val existing = sQ.byId(staff.id).executeAsOneOrNull()
        val saved = staff.copy(
            id = staff.id.ifBlank { newId() },
            name = staff.name.trim(),
            createdAt = existing?.created_at ?: staff.createdAt.ifBlank { now },
            updatedAt = now,
        )
        sQ.upsert(saved.id, saved.name, saved.role, saved.pinHash, if (saved.active) 1L else 0L,
            saved.createdAt, saved.updatedAt, saved.deletedAt)
        saved
    }

    /** Set (non-blank) or clear (null/blank) a person's PIN. */
    suspend fun setPin(staffId: String, pin: String?): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val row = sQ.byId(staffId).executeAsOneOrNull() ?: error("Staff not found: $staffId")
            val clean = pin?.trim().orEmpty()
            require(clean.isEmpty() || clean.length >= MIN_PIN) { "PIN must be at least $MIN_PIN digits" }
            val hash = if (clean.isEmpty()) null else hashPin(clean, prefs.pinSalt)
            sQ.upsert(row.id, row.name, row.role, hash, row.active, row.created_at, nowIso(), row.deleted_at)
            Unit
        }
    }

    /**
     * True when [pin] matches — and also true when the person has NO PIN at all
     * (tap-to-enter is the sign-in contract for a PIN-less account). Unknown
     * staff id ⇒ false.
     */
    suspend fun verifyPin(staffId: String, pin: String): Boolean = withContext(Dispatchers.Default) {
        val row = sQ.byId(staffId).executeAsOneOrNull() ?: return@withContext false
        val stored = row.pin_hash
        if (stored.isNullOrBlank()) return@withContext true
        verifyAgainst(pin.trim(), stored)
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
                row.created_at, nowIso(), row.deleted_at)
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
        sQ.upsert(owner.id, owner.name, owner.role, null, 1L, owner.createdAt, owner.updatedAt, null)
        owner
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun StaffRow.toModel() = Staff(
        id = id, name = name, role = role, pinHash = pin_hash, active = active == 1L,
        createdAt = created_at, updatedAt = updated_at, deletedAt = deleted_at,
    )

    private fun newId(): String = Uuid.random().toString()

    private fun nowIso(): String = kotlin.time.Clock.System.now().toString()

    companion object {
        /** Stable id for the anti-lockout owner — the same row on every seat. */
        const val DEFAULT_OWNER_ID = "staff-default-owner"
        const val MIN_PIN = 4

        private const val SCHEME = "s1"

        /** `s1$<salt>$<sha256(salt:pin)>` — salt embedded so other seats verify. */
        internal fun hashPin(pin: String, salt: String): String =
            "$SCHEME\$$salt\$${sha256Hex("$salt:$pin")}"

        internal fun verifyAgainst(pin: String, stored: String): Boolean {
            val parts = stored.split('$')
            if (parts.size != 3 || parts[0] != SCHEME) return false
            return sha256Hex("${parts[1]}:$pin") == parts[2]
        }
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
