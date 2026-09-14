package com.bnm.lab.screens.billing

import com.bnm.lab.billing.BillingScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.lab.api.BillingApi
import com.bnm.lab.report.waPhone
import com.bnm.lab.report.waDeepLink
import com.bnm.lab.report.waBillMessage
import com.bnm.lab.report.openUrl
import com.bnm.lab.report.WaShareMode
import com.bnm.lab.report.ReportPrefs
import com.bnm.lab.chat.LocalBillingRepository
import com.bnm.lab.print.amountInWords
import com.bnm.lab.print.placeOfSupplyLabel
import com.bnm.lab.util.formatDecimal2
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceDetailScreen(
    api: BillingApi,
    businessId: String,
    invoiceId: String,
    onBack: () -> Unit,
    /** Printed as the supplier when the bill has no business settings behind it (offline edition). */
    labName: String = "BNM Lab",
) {
    val repo = LocalBillingRepository.current
    val scope = rememberCoroutineScope()
    // The balance view, not the raw invoice: a part payment still sitting in the
    // outbox is money the operator has already taken, and this screen is where
    // they check it.
    val bill by repo.invoiceBalanceFlow(businessId, invoiceId).collectAsState(null)
    val businessSettings by repo.invoiceSettingsFlow(businessId).collectAsState(null)
    val inv = bill?.invoice
    var busy by remember { mutableStateOf(false) }
    var collecting by remember { mutableStateOf(false) }
    val waMode = remember { ReportPrefs().waShareMode() }
    val waCountry = remember { ReportPrefs().waCountryCode }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(inv?.displayNumber ?: "Invoice") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
        }
    ) { inner ->
        val money = bill
        if (inv == null || money == null) {
            Text("Invoice not found", modifier = Modifier.padding(inner).padding(16.dp))
            return@Scaffold
        }
        // An offline-archive bill was issued with no business behind it: no GSTIN,
        // no address. After a move to the connected edition the business HAS
        // settings, and printing them on an old offline bill would put a GSTIN on
        // a document that was never issued under it.
        val settings = if (money.offlineArchive) null else businessSettings
        val supplierName = settings?.supplierDisplayName ?: labName
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Supplier identity (Rule 46(a)) + document title ──
            item {
                val gstin = settings?.taxId?.takeIf { it.isNotBlank() }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(supplierName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    settings?.supplierAddress?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    gstin?.let { Text("GSTIN: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(
                        if (gstin != null) "TAX INVOICE" else "INVOICE",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            // ── Invoice meta + recipient ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MetaRow("Invoice no", inv.displayNumber)
                    inv.issuedAt?.let { MetaRow("Date", it) }
                    placeOfSupplyLabel(inv.placeOfSupply ?: settings?.taxId)?.let { MetaRow("Place of supply", it) }
                    MetaRow("Reverse charge", "No")
                    MetaRow(
                        "Status",
                        buildString {
                            append(money.label)
                            if (money.hasQueuedPayment) append(" · payment queued")
                            if (money.isPendingSync) append(" · pending sync")
                        },
                    )
                    // Money collected, then what is still owed — the two numbers a
                    // counter is asked for when a patient returns with a part-paid
                    // bill. Shown for every unsettled bill, not just part-paid ones.
                    if (!money.isSettled || money.collected > 0.005) {
                        MetaRow("Paid", "₹ ${formatDecimal2(money.collected)}")
                    }
                    if (!money.isSettled) {
                        MetaRow("Balance due", "₹ ${formatDecimal2(money.balance)}")
                    }
                    inv.amountTendered?.let { t ->
                        MetaRow("Tendered", "₹ ${formatDecimal2(t)}")
                        MetaRow("Change", "₹ ${formatDecimal2(inv.changeDue ?: 0.0)}")
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Bill to: ${inv.resolvedCustomer}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    inv.customerPhone?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    inv.customerGstin?.takeIf { it.isNotBlank() }?.let { Text("GSTIN: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            // ── Line items + tax breakup + words + signatory ──
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        inv.lineItems.forEach { li ->
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(li.description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Text("₹ ${formatDecimal2(li.amount)}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(
                                    buildString {
                                        li.hsn?.takeIf { it.isNotBlank() }?.let { append("HSN $it · ") }
                                        append("${formatDecimal2(li.quantity)} Nos × ₹${formatDecimal2(li.rate)} · ${pctLabel(li.gstRate)}% GST")
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        AmountRow("Taxable value", inv.subtotal - inv.discount)
                        if (inv.discount > 0) AmountRow("Discount", -inv.discount)
                        inv.taxBreakup.forEach { b ->
                            if (b.igst > 0) AmountRow("IGST @ ${pctLabel(b.rate)}%", b.igst)
                            else { AmountRow("CGST @ ${pctLabel(b.rate / 2)}%", b.cgst); AmountRow("SGST @ ${pctLabel(b.rate / 2)}%", b.sgst) }
                        }
                        AmountRow("Total", inv.total, bold = true)
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        Text(amountInWords(inv.total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("For $supplierName · Authorised Signatory",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!money.isSettled) {
                        // Collecting goes through the outbox, not api.markPaid: the
                        // balance is usually collected days later, often offline, and
                        // a full settle is just a payment that happens to clear it.
                        Button(onClick = { collecting = true }, enabled = !busy) {
                            Text(if (money.isPartPaid) "Collect balance" else "Collect payment")
                        }
                    }
                    // Same choice as the report: the lab's WhatsApp Business
                    // number sends it, or WhatsApp opens with the message ready.
                    val waTo = waPhone(inv.customerPhone, waCountry)
                    // An offline-edition bill has no server copy to send from, so it
                    // only ever opens WhatsApp on this PC. Decided by where THIS bill
                    // is filed, not by the screen: a connected lab still opens the
                    // bills it issued offline.
                    val offlineBill = money.offlineArchive || BillingScope.isOffline(businessId)
                    OutlinedButton(onClick = {
                        if (!busy) {
                            if ((waMode == WaShareMode.LINK || offlineBill) && waTo != null) {
                                openUrl(waDeepLink(waTo, waBillMessage(
                                    customerName = inv.customerName,
                                    businessName = supplierName,
                                    invoiceNumber = inv.displayNumber,
                                    total = inv.total,
                                    balance = money.balance,
                                    url = inv.pdfUrl,
                                    money = { "₹ " + formatDecimal2(it) },
                                )))
                            } else if (!offlineBill) {
                                busy = true
                                scope.launch {
                                    api.sendInvoice(businessId, invoiceId, "whatsapp")
                                    runCatching { repo.syncInvoices(businessId) }; busy = false
                                }
                            }
                        }
                    },
                        // No phone on an offline bill: nothing to open and nowhere to
                        // send from, so the button says so instead of doing nothing.
                        enabled = !busy && !(offlineBill && waTo == null),
                    ) { Text(if (offlineBill && waTo == null) "No phone to WhatsApp" else "Send on WhatsApp") }
                }
            }
        }

        if (collecting) {
            CollectPaymentDialog(
                businessId = businessId,
                bill = money,
                onDismiss = { collecting = false },
                onCollected = { collecting = false },
            )
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: Double, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text("₹ ${formatDecimal2(amount)}", style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun pctLabel(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else formatDecimal2(v)
