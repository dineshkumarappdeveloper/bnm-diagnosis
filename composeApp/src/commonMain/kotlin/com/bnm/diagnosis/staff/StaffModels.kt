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
) {
    val hasPin: Boolean get() = !pinHash.isNullOrBlank()

    /** Pathologist sign-off is the legally meaningful one — owner included so a
     *  single-person lab is never stuck (an owner IS the responsible person). */
    val canApprove: Boolean get() = role == StaffRole.PATHOLOGIST || role == StaffRole.OWNER

    /** Technician+ verifies; registration/billing is open to every role. */
    val canVerify: Boolean get() = role != StaffRole.RECEPTIONIST

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
        OWNER -> "Everything, including staff & roles."
        PATHOLOGIST -> "Approves results and signs reports."
        TECHNICIAN -> "Enters and verifies results."
        RECEPTIONIST -> "Registers patients, orders and bills."
        else -> "Registers patients, orders and bills."
    }
}
