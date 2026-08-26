package com.bnm.diagnosis.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.bnm.diagnosis.staff.LabPermission
import com.bnm.diagnosis.staff.Staff
import com.bnm.diagnosis.staff.allows

/**
 * Which destinations the signed-in person must hold a permission to reach.
 *
 * Round-1 feedback item 2: an employee "won't see the commission and other
 * controllers". A hidden button in front of a live route is not a gate — anything
 * that can produce a route string (a deep link, a restored back stack, the next
 * screen someone wires up) walks straight past it. So the rule lives HERE, at the
 * layer that decides destinations, and the hidden buttons are the courtesy on top.
 *
 * Keyed by the route PATTERN, which is exactly what `NavDestination.route` gives
 * back for both plain and argument-bearing routes.
 *
 * Not every money surface is a whole route. [Screen.Catalog] is deliberately
 * absent: a technician needs to look tests up, and it is only the price EDITOR
 * inside that screen which is owner-only — that one is gated in-screen against
 * [LabPermission.EDIT_CATALOG].
 */
object RouteGuard {
    private val REQUIRED: Map<String, LabPermission> = mapOf(
        // The whole referrer hub: tab 2 is the payout statement and tab 1 carries
        // the per-doctor negotiated rate lists. Picking a referrer during
        // registration happens on NewOrder, which stays open to everyone.
        Screen.Referrers.route to LabPermission.MONEY,
        Screen.Staff.route to LabPermission.MANAGE_STAFF,
    )

    fun requirement(route: String?): LabPermission? = REQUIRED[route]

    /** Unlisted routes are open — this is a deny-list of money surfaces, not an
     *  allow-list, so a new screen is never accidentally unreachable. */
    fun allows(route: String?, who: Staff?): Boolean =
        requirement(route)?.let { who.allows(it) } ?: true
}

/**
 * Wrap a guarded destination's body. Renders [content] only when the signed-in
 * person is allowed; otherwise the route composes to an explanation and a way
 * back, so the screen behind it never builds and never queries.
 *
 * This is the flash-free half of the gate — prefer it over [RouteGuardEffect]
 * alone, which can only pop after a frame has already drawn.
 */
@Composable
fun GuardedRoute(
    route: String,
    who: Staff?,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val needed = RouteGuard.requirement(route)
    if (needed == null || who.allows(needed)) content() else PermissionDenied(needed, onBack)
}

/**
 * Backstop: one line at the NavHost's parent that pops any destination the
 * current person may not be on. Catches the paths [GuardedRoute] cannot — a
 * back stack restored across a "switch user", or a route reached before someone
 * remembered to wrap it.
 *
 * Re-collects when the person changes, so switching from the owner to a
 * technician while the commission tab is open ejects the technician immediately.
 * Never applied to the start destination (none of the guarded routes is one), so
 * this cannot pop the app's last entry.
 */
@Composable
fun RouteGuardEffect(navController: NavController, who: Staff?) {
    LaunchedEffect(navController, who?.id, who?.role, who?.active) {
        navController.currentBackStackEntryFlow.collect { entry ->
            if (!RouteGuard.allows(entry.destination.route, who)) navController.popBackStack()
        }
    }
}

/**
 * Navigate only if allowed. For entry points that cannot be hidden (a shortcut
 * tile whose owner screen has no idea who is signed in) — returns false when the
 * tap was refused so the caller can say something.
 */
fun NavController.navigateAllowed(route: String, who: Staff?): Boolean {
    if (!RouteGuard.allows(route, who)) return false
    navigate(route)
    return true
}

/** What a blocked route shows instead of the screen. Names the reason — a plain
 *  "forbidden" leaves the person thinking the app is broken. */
@Composable
private fun PermissionDenied(permission: LabPermission, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    permission.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    permission.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onBack) { Text("Go back") }
            }
        }
    }
}
