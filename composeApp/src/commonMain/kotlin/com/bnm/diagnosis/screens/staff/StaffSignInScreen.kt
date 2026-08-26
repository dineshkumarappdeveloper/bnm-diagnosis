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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.staff.LocalStaffRepository
import com.bnm.diagnosis.staff.Staff
import com.bnm.diagnosis.staff.StaffCredential
import com.bnm.diagnosis.staff.StaffRole
import kotlinx.coroutines.launch

/**
 * The seat's sign-in gate (P4): every active staff member as a tap target, with
 * a PIN pad — or a password box — for the people who set a credential. Reached at
 * app start (once licensed), from "Switch user", and whenever the 15-minute
 * auto-lock fires; lab data is never touched, only the in-memory
 * [com.bnm.diagnosis.staff.StaffSession].
 *
 * Round 1 added the second door: **type a username and password** instead of
 * picking a face. Same screen, same offline guarantee — both paths end in a
 * local SQLDelight read and a pure-Kotlin hash, so a lab PC that has never seen
 * the internet signs its people in exactly the same way.
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

    var secretFor by remember { mutableStateOf<Staff?>(null) }   // tile chosen, secret pending
    var typedLogin by remember { mutableStateOf(false) }         // username + password form
    var secret by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    // Offering the typed form on a lab where nobody has a username would be a
    // door that cannot open — the owner sets logins up in Staff & roles first.
    val anyLogins = staff.any { it.hasLogin }

    fun reset() {
        secretFor = null; typedLogin = false; secret = ""; username = ""; error = null
    }

    fun choose(s: Staff) {
        error = null
        if (!s.hasSecret) { onSignedIn(s); return }
        secret = ""; secretFor = s
    }

    /** Tile path: the person is already identified, only the secret is in doubt. */
    fun submitSecret() {
        val target = secretFor ?: return
        if (checking || secret.isEmpty()) return
        checking = true; error = null
        scope.launch {
            val ok = repo.verifyPin(target.id, secret)
            checking = false
            if (ok) { reset(); onSignedIn(target) }
            else {
                secret = ""
                // Name whichever box is actually on screen (see the branch below).
                error = if (target.credential == StaffCredential.PIN) "Wrong PIN — try again"
                else "Wrong password — try again"
            }
        }
    }

    /** Typed path: one message for both failures, so the form never confirms
     *  which usernames exist on this lab's roster. */
    fun submitLogin() {
        if (checking || username.isBlank() || secret.isEmpty()) return
        checking = true; error = null
        scope.launch {
            val person = repo.verifyLogin(username, secret)
            checking = false
            if (person != null) { reset(); onSignedIn(person) }
            else { secret = ""; error = "Wrong username or password" }
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

            val target = secretFor
            when {
                typedLogin -> LoginForm(
                    username = username,
                    password = secret,
                    error = error,
                    busy = checking,
                    onUsername = { username = it; error = null },
                    onPassword = { secret = it; error = null },
                    onSubmit = { submitLogin() },
                    onCancel = { reset() },
                )

                // Text box for everything except a genuine numeric PIN — the pad
                // cannot type letters.
                target != null && target.credential != StaffCredential.PIN -> PasswordPrompt(
                    staff = target,
                    password = secret,
                    error = error,
                    busy = checking,
                    onPassword = { secret = it; error = null },
                    onSubmit = { submitSecret() },
                    onCancel = { reset() },
                )

                target != null -> PinPad(
                    staff = target,
                    pin = secret,
                    error = error,
                    busy = checking,
                    onDigit = { d -> if (secret.length < MAX_PIN) { error = null; secret += d } },
                    onBackspace = { if (secret.isNotEmpty()) secret = secret.dropLast(1) },
                    onSubmit = { submitSecret() },
                    onCancel = { reset() },
                )

                else -> {
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
                    if (anyLogins) {
                        TextButton(onClick = { reset(); typedLogin = true }) {
                            Text("Sign in with a username instead")
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_PIN = 8

/** One person as a tap target: initials disc, name, role chip, sign-in hint. */
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
                when (staff.credential) {
                    StaffCredential.NONE -> "Tap to enter"
                    StaffCredential.PIN -> "PIN required"
                    StaffCredential.PASSWORD -> "Password required"
                    // Written by a newer build; it will refuse, and that is the
                    // safe direction — say so rather than pretending it opens.
                    StaffCredential.UNREADABLE -> "Ask the owner to reset this login"
                },
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

/** Typed sign-in: the employee login. No digit filtering anywhere — a lab
 *  password is alphanumeric, unlike the PIN the pad below collects. */
@Composable
private fun LoginForm(
    username: String,
    password: String,
    error: String?,
    busy: Boolean,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }

    Column(
        Modifier.widthIn(max = 340.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sign in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = username,
            // Case-folded as you type, so the field shows exactly what is matched.
            onValueChange = { onUsername(it.trim().lowercase()) },
            label = { Text("Username") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation =
                if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = onSubmit,
            enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Checking…" else "Sign in") }

        TextButton(onClick = onCancel) { Text("Back to the list") }
    }
}

/** Tile path for someone whose credential is a password: the pad cannot type
 *  letters, so the same person gets a text box instead. */
@Composable
private fun PasswordPrompt(
    staff: Staff,
    password: String,
    error: String?,
    busy: Boolean,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }

    Column(
        Modifier.widthIn(max = 340.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(staff.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        RoleChip(staff.role)

        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation =
                if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = onSubmit,
            enabled = !busy && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Checking…" else "Sign in") }

        TextButton(onClick = onCancel) { Text("Back to the list") }
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
