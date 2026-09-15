package com.bnm.lab.report

import com.bnm.lab.staff.StaffRole

/**
 * The names a report may print under "Verified by" and "Approved by".
 *
 * A fresh install seeds one account named "Lab Owner" so the first person can
 * tap in. That is a placeholder, not a person, and not a qualification: a
 * report is signed by a named technician and a named pathologist. When the owner
 * is also the lab's pathologist, the paper must carry their name — never
 * "Owner", which says nothing about who signed and reads as if the business,
 * not a doctor, released the result.
 */
object Signatory {
    /** The seeded account's name (StaffRepository.seedOwnerIfEmpty). */
    const val PLACEHOLDER_NAME = "Lab Owner"

    /** A name that stands for a role rather than a person. */
    fun isPlaceholder(name: String?): Boolean {
        val n = name?.trim()?.lowercase() ?: return false
        return n == PLACEHOLDER_NAME.lowercase() || n == StaffRole.label(StaffRole.OWNER).lowercase()
    }

    /** The name to print, or null when there is no real name to put on the report. */
    fun printable(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() && !isPlaceholder(it) }

    /**
     * Why an owner cannot be marked as the pathologist with [name] yet, or null.
     * Their name is what the report prints as the approving pathologist.
     */
    fun pathologistNameProblem(name: String): String? = when {
        name.isBlank() -> "Enter the pathologist's name as it should appear on reports"
        isPlaceholder(name) -> "Reports print the approving pathologist's name — enter yours " +
            "(for example \"Dr. Meena Iyer\") instead of \"${name.trim()}\""
        else -> null
    }
}
