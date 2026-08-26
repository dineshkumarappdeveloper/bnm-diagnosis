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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.chat.InvoiceBalance
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.util.formatDecimal2

/** Offline invoice list — reads the local `invoice` flow, with each bill's
 *  outstanding balance and a dues-only filter over the same data. */
@Composable
fun InvoiceListScreen(businessId: String, onOpen: (String) -> Unit) {
    val repo = LocalBillingRepository.current
    val bills by repo.invoiceBalancesFlow(businessId).collectAsState(emptyList())
    var duesOnly by remember { mutableStateOf(false) }

    // The dues surface: what the lab is still owed, over every bill on the
    // device. Computed from the same balances the rows show, so the header can
    // never disagree with the list under it.
    val dues = bills.filter { !it.isSettled && !it.isCancelled }
    val outstanding = dues.sumOf { it.balance }
    val shown = if (duesOnly) dues else bills

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            DuesHeader(
                outstanding = outstanding,
                count = dues.size,
                duesOnly = duesOnly,
                onToggle = { duesOnly = !duesOnly },
            )
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    if (duesOnly) "Nothing outstanding" else "No bills yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(shown, key = { it.invoice.id }) { bill -> InvoiceRow(bill, onOpen) }
    }
}

@Composable
private fun DuesHeader(outstanding: Double, count: Int, duesOnly: Boolean, onToggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Outstanding", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "₹ ${formatDecimal2(outstanding)}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (outstanding > 0.005) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        FilterChip(
            selected = duesOnly,
            onClick = onToggle,
            label = { Text(if (count == 1) "1 bill with dues" else "$count bills with dues") },
        )
    }
}

@Composable
private fun InvoiceRow(bill: InvoiceBalance, onOpen: (String) -> Unit) {
    val inv = bill.invoice
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(inv.id) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(inv.displayNumber, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                SyncChip(inv.isPendingSync || bill.hasQueuedPayment)
            }
            Text(inv.resolvedCustomer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("₹ ${formatDecimal2(inv.total)} · ${bill.label}", style = MaterialTheme.typography.bodyMedium)
            // The number the counter actually acts on — only when there is one.
            if (!bill.isSettled) {
                Text(
                    "Balance ₹ ${formatDecimal2(bill.balance)}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Per-card offline sync indicator — amber while queued, muted "Synced" once the
 *  outbox has reconciled the row with the server. A queued part payment counts:
 *  the bill itself may be synced while the money on it is not. */
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
