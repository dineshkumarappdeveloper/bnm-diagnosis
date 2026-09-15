package com.bnm.lab.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bnm.lab.license.LicenseState
import com.bnm.lab.license.ReadOnlyCopy
import com.bnm.lab.license.ReadOnlyReason

/**
 * What the licence decides about destinations — the licence counterpart of
 * [RouteGuard].
 *
 * Two rules, and "lab data is always exportable regardless of licence state"
 * is what holds them together:
 *
 * 1. ENTRY. Only a computer with no genuine licence (never activated, or
 *    deactivated) opens on Activation. A subscription past lic_exp + gr, or a
 *    device BNM blocked, still opens on staff sign-in and reaches every record.
 *    Sending a lapsed lab to Activation stranded its data behind a key screen.
 * 2. NEW WORK. The destinations that START work — registering an order,
 *    raising a bill — refuse a seat whose licence is lapsed or blocked. Listed
 *    here rather than hidden behind buttons, so a route string from anywhere (a
 *    restored back stack, the EMR inbox, the next screen someone wires up) meets
 *    the same rule. Everything else, results on existing orders included, is
 *    open: this is a deny-list, so a new read screen is never locked by accident.
 */
object LicenceGate {
    private val NEW_WORK: Set<String> = setOf(
        Screen.NewOrder.route,
        Screen.CreateInvoice.route,
        Screen.Cart.route,
        Screen.CustomerDetails.route,
    )

    /** The app's first screen for a computer that is [activated] or not. */
    fun entryRoute(activated: Boolean): String =
        if (activated) Screen.StaffSignIn.route else Screen.Activation.route

    fun startsNewWork(route: String?): Boolean = route in NEW_WORK

    fun allows(route: String?, licence: LicenseState): Boolean =
        !startsNewWork(route) || licence.canStartNewWork
}

/**
 * Wrap a new-work destination's body: [content] when the licence allows it,
 * otherwise an explanation and a way back, so the screen behind never builds.
 *
 * Decided ONCE, when the screen opens, not on every licence emission. The app
 * re-checks the term every minute, and a lapse (or a block) landing while
 * someone is half-way through a registration must not throw the typed form
 * away — that registration finishes, and the next one is refused.
 */
@Composable
fun LicenceGatedRoute(
    route: String,
    licence: LicenseState,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val refusal = remember { if (LicenceGate.allows(route, licence)) null else licence.readOnlyReason }
    if (refusal == null) content() else ReadOnlyNotice(refusal, onBack)
}

/** Full-width banner over the home screen while this seat is read-only. */
@Composable
fun ReadOnlyBanner(reason: ReadOnlyReason, onOpenLicence: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    ReadOnlyCopy.title(reason),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    ReadOnlyCopy.detail(reason),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Renewal is something the lab can act on from here; a block is not.
            if (reason == ReadOnlyReason.EXPIRED && onOpenLicence != null) {
                TextButton(onClick = onOpenLicence) {
                    Text("License & renewal", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/** Shown instead of a new-work screen while this seat is read-only. */
@Composable
private fun ReadOnlyNotice(reason: ReadOnlyReason, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    ReadOnlyCopy.title(reason),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    ReadOnlyCopy.detail(reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onBack) { Text("Go back") }
            }
        }
    }
}
