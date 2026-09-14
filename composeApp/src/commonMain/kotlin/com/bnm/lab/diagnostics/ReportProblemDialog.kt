package com.bnm.lab.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * "Report a problem": describe it, create the report, and the lab's own mail
 * client opens addressed to BNM support with the report file revealed beside it.
 *
 * The copy tells the operator exactly what the file does and does not contain,
 * because a lab that is unsure whether it is emailing patient data will — rightly
 * — not send it.
 */
@Composable
fun ReportProblemDialog(request: SupportUi.Request, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SupportReportResult?>(null) }

    fun close() {
        if (request.crashNotice != null) SupportReporter.acknowledgeCrash()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(if (request.crashNotice != null) "BNM Lab closed unexpectedly" else "Report a problem") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (request.crashNotice != null) {
                    Text(
                        "The last session ended with an error. Sending the report lets BNM " +
                            "find the cause and fix it in the next update.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val done = result
                if (done == null) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(2000) },
                        label = { Text("What happened? (optional)") },
                        placeholder = { Text("e.g. The report did not print after approving") },
                        minLines = 3,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "This creates a file with the app's activity log from the last " +
                            "7 days — screens opened, sync and printing events, and any errors. " +
                            "It contains no patient names, phone numbers or test results.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(done.message, style = MaterialTheme.typography.bodyMedium)
                    done.filePath?.let { path ->
                        Text("Report file", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(path, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("Send it to", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(
                            SupportReporter.supportEmail,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (done.filePath != null) {
                        // Shown either way: "opened" only means the OS accepted the
                        // link — a lab on browser Gmail may still see no draft.
                        Text(
                            if (done.emailOpened) {
                                "If no draft appeared (for example, you use Gmail or Outlook in a browser), " +
                                    "write to the address above and attach the report file."
                            } else {
                                "No email app opened on this computer. Open your email (Gmail, " +
                                    "Outlook…), write to the address above, and attach the report file."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (done.emailOpened) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            val done = result
            if (done == null) {
                Button(
                    enabled = !busy,
                    onClick = onClick@{
                        if (busy) return@onClick
                        busy = true
                        scope.launch {
                            result = runCatching { SupportReporter.createAndEmail(description.trim()) }
                                .getOrElse {
                                    AppLog.e("Support", "report creation failed", it)
                                    SupportReportResult(null, false, "Couldn't create the report: ${it.message}")
                                }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Creating…" else "Create report & email") }
            } else {
                Button(onClick = { close() }) { Text("Done") }
            }
        },
        dismissButton = {
            val path = result?.filePath
            when {
                path != null -> TextButton(onClick = { SupportReporter.revealFile(path) }) { Text("Show file") }
                result == null -> TextButton(enabled = !busy, onClick = { close() }) {
                    Text(if (request.crashNotice != null) "Not now" else "Cancel")
                }
            }
        },
    )
}
