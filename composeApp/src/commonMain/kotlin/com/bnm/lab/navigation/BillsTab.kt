package com.bnm.lab.navigation

import com.bnm.lab.staff.LabPermission
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.allows

/**
 * The tabs of the Bills page. Bills and Revenue live on ONE page, but not for
 * everyone: the desk collects payments from the bill list, while takings are
 * the owner's. RouteGuard keys on the route PATTERN and cannot tell
 * `bills?tab=revenue` from `bills`, so the rule lives here, as data, and the
 * page resolves the tab from the live signed-in person on every composition —
 * the same in-screen gate the catalog's price editor uses.
 */
enum class BillsTab(val slug: String, val label: String, val requires: LabPermission?) {
    BILLS("bills", "Bills", null),
    REVENUE("revenue", "Revenue", LabPermission.REVENUE);

    fun allowedFor(who: Staff?): Boolean = requires == null || who.allows(requires)

    companion object {
        /**
         * The tab a person actually gets: the one asked for when they may see it,
         * otherwise Bills. An unknown slug — including the literal "{tab}" a
         * caller would pass by navigating to the bare route pattern — is Bills.
         */
        fun resolve(slug: String?, who: Staff?): BillsTab =
            entries.firstOrNull { it.slug == slug }?.takeIf { it.allowedFor(who) } ?: BILLS

        /** The tabs shown to [who]; a single tab means no tab row at all. */
        fun visibleTo(who: Staff?): List<BillsTab> = entries.filter { it.allowedFor(who) }
    }
}
