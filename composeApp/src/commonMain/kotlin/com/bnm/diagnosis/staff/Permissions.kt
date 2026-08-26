package com.bnm.diagnosis.staff

/**
 * The gated surfaces, as data rather than as scattered `role == "owner"` checks.
 *
 * Round-1 feedback item 2 asked for an employee "who won't see the commission
 * and other controllers — he/she can only do work related". That is one rule
 * with several front doors (a nav route, a tab, a price field), so the rule
 * lives here once and every door asks the same question via [allows].
 *
 * The denial copy travels with the permission on purpose: a person who lands on
 * a blocked surface should read *why* it is blocked, not a generic "forbidden".
 */
enum class LabPermission(val title: String, val explanation: String) {
    /** Commission %, payout statements, CSV export, and the B2B rate lists —
     *  a negotiated per-test price is a money surface just as much as a payout. */
    MONEY(
        title = "Commission & rates are owner-only",
        explanation = "Payout statements, commission percentages and negotiated " +
            "doctor rates are the lab owner's to see. Everything else about " +
            "referrers stays available from the registration desk.",
    ),

    /** What a patient is charged: catalog test/panel prices. */
    EDIT_CATALOG(
        title = "Prices are owner-only",
        explanation = "Only the lab owner can change what a test costs. The " +
            "catalog itself stays readable so you can look tests up.",
    ),

    /** Adding people, setting their role, minting their login. */
    MANAGE_STAFF(
        title = "Staff & roles are owner-only",
        explanation = "Only the lab owner can add people, change a role or set " +
            "someone's username and password.",
    ),
}

/**
 * Does the signed-in person hold [permission]? Nobody signed in ⇒ **no** — a
 * null session means the seat is sitting on the sign-in gate, so a guarded route
 * must not slip through while the session is being restored or torn down.
 *
 * Honest framing, same as everywhere else in this package: this is convenience
 * access control on a shared lab PC. The SQLite file — commission rows included
 * — is readable by anyone holding the machine.
 */
fun Staff?.allows(permission: LabPermission): Boolean = when {
    this == null -> false
    !active -> false
    else -> when (permission) {
        LabPermission.MONEY -> canSeeMoney
        LabPermission.EDIT_CATALOG -> canEditCatalog
        LabPermission.MANAGE_STAFF -> canManageStaff
    }
}
