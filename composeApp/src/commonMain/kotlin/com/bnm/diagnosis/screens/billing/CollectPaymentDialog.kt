package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bnm.diagnosis.chat.InvoiceBalance
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.chat.LocalOutboxSender
import com.bnm.diagnosis.util.formatDecimal2
import kotlinx.coroutines.launch

/**
 * Collect against a bill that already exists — the second half of a part
 * payment, typically days later when the patient comes for the report.
 *
 * This is the offline case by definition (different session, maybe no network),
 * so it NEVER calls the network: the tender is queued through the outbox and the
 * balance on every screen drops immediately. Full and further-partial payments
 * are the same path — the server's running total decides when the bill is paid.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectPaymentDialog(
    businessId: String,
    bill: InvoiceBalance,
    onDismiss: () -> Unit,
    onCollected: () -> Unit,
) {
    val repo = LocalBillingRepository.current
    val outbox = LocalOutboxSender.current
    val scope = rememberCoroutineScope()

    val due = bill.balance
    var amountText by remember { mutableStateOf(formatDecimal2(due)) }
    var method by remember { mutableStateOf("cash") }
    var reference by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Never accept more than is owed: an overpayment is a refund problem, and
    // the bill's own total is the only ceiling that makes sense here.
    val amount = amountText.trim().toDoubleOrNull()
    val valid = amount != null && amount > 0.0 && amount <= due + 0.005
    val remaining = due - (amount ?: 0.0)

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.width(360.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Collect payment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(bill.invoice.displayNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Bill ₹ ${formatDecimal2(bill.invoice.total)} · already paid ₹ ${formatDecimal2(bill.collected)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; error = null },
                    label = { Text("Amount (due ₹ ${formatDecimal2(due)})") },
                    singleLine = true,
                    isError = amountText.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                AssistChip(onClick = { amountText = formatDecimal2(due) }, label = { Text("Full balance") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("cash" to "Cash", "upi" to "UPI", "card" to "Card").forEach { (key, label) ->
                        FilterChip(selected = method == key, onClick = { method = key }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = reference,
                    onValueChange = { reference = it },
                    label = { Text("Reference (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (valid && remaining > 0.005) {
                    Text(
                        "Balance after this: ₹ ${formatDecimal2(remaining)}",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        val amt = amount ?: return@Button
                        if (busy) return@Button
                        busy = true; error = null
                        scope.launch {
                            repo.recordPaymentLocal(
                                businessId = businessId,
                                invoiceId = bill.invoice.id,
                                amount = amt,
                                method = method,
                                reference = reference,
                            ).onSuccess {
                                // Queued and already visible; the kick only decides
                                // whether it leaves NOW or on the next reconnect.
                                outbox.kick()
                                busy = false
                                onCollected()
                            }.onFailure {
                                busy = false
                                error = it.message ?: "Could not record the payment"
                            }
                        }
                    },
                    enabled = valid && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (valid && remaining <= 0.005) "Settle bill" else "Record payment") }
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}
