package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.util.formatDecimal2

/** Offline invoice list — reads the local `invoice` flow. */
@Composable
fun InvoiceListScreen(businessId: String, onOpen: (String) -> Unit) {
    val repo = LocalBillingRepository.current
    val invoices by repo.invoicesFlow(businessId).collectAsState(emptyList())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(invoices, key = { it.id }) { inv -> InvoiceRow(inv, onOpen) }
    }
}

@Composable
private fun InvoiceRow(inv: Invoice, onOpen: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(inv.id) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(inv.displayNumber, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                SyncChip(inv.isPendingSync)
            }
            Text(inv.resolvedCustomer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("₹ ${formatDecimal2(inv.total)} · ${inv.paymentLabel}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Per-card offline sync indicator — amber while queued, muted "Synced" once the
 *  outbox has reconciled the row with the server. */
@Composable
private fun SyncChip(pending: Boolean) {
    val bg = if (pending) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (pending) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            if (pending) "● Pending sync" else "✓ Synced",
            style = MaterialTheme.typography.labelSmall, color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
