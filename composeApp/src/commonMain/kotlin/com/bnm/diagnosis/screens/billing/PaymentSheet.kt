package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Image
import com.bnm.diagnosis.api.PaymentLinkInfo
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.billing.PaymentChoice
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.chat.LocalOutboxSender
import com.bnm.diagnosis.util.formatDecimal2
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.ktor.http.encodeURLParameter

/**
 * An amount collected now that does NOT settle the bill — the advance a lab
 * takes at registration ("₹300 now, the rest when you collect the report").
 * It is deliberately not a [PaymentChoice]: that type says how a bill was
 * SETTLED, and a part payment settles nothing. The caller turns this into a
 * queued tender (BillingRepository.recordPaymentLocal), which is what keeps the
 * balance alive across a sync that replaces the whole invoice doc.
 */
data class PartPayment(val amount: Double, val method: String, val reference: String? = null)

/**
 * Payment-mode step shown before a bill saves. Cash / UPI / Card confirm the
 * payment on the spot (bill saves as PAID); Payment link saves the bill unpaid
 * and hands off to [PaymentLinkDialog]; "Save without payment" keeps the old
 * behavior. Everything except the payment link works fully OFFLINE.
 *
 * Pass [onPartPayment] to let the counter collect LESS than [total]: the bill
 * then saves unpaid and the amount rides along as an advance, leaving a visible
 * balance. Callers that leave it null get exactly the old all-or-nothing sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentSheet(
    total: Double,
    upiVpa: String?,
    businessName: String,
    isOnline: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PaymentChoice) -> Unit,
    onPaymentLink: () -> Unit,
    onPartPayment: ((PartPayment) -> Unit)? = null,
) {
    var mode by remember { mutableStateOf("cash") }
    val allowPart = onPartPayment != null
    val emitPart: (PartPayment) -> Unit = onPartPayment ?: { _ -> }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.width(360.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Collect payment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("₹ ${formatDecimal2(total)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "cash", onClick = { mode = "cash" }, label = { Text("Cash") })
                    FilterChip(selected = mode == "upi", onClick = { mode = "upi" }, label = { Text("UPI") })
                    FilterChip(selected = mode == "card", onClick = { mode = "card" }, label = { Text("Card") })
                    FilterChip(selected = mode == "link", onClick = { mode = "link" }, label = { Text("Payment link") }, enabled = isOnline)
                }
                if (!isOnline) {
                    Text("Payment link needs internet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (mode) {
                    "cash" -> CashPane(total, allowPart, onConfirm, emitPart)
                    "upi" -> UpiPane(total, upiVpa, businessName, allowPart, onConfirm, emitPart)
                    "card" -> CardPane(total, allowPart, onConfirm, emitPart)
                    "link" -> LinkPane(isOnline, onPaymentLink)
                }
                TextButton(onClick = { onConfirm(PaymentChoice(method = null, markPaid = false)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save without payment", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CashPane(
    total: Double,
    allowPart: Boolean,
    onConfirm: (PaymentChoice) -> Unit,
    onPart: (PartPayment) -> Unit,
) {
    var tenderedText by remember { mutableStateOf("") }
    val tendered = tenderedText.trim().toDoubleOrNull()
    // ONE box serves both cases, because the operator types the same thing: the
    // notes in their hand. At or above the total it settles the bill and the
    // drawer owes change; below it, it is an advance and the bill keeps a
    // balance. Neither reading is a special mode the operator has to pick.
    val full = tendered != null && tendered >= total - 1e-9
    val part = allowPart && tendered != null && tendered > 0.0 && !full
    val change = (tendered ?: 0.0) - total

    fun setAmount(v: Double) {
        tenderedText = if (v == v.toLong().toDouble()) v.toLong().toString() else formatDecimal2(v)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = tenderedText,
            onValueChange = { tenderedText = it },
            label = { Text("Cash received") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AssistChip(onClick = { setAmount(total) }, label = { Text("Exact") })
            listOf(10, 20, 50, 100, 200, 500).forEach { add ->
                AssistChip(onClick = { setAmount((tendered ?: 0.0) + add) }, label = { Text("+₹$add") })
            }
        }
        if (part) {
            BalanceNote(total - (tendered ?: 0.0))
        } else {
            Text(
                "Change to return: ₹ ${formatDecimal2(change)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (full) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                val t = tendered ?: return@Button
                if (full) onConfirm(PaymentChoice(method = "cash", markPaid = true, tendered = t, change = t - total))
                else onPart(PartPayment(amount = t, method = "cash"))
            },
            enabled = full || part,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (part) "Take ₹ ${formatDecimal2(tendered ?: 0.0)} as part payment" else "Confirm cash received") }
    }
}

/** Shown whenever the amount on screen leaves something owing. */
@Composable
private fun BalanceNote(balance: Double) {
    Text(
        "Balance after this: ₹ ${formatDecimal2(balance)}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

/** "Amount to collect now", pre-filled with the full total. Used by the UPI and
 *  card panes, where (unlike cash) there is no change to give back — so the
 *  number typed here IS the amount charged, and the QR must carry it. */
@Composable
private fun CollectAmountField(total: Double, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Amount to collect (full: ₹ ${formatDecimal2(total)})") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@Composable
private fun UpiPane(
    total: Double,
    upiVpa: String?,
    businessName: String,
    allowPart: Boolean,
    onConfirm: (PaymentChoice) -> Unit,
    onPart: (PartPayment) -> Unit,
) {
    val vpa = upiVpa?.trim()?.takeIf { it.isNotEmpty() }
    var amountText by remember(total) { mutableStateOf(formatDecimal2(total)) }
    // Clamp to the bill: a UPI QR must never ask for more than is owed. Garbage
    // in the box falls back to the full total rather than charging ₹0.
    val amount = (amountText.trim().toDoubleOrNull() ?: total).coerceIn(0.0, total)
    val full = !allowPart || amount >= total - 1e-9
    val charge = if (full) total else amount
    // NPCI `tr` (Transaction Reference): unique per QR, alphanumeric, ≤35 chars
    // (uuid hex = 32). The payer's UPI app carries it into the transaction, so
    // the bank's payment-notification email quotes it — a future email webhook
    // matches this against invoices.payment_reference to auto-mark paid.
    val txRef = remember { kotlin.uuid.Uuid.random().toString().replace("-", "") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (vpa != null) {
            if (allowPart) CollectAmountField(total, amountText) { amountText = it }
            if (!full) BalanceNote(total - charge)
            val note = "Bill at ${businessName.take(30)}"
            val uri = "upi://pay?pa=$vpa&pn=${businessName.encodeURLParameter()}" +
                "&am=${formatDecimal2(charge)}&cu=INR" +
                "&tn=${note.encodeURLParameter()}&tr=$txRef"
            // White backing keeps the QR scannable in dark theme.
            Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                Image(
                    painter = rememberQrCodePainter(data = uri),
                    contentDescription = "UPI payment QR",
                    modifier = Modifier.padding(12.dp).size(200.dp),
                )
            }
            Text(vpa, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Ref: $txRef", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    if (full) onConfirm(PaymentChoice(method = "upi", markPaid = true, reference = txRef))
                    else onPart(PartPayment(amount = charge, method = "upi", reference = txRef))
                },
                enabled = charge > 0.0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (full) "Payment received" else "Received ₹ ${formatDecimal2(charge)} (part)") }
        } else {
            Text(
                "Set this counter's UPI ID in BNM Admin → Settings → Billing Counters",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Payment received") }
        }
    }
}

@Composable
private fun CardPane(
    total: Double,
    allowPart: Boolean,
    onConfirm: (PaymentChoice) -> Unit,
    onPart: (PartPayment) -> Unit,
) {
    var amountText by remember(total) { mutableStateOf(formatDecimal2(total)) }
    val amount = (amountText.trim().toDoubleOrNull() ?: total).coerceIn(0.0, total)
    val full = !allowPart || amount >= total - 1e-9
    val charge = if (full) total else amount

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (allowPart) CollectAmountField(total, amountText) { amountText = it }
        Text("Charge ₹ ${formatDecimal2(charge)} on the card machine", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (!full) BalanceNote(total - charge)
        Text("Confirm once the machine approves", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = {
                if (full) onConfirm(PaymentChoice(method = "card", markPaid = true))
                else onPart(PartPayment(amount = charge, method = "card"))
            },
            enabled = charge > 0.0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (full) "Payment approved" else "Approved ₹ ${formatDecimal2(charge)} (part)") }
    }
}

@Composable
private fun LinkPane(isOnline: Boolean, onPaymentLink: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "The bill saves unpaid and a Razorpay link is created for the customer. It marks itself paid automatically once they pay.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isOnline) Text("Needs internet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Button(onClick = onPaymentLink, enabled = isOnline, modifier = Modifier.fillMaxWidth()) { Text("Save & create link") }
    }
}

/**
 * Post-save payment-link flow: drains the outbox so the bill exists server-side,
 * then mints the Razorpay link and shows it as a QR + copyable URL. On failure
 * the bill stays saved unpaid (operator can collect another way).
 */
@Composable
fun PaymentLinkDialog(businessId: String, invoice: Invoice, onDone: () -> Unit) {
    val repo = LocalBillingRepository.current
    val outbox = LocalOutboxSender.current
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf<PaymentLinkInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var paid by remember { mutableStateOf(false) }
    var paidRef by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(invoice.id) {
        // The link endpoint needs the invoice server-side — push the outbox first.
        runCatching { outbox.drain() }
        repo.createPaymentLink(businessId, invoice.id)
            .onSuccess { link = it }
            .onFailure { error = it.message ?: "Couldn't create the payment link" }
    }

    // Live status: while the QR is on screen, poll the invoice so the counter
    // sees "Paid" the moment the Razorpay webhook flips it — and mirror the
    // paid row into the local store so the Bills list updates immediately.
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        while (!paid) {
            kotlinx.coroutines.delay(4000)
            repo.getInvoice(businessId, invoice.id).onSuccess { srv ->
                if (srv.status == "paid") {
                    paid = true
                    paidRef = srv.paymentReference
                    runCatching { repo.applyServerInvoice(businessId, srv) }
                }
            }
        }
        // A short beat so the operator SEES the green confirmation, then move
        // on automatically (→ the save-result dialog, which auto-prints when a
        // printer is connected). Done stays tappable for the impatient.
        kotlinx.coroutines.delay(1800)
        onDone()
    }

    Dialog(onDismissRequest = onDone) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(
                Modifier.width(360.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Payment link", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${invoice.displayNumber} · ₹ ${formatDecimal2(link?.amount?.takeIf { it > 0 } ?: invoice.total)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val l = link
                when {
                    paid -> {
                        Text(
                            "✓", style = MaterialTheme.typography.displayMedium,
                            color = Color(0xFF16A34A), fontWeight = FontWeight.Bold,
                        )
                        Text("Payment received", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        paidRef?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "The bill is marked paid.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    l != null -> {
                        Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                            Image(
                                painter = rememberQrCodePainter(data = l.shortUrl),
                                contentDescription = "Payment link QR",
                                modifier = Modifier.padding(12.dp).size(200.dp),
                            )
                        }
                        Text(l.shortUrl, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(l.shortUrl)); copied = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (copied) "Copied" else "Copy link") }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(
                                "Waiting for payment — marks itself paid automatically.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    error != null -> {
                        Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Text(
                            "The bill is saved (unpaid) — you can collect the payment another way.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Text("Creating payment link…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Done") }
            }
        }
    }
}
