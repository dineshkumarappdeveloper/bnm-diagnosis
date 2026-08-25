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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.billing.GstLine
import com.bnm.diagnosis.billing.GstTaxEngine
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.chat.LocalOutboxSender
import com.bnm.diagnosis.util.formatDecimal2
import kotlinx.coroutines.launch

private class LineState(qty: String = "1", rate: String = "", gst: String = "18") {
    var description by mutableStateOf("")
    var hsn by mutableStateOf("")
    var qty by mutableStateOf(qty)
    var rate by mutableStateOf(rate)
    var gst by mutableStateOf(gst)
    fun toGstLine() = GstLine(
        description = description.trim(),
        hsn = hsn.trim().ifBlank { null },
        quantity = qty.toDoubleOrNull() ?: 0.0,
        rate = rate.toDoubleOrNull() ?: 0.0,
        gstRate = gst.toDoubleOrNull() ?: 0.0,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateInvoiceScreen(businessId: String, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val repo = LocalBillingRepository.current
    val outbox = LocalOutboxSender.current
    val scope = rememberCoroutineScope()
    val settings by repo.invoiceSettingsFlow(businessId).collectAsState(null)

    var hasSeries by remember { mutableStateOf<Boolean?>(null) }
    var seriesLabel by remember { mutableStateOf("") }
    LaunchedEffect(businessId) {
        val s = repo.deviceSeries(businessId)
        hasSeries = s != null
        seriesLabel = s?.let { "${it.prefix}-${it.series} (FY ${it.fy})" } ?: ""
    }

    var customerName by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var customerGstin by remember { mutableStateOf("") }
    var placeOfSupply by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf(LineState()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val supplierState = settings?.taxId?.trim()?.take(2)
    val gst = remember(lines.map { "${it.qty}|${it.rate}|${it.gst}" }, placeOfSupply, supplierState) {
        GstTaxEngine.compute(lines.map { it.toGstLine() }, supplierState, placeOfSupply.trim().ifBlank { null })
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("New invoice") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.Close, contentDescription = "Back") }
            })
        }
    ) { inner ->
        if (hasSeries == false) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No billing series on this device", style = MaterialTheme.typography.titleMedium)
                    Text("Register a counter series in Settings first.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Text("Series: $seriesLabel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            item { Field("Customer name (walk-in ok)", customerName) { customerName = it } }
            item { Field("Phone", customerPhone, KeyboardType.Phone) { customerPhone = it } }
            item { Field("Customer GSTIN (optional)", customerGstin) { customerGstin = it } }
            item { Field("Place of supply — state code (e.g. 33)", placeOfSupply, KeyboardType.Number) { placeOfSupply = it } }
            item { Text("Line items", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }

            itemsIndexed(lines) { idx, line ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Item ${idx + 1}", style = MaterialTheme.typography.labelMedium)
                            if (lines.size > 1) IconButton(onClick = { lines.removeAt(idx) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                        Field("Description", line.description) { line.description = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { Field("Qty", line.qty, KeyboardType.Number) { line.qty = it } }
                            Box(Modifier.weight(1f)) { Field("Rate", line.rate, KeyboardType.Decimal) { line.rate = it } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { Field("HSN/SAC", line.hsn) { line.hsn = it } }
                            Box(Modifier.weight(1f)) { Field("GST %", line.gst, KeyboardType.Number) { line.gst = it } }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { lines.add(LineState()) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Text("Add item")
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TotalRow("Subtotal", gst.subtotal)
                        gst.taxBreakup.forEach { b ->
                            if (b.igst > 0) TotalRow("IGST @ ${b.rate.toInt()}%", b.igst)
                            else { TotalRow("CGST", b.cgst); TotalRow("SGST", b.sgst) }
                        }
                        TotalRow("Total", gst.total, bold = true)
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            item {
                Button(
                    onClick = {
                        if (saving) return@Button
                        val valid = lines.any { (it.rate.toDoubleOrNull() ?: 0.0) > 0 }
                        if (!valid) { error = "Add at least one item with a rate"; return@Button }
                        saving = true; error = null
                        scope.launch {
                            repo.createInvoiceLocal(
                                businessId = businessId,
                                supplierStateCode = supplierState,
                                placeOfSupply = placeOfSupply.trim().ifBlank { null },
                                customerName = customerName.trim().ifBlank { null },
                                customerPhone = customerPhone.trim().ifBlank { null },
                                customerGstin = customerGstin.trim().ifBlank { null },
                                lines = lines.map { it.toGstLine() }.filter { it.rate > 0 },
                                dueDays = settings?.dueDays ?: 7,
                            ).onSuccess { inv ->
                                outbox.kick()
                                saving = false; onCreated(inv.id)
                            }.onFailure { saving = false; error = it.message ?: "Failed to create invoice" }
                        }
                    },
                    enabled = !saving, modifier = Modifier.fillMaxWidth()
                ) {
                    if (saving) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text(if (saving) "Saving…" else "Create invoice · ₹ ${formatDecimal2(gst.total)}")
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, kb: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kb), modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TotalRow(label: String, amount: Double, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text("₹ ${formatDecimal2(amount)}", style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
