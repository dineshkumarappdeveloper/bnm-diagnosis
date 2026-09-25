package com.bnm.lab.screens.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffCredential
import kotlinx.coroutines.launch

/** How many wrong secrets before the gate closes on its own. */
private const val MAX_ATTEMPTS = 3

/**
 * "Confirm it's you" — the signed-in owner re-enters their PIN (or password)
 * before a backup action that shows the recovery code or destroys backups.
 * Same secret, same [com.bnm.lab.staff.StaffRepository.verifyPin] as the
 * sign-in grid; [verify] is that call bound to the person.
 *
 * An owner with NO secret passes straight through: tap-to-enter is the
 * documented contract for a bench PC, and a gate that demanded a PIN nobody
 * set would simply lock the owner out of their own backups.
 */
@Composable
fun OwnerPinDialog(
    staff: Staff,
    title: String,
    verify: suspend (String) -> Boolean,
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!staff.hasSecret) {
        LaunchedEffect(Unit) { onVerified() }
        return
    }
    val scope = rememberCoroutineScope()
    val isPin = staff.credential == StaffCredential.PIN
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableStateOf(0) }
    var checking by remember { mutableStateOf(false) }

    fun submit() {
        if (checking || secret.isEmpty()) return
        checking = true; error = null
        scope.launch {
            val ok = runCatching { verify(secret) }.getOrDefault(false)
            checking = false
            if (ok) { onVerified(); return@launch }
            attempts++
            secret = ""
            if (attempts >= MAX_ATTEMPTS) { onDismiss(); return@launch }
            error = if (isPin) "Wrong PIN — try again" else "Wrong password — try again"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.widthIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${staff.name}, enter your ${if (isPin) "PIN" else "password"} to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = if (isPin) it.filter { ch -> ch.isDigit() }.take(8) else it; error = null },
                    label = { Text(if (isPin) "PIN" else "Password") },
                    singleLine = true,
                    enabled = !checking,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !checking && secret.isNotEmpty()) {
                Text(if (checking) "Checking…" else "Confirm")
            }
        },
        dismissButton = { TextButton(onClick = { if (!checking) onDismiss() }) { Text("Cancel") } },
    )
}
