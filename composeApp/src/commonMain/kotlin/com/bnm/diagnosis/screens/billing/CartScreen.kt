package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.api.models.Product
import com.bnm.diagnosis.billing.BillingPrefs
import com.bnm.diagnosis.billing.CustomLine
import com.bnm.diagnosis.billing.LocalCart
import com.bnm.diagnosis.billing.PaymentChoice
import com.bnm.diagnosis.billing.cartPayableTotal
import com.bnm.diagnosis.billing.createFromCart
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.chat.LocalOutboxSender
import com.bnm.diagnosis.connectivity.LocalConnectivity
import com.bnm.diagnosis.util.formatDecimal2
import kotlinx.coroutines.launch

/** Cart contents + actions (Hold / Customer details / Save immediately). Reused as
 *  the desktop right panel and the mobile cart screen body. */
@Composable
fun CartContent(
    businessId: String,
    businessName: String,
    onEnterDetails: () -> Unit,
    onSaved: (String) -> Unit,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val repo = LocalBillingRepository.current
    val outbox = LocalOutboxSender.current
    val cart = LocalCart.current
    val settings by repo.invoiceSettingsFlow(businessId).collectAsState(null)
    val scope = rememberCoroutineScope()
    val isOnline by LocalConnectivity.current.isOnline.collectAsState(true)
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<Invoice?>(null) }
    var showPayment by remember { mutableStateOf(false) }
    var linkInvoice by remember { mutableStateOf<Invoice?>(null) }
    val lines = cart.lines()
    val customLines = cart.customLines
    val notEmpty = lines.isNotEmpty() || customLines.isNotEmpty()

    // Save the active sale as a Guest bill with the chosen payment. Payment-link
    // saves route to the link dialog instead of the plain result popup.
    fun save(payment: PaymentChoice?, forLink: Boolean = false) {
        if (saving) return
        saving = true; error = null
        scope.launch {
            repo.createFromCart(businessId, settings, cart, customerName = "Guest", customerPhone = null, customerGstin = null, placeOfSupply = null, payment = payment)
                .onSuccess { inv ->
                    outbox.kick()
                    cart.completeActive(); saving = false
                    if (forLink) linkInvoice = inv else saved = inv
                }.onFailure { saving = false; error = it.message ?: "Failed to save" }
        }
    }

    Column(modifier.fillMaxSize()) {
        if (!notEmpty) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Cart is empty — add products", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lines.size) { i ->
                    val (p, qty) = lines[i]
                    CartLine(p, qty, onDec = { cart.setQty(p, qty - 1) }, onInc = { cart.setQty(p, qty + 1) })
                }
                items(customLines.size, key = { customLines[it].id }) { i ->
                    val c = customLines[i]
                    CustomCartLine(
                        c,
                        onDec = { cart.setCustomQty(c.id, c.qty - 1) },
                        onInc = { cart.setCustomQty(c.id, c.qty + 1) },
                        onRemove = { cart.removeCustom(c.id) },
                    )
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ── POS fulfillment picker (Dine-in / Take away / Delivery / …) ──
            // Config-driven from invoice_settings.pos_flows, edited ONLY in
            // BNMAdmin/BusinessStudio. Hidden entirely for single-flow shops
            // (grocery), so their screen is unchanged. Per-sale selection.
            val posOptions = settings?.enabledPosOptions ?: emptyList()
            // A stored pick that's no longer enabled (config changed mid-sale /
            // on a held bill) is ignored — resolution falls back to default →
            // first, mirroring createFromCart so display == what gets sent.
            val selectedKey = cart.active.fulfillment?.takeIf { k -> posOptions.any { it.first == k } }
                ?: settings?.posFlows?.default?.takeIf { d -> posOptions.any { it.first == d } }
                ?: posOptions.firstOrNull()?.first
            if (posOptions.size > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    posOptions.forEach { (key, opt) ->
                        FilterChip(
                            selected = key == selectedKey,
                            onClick = { cart.active.fulfillment = key },
                            label = { Text(opt.label.ifBlank { key }) },
                            enabled = !saving,
                        )
                    }
                }
            }
            // Delivery-style flows need a reachable customer (dispatch/notify) —
            // guest quick-save is disabled and the primary action collects details.
            val requiresCustomer = posOptions.firstOrNull { it.first == selectedKey }?.second?.requiresCustomer == true
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Items total (pre-tax)", style = MaterialTheme.typography.titleSmall)
                Text("₹ ${formatDecimal2(cart.total())}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { cart.hold() }, modifier = Modifier.weight(1f), enabled = !saving && notEmpty) { Text("Hold") }
                OutlinedButton(onClick = onEnterDetails, modifier = Modifier.weight(1f), enabled = !saving && notEmpty) { Text("Customer") }
            }
            Button(
                onClick = {
                    if (saving) return@Button
                    if (requiresCustomer) { onEnterDetails(); return@Button }
                    // Payment-mode step first — the sheet's callback runs the save.
                    showPayment = true
                },
                modifier = Modifier.fillMaxWidth(), enabled = !saving && notEmpty
            ) {
                if (saving) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(14.dp), strokeWidth = 2.dp)
                Text(if (requiresCustomer) "Add customer & save" else "Save immediately")
            }
        }
    }

    if (showPayment) {
        PaymentSheet(
            total = cartPayableTotal(settings, cart),
            // Counter VPA (pair + counter sync), falling back to the business-level
            // invoice-settings VPA if the counter has none.
            upiVpa = BillingPrefs().counterUpiVpa.ifBlank { null } ?: settings?.upiVpa,
            businessName = businessName,
            isOnline = isOnline,
            onDismiss = { showPayment = false },
            onConfirm = { choice -> showPayment = false; save(choice) },
            onPaymentLink = {
                showPayment = false
                save(PaymentChoice(method = "payment_link", markPaid = false), forLink = true)
            },
        )
    }

    linkInvoice?.let { inv ->
        PaymentLinkDialog(
            businessId = businessId,
            invoice = inv,
            onDone = { linkInvoice = null; saved = inv },
        )
    }

    saved?.let { inv ->
        SaveResultDialog(
            businessId = businessId,
            settings = settings,
            businessName = businessName,
            invoice = inv,
            onView = { saved = null; onSaved(inv.id) },
            onDone = { saved = null; onClose() },
        )
    }
}

/** A catalog-less line ("Add custom item"). Same layout as [CartLine] plus an
 *  explicit remove ×, since there is no product grid card to decrement it from. */
@Composable
private fun CustomCartLine(c: CustomLine, onDec: () -> Unit, onInc: () -> Unit, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "₹ ${formatDecimal2(c.unitPrice)} each · custom" + if (c.gstRate > 0) " · ${formatDecimal2(c.gstRate).removeSuffix(".00")}% GST" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDec) { Icon(Icons.Default.Remove, contentDescription = "−", modifier = Modifier.size(18.dp)) }
            Text("${c.qty}", fontWeight = FontWeight.Bold)
            IconButton(onClick = onInc) { Icon(Icons.Default.Add, contentDescription = "+", modifier = Modifier.size(18.dp)) }
            Text("₹ ${formatDecimal2(c.unitPrice * c.qty)}", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove item", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CartLine(p: Product, qty: Int, onDec: () -> Unit, onInc: () -> Unit) {
    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("₹ ${formatDecimal2(p.effectivePrice)} each", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDec) { Icon(Icons.Default.Remove, contentDescription = "−", modifier = Modifier.size(18.dp)) }
            Text("$qty", fontWeight = FontWeight.Bold)
            IconButton(onClick = onInc) { Icon(Icons.Default.Add, contentDescription = "+", modifier = Modifier.size(18.dp)) }
            Text("₹ ${formatDecimal2(p.effectivePrice * qty)}", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Mobile cart screen (the bottom cart-bar opens this). Desktop shows CartContent in a side panel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(businessId: String, businessName: String, onBack: () -> Unit, onEnterDetails: () -> Unit, onSaved: (String) -> Unit) {
    val cart = LocalCart.current
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Cart (${cart.count})") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
        }
    ) { inner ->
        CartContent(
            businessId = businessId,
            businessName = businessName,
            onEnterDetails = onEnterDetails,
            onSaved = onSaved,
            onClose = onBack,
            modifier = Modifier.padding(inner),
        )
    }
}
