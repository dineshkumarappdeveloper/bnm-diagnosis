package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.billing.CartStore
import com.bnm.diagnosis.billing.Sale
import com.bnm.diagnosis.util.formatDecimal2

/**
 * Open-sales switcher. Shows a chip per open sale ("Bill N · ₹total"), the active
 * one highlighted; tap to switch, × to discard, "+ New" to start another. Only
 * appears once there's more than one sale (i.e. something is on hold).
 */
@Composable
fun HeldBillsRow(cart: CartStore, modifier: Modifier = Modifier) {
    // Hidden only when there's a single, empty sale (a clean start). Once items are
    // added, the row appears so "+ New" can park this bill and start another.
    if (cart.sales.size <= 1 && cart.count == 0) return
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(cart.sales, key = { it.id }) { s ->
            BillChip(
                sale = s,
                label = cart.label(s),
                selected = s.id == cart.activeId,
                onClick = { cart.switchTo(s.id) },
                onClose = { cart.discard(s.id) },
            )
        }
        item {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.height(34.dp),
            ) {
                Row(
                    Modifier.clickable { cart.newSale() }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("New", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun BillChip(sale: Sale, label: String, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(50), color = bg, modifier = Modifier.height(34.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clickable { onClick() }.padding(start = 12.dp, end = 4.dp).padding(vertical = 4.dp)) {
                Text(
                    label + if (sale.count > 0) " · ₹${formatDecimal2(sale.total())}" else " · empty",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = fg,
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Discard", tint = fg, modifier = Modifier.size(15.dp))
            }
        }
    }
}
