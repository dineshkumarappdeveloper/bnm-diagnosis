package com.bnm.diagnosis.staff

import kotlinx.serialization.Serializable

/**
 * Lab staff account (P4). The SQLDelight `staff` table is the system of record;
 * this is the typed view the app works with — and the wire format the sync
 * spine pushes to `lab_entities` (entity = "staff").
 *
 * Retirement is `active = false`, never a delete: the person's NAME is stamped
 * on every result they entered/verified/approved and that attribution has to
 * stay readable for the life of the report.
 */
@Serializable
data class Staff(
    val id: String,
    val name: String,
    val role: String = StaffRole.RECEPTIONIST,
    /** Salted SHA-256 (`s1$<salt>$<hex>`); null/blank = no PIN, tap to enter. */
    val pinHash: String? = null,
    val active: Boolean = true,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
    /** Typed login id. Case-insensitive, unique among live staff; null = the
     *  original tap-a-tile sign-in, which keeps working. */
    val username: String? = null,
    /** Base64 PNG of the approver's signature, drawn into the report sign-off
     *  block. Base64 not a path — this row syncs to the lab's other seats. */
    val signaturePng: String? = null,
    val qualifications: String? = null,   // 'MD (Pathology)'
    val registrationNo: String? = null,   // state medical-council number
) {
    /**
     * Which scheme [pinHash] currently holds. A person carries exactly ONE
     * secret — setting a password replaces a PIN and vice versa — because the
     * column is one column and a second credential would just be a second way
     * in, not a second factor.
     *
     * [StaffCredential.NONE] is decided by the column being EMPTY, never by the
     * scheme being unrecognised: a row synced down from a seat running a newer
     * build must fail closed ([StaffCredential.UNREADABLE]), not fall through to
     * tap-to-enter.
     */
    val credential: StaffCredential get() = when {
        pinHash.isNullOrBlank() -> StaffCredential.NONE
        SecretHash.schemeOf(pinHash) == SecretHash.PIN -> StaffCredential.PIN
        SecretHash.schemeOf(pinHash) == SecretHash.PASSWORD -> StaffCredential.PASSWORD
        else -> StaffCredential.UNREADABLE
    }

    val hasPin: Boolean get() = credential == StaffCredential.PIN
    val hasPassword: Boolean get() = credential == StaffCredential.PASSWORD

    /** Something must be proved before this tile opens. `false` ⇒ tap-to-enter —
     *  and, by [StaffRepository.verifyLogin], NO typed sign-in either. */
    val hasSecret: Boolean get() = credential != StaffCredential.NONE

    val hasLogin: Boolean get() = !username.isNullOrBlank()
    val hasSignature: Boolean get() = !signaturePng.isNullOrBlank()

    /**
     * Money surfaces: commission statements, payouts and B2B rate lists.
     *
     * The lab owner's round-1 ask was explicit — an employee "won't see the
     * commission and other controllers, he/she can only do work related". So
     * this is deliberately owner-only: a pathologist signs reports but does not
     * need to see what the lab pays its referring doctors.
     */
    val canSeeMoney: Boolean get() = role == StaffRole.OWNER

    /** Catalog prices are a money surface too — they set what patients are charged. */
    val canEditCatalog: Boolean get() = role == StaffRole.OWNER

    /** Pathologist sign-off is the legally meaningful one — owner included so a
     *  single-person lab is never stuck (an owner IS the responsible person). */
    val canApprove: Boolean get() = role == StaffRole.PATHOLOGIST || role == StaffRole.OWNER

    /** Adding/editing/retiring staff is the owner's job alone. */
    val canManageStaff: Boolean get() = role == StaffRole.OWNER

    val roleLabel: String get() = StaffRole.label(role)
}

/** The four roles. Stored as the raw string so an unknown value from a newer
 *  seat degrades to "least privilege" instead of crashing the sign-in grid. */
object StaffRole {
    const val OWNER = "owner"
    const val PATHOLOGIST = "pathologist"
    const val TECHNICIAN = "technician"
    const val RECEPTIONIST = "receptionist"

    /** Ordered most → least privileged (drives the role dropdown). */
    val ALL = listOf(OWNER, PATHOLOGIST, TECHNICIAN, RECEPTIONIST)

    fun label(role: String): String = when (role) {
        OWNER -> "Owner"
        PATHOLOGIST -> "Pathologist"
        TECHNICIAN -> "Technician"
        RECEPTIONIST -> "Receptionist"
        else -> role.replaceFirstChar { it.uppercase() }
    }

    /** One-line "what this person may do", shown under the role dropdown. */
    fun describe(role: String): String = when (role) {
        OWNER -> "Everything — commission, prices, staff & roles included."
        PATHOLOGIST -> "Approves results and signs reports. No commission or prices."
        TECHNICIAN -> "Enters and verifies results. No commission or prices."
        RECEPTIONIST -> "Registers patients, orders and bills. No commission or prices."
        else -> "Registers patients, orders and bills. No commission or prices."
    }
}


/** How a person proves who they are at the sign-in gate. */
enum class StaffCredential {
    /** No secret: the tile is tap-to-enter. Cannot be signed into by username. */
    NONE,
    /** Numeric PIN, entered on the pad (`s1`). */
    PIN,
    /** Alphanumeric password, typed (`s2`) — the round-1 employee-login ask. */
    PASSWORD,

    /**
     * A secret this build cannot read — a scheme minted by a newer seat and
     * synced down. It never verifies, so the person is locked out of THIS seat
     * until the owner resets them; that is the safe direction to fail, and the
     * lab's data stays fully readable on every other account meanwhile.
     */
    UNREADABLE,
}
