package com.bnm.diagnosis.screens.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.staff.LocalStaffRepository
import com.bnm.diagnosis.staff.Staff
import com.bnm.diagnosis.staff.StaffRole
import kotlinx.coroutines.launch

/**
 * The seat's sign-in gate (P4): every active staff member as a tap target, with
 * a PIN pad for the people who set one. Reached at app start (once licensed),
 * from "Switch user", and whenever the 15-minute auto-lock fires — lab data is
 * never touched, only the in-memory [com.bnm.diagnosis.staff.StaffSession].
 *
 * A fresh install seeds a PIN-less "Lab Owner" here, so the first person in is
 * always one tap away from setting the lab up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StaffSignInScreen(
    labName: String,
    onSignedIn: (Staff) -> Unit,
) {
    val repo = LocalStaffRepository.current
    val scope = rememberCoroutineScope()
    val staff by remember(repo) { repo.listActiveFlow() }.collectAsState(emptyList())

    // Anti-lockout: no active account (fresh install / pre-P4 DB) → seed one owner.
    LaunchedEffect(Unit) { runCatching { repo.seedOwnerIfEmpty(labName) } }

    var pinFor by remember { mutableStateOf<Staff?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun choose(s: Staff) {
        error = null
        if (!s.hasPin) { onSignedIn(s); return }
        pin = ""; pinFor = s
    }

    fun submit() {
        val target = pinFor ?: return
        if (checking || pin.isBlank()) return
        checking = true; error = null
        scope.launch {
            val ok = repo.verifyPin(target.id, pin)
            checking = false
            if (ok) { pinFor = null; pin = ""; onSignedIn(target) }
            else { pin = ""; error = "Wrong PIN — try again" }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("BNM DIAGNOSIS", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(labName, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            val target = pinFor
            if (target == null) {
                Text("Who's working?", style = MaterialTheme.typography.titleMedium)
                if (staff.isEmpty()) {
                    Text(
                        "Setting up this lab's first account…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(
                    Modifier.widthIn(max = 780.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    staff.forEach { s -> StaffTile(s, onClick = { choose(s) }) }
                }
            } else {
                PinPad(
                    staff = target,
                    pin = pin,
                    error = error,
                    busy = checking,
                    onDigit = { d -> if (pin.length < MAX_PIN) { error = null; pin += d } },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    onSubmit = { submit() },
                    onCancel = { pinFor = null; pin = ""; error = null },
                )
            }
        }
    }
}

private const val MAX_PIN = 8

/** One person as a tap target: initials disc, name, role chip, PIN hint. */
@Composable
private fun StaffTile(staff: Staff, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(200.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(initialsOf(staff.name), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(staff.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            RoleChip(staff.role)
            Text(
                if (staff.hasPin) "PIN required" else "Tap to enter",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Role pill — shared by the sign-in grid, the header chip and staff management. */
@Composable
fun RoleChip(role: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(StaffRole.label(role), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Numeric PIN pad — desktop keyboards can type, touch seats tap. */
@Composable
private fun PinPad(
    staff: Staff,
    pin: String,
    error: String?,
    busy: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.widthIn(max = 320.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(staff.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        RoleChip(staff.role)
        Text("Enter PIN", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(maxOf(pin.length, 4)) { i ->
                Box(
                    Modifier.size(14.dp).background(
                        if (i < pin.length) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    )
                )
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { d -> PinKey(d, enabled = !busy) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PinKey("←", enabled = !busy, onClick = onBackspace)
            PinKey("0", enabled = !busy) { onDigit("0") }
            PinKey("✓", enabled = !busy && pin.isNotEmpty(), primary = true, onClick = onSubmit)
        }
        TextButton(onClick = onCancel) { Text("Back to the list") }
    }
}

@Composable
private fun PinKey(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    val container =
        if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(72.dp)
            .background(if (enabled) container else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Asha R Kumar" → "AK"; single word → first two letters. */
internal fun initialsOf(name: String): String {
    val parts = name.trim().split(' ', '.').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}
