package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.billing.BillingPrefs
import com.bnm.diagnosis.billing.LocalCart
import com.bnm.diagnosis.billing.PaymentChoice
import com.bnm.diagnosis.billing.cartPayableTotal
import com.bnm.diagnosis.billing.createFromCart
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.chat.LocalOutboxSender
import com.bnm.diagnosis.connectivity.LocalConnectivity
import com.bnm.diagnosis.util.formatDecimal2
import kotlinx.coroutines.launch

/** Optional customer fields + Save invoice. Reused by the mobile screen and the
 *  desktop "Customer" dialog. Saves the active cart sale (Guest if blank). */
@Composable
fun CustomerDetailsContent(businessId: String, businessName: String, onSaved: (String) -> Unit, modifier: Modifier = Modifier) {
    val repo = LocalBillingRepository.current
    val outbox = LocalOutboxSender.current
    val cart = LocalCart.current
    val settings by repo.invoiceSettingsFlow(businessId).collectAsState(null)
    val scope = rememberCoroutineScope()
    val isOnline by LocalConnectivity.current.isOnline.collectAsState(true)

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var gstin by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPayment by remember { mutableStateOf(false) }
    var linkInvoice by remember { mutableStateOf<Invoice?>(null) }

    /** [advance] = money taken now that does NOT settle the bill. */
    fun save(payment: PaymentChoice?, forLink: Boolean = false, advance: PartPayment? = null) {
        if (saving) return
        saving = true; error = null
        scope.launch {
            repo.createFromCart(businessId, settings, cart, customerName = name, customerPhone = phone, customerGstin = gstin, placeOfSupply = pos, payment = payment)
                .onSuccess { inv ->
                    // Queued as its own tender (never written into the invoice doc,
                    // which sync replaces), ordered behind the create it depends on.
                    advance?.let { a ->
                        repo.recordPaymentLocal(
                            businessId, inv.id, a.amount, a.method, a.reference,
                            note = "Advance at billing",
                        )
                    }
                    outbox.kick()
                    cart.completeActive(); saving = false
                    if (forLink) linkInvoice = inv else onSaved(inv.id)
                }.onFailure { saving = false; error = it.message ?: "Failed to save" }
        }
    }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Optional — leave blank to bill as Guest. Cart total ₹ ${formatDecimal2(cart.total())} (pre-tax).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Field("Customer name", name, KeyboardType.Text) { name = it } }
        item { Field("Phone", phone, KeyboardType.Phone) { phone = it } }
        item { Field("GSTIN (optional)", gstin, KeyboardType.Text) { gstin = it } }
        item { Field("Place of supply — state code (e.g. 33)", pos, KeyboardType.Number) { pos = it } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        item {
            Button(
                onClick = {
                    if (saving) return@Button
                    // Payment-mode step first — the sheet's callback runs the save.
                    showPayment = true
                },
                enabled = !saving, modifier = Modifier.fillMaxWidth()
            ) {
                if (saving) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                Text(if (saving) "Saving…" else "Save invoice")
            }
        }
    }

    if (showPayment) {
        PaymentSheet(
            total = cartPayableTotal(settings, cart, pos),
            upiVpa = BillingPrefs().counterUpiVpa.ifBlank { null } ?: settings?.upiVpa,
            businessName = businessName,
            isOnline = isOnline,
            onDismiss = { showPayment = false },
            onConfirm = { choice -> showPayment = false; save(choice) },
            onPaymentLink = {
                showPayment = false
                save(PaymentChoice(method = "payment_link", markPaid = false), forLink = true)
            },
            // Advance at registration: the bill saves unpaid, the amount rides along
            // as a tender, and the invoice detail opens showing the balance.
            onPartPayment = { p ->
                showPayment = false
                save(PaymentChoice(method = p.method, markPaid = false), advance = p)
            },
        )
    }

    linkInvoice?.let { inv ->
        PaymentLinkDialog(
            businessId = businessId,
            invoice = inv,
            onDone = { linkInvoice = null; onSaved(inv.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDetailsScreen(businessId: String, businessName: String, onBack: () -> Unit, onSaved: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Customer details") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
        }
    ) { inner ->
        CustomerDetailsContent(businessId = businessId, businessName = businessName, onSaved = onSaved, modifier = Modifier.padding(inner))
    }
}

@Composable
private fun Field(label: String, value: String, kb: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kb), modifier = Modifier.fillMaxWidth())
}
