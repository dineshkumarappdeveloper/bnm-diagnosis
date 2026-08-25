package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bnm.diagnosis.api.models.Product
import com.bnm.diagnosis.billing.LocalCart
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.util.formatDecimal2

/** The product picker: search + category tabs + responsive product grid. Adds to
 *  the active cart sale. Reused as the mobile home body and the desktop centre pane. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductGridContent(businessId: String, modifier: Modifier = Modifier) {
    val repo = LocalBillingRepository.current
    val cart = LocalCart.current
    val products by repo.productsFlow(businessId).collectAsState(emptyList())

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var showCustomItem by remember { mutableStateOf(false) }

    val active = products.filter { it.isActive }
    val categories = remember(active) { listOf("All") + active.map { it.categoryLabel }.distinct().sorted() }
    val filtered = active.filter {
        (category == "All" || it.categoryLabel == category) &&
            (query.isBlank() || it.displayName.contains(query, ignoreCase = true))
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search products") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        )
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Free-style billing: always available, even with an empty/unsynced
            // catalog — the counter is never blocked by the product list.
            item {
                AssistChip(
                    onClick = { showCustomItem = true },
                    label = { Text("Custom item") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }
            items(categories) { cat ->
                FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat) })
            }
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (active.isEmpty()) "No products synced yet — sync, or bill a custom item." else "No matching products",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                // Responsive: as many ~150dp columns as the window fits — 2 on a
                // phone, many more on a wide desktop window (no giant cards).
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered, key = { it.id }) { p ->
                    ProductCard(p, cart.qty(p.id), onAdd = { cart.add(p) }, onDec = { cart.setQty(p, cart.qty(p.id) - 1) })
                }
            }
        }
    }

    if (showCustomItem) {
        AddCustomItemDialog(
            onDismiss = { showCustomItem = false },
            onAdd = { name, qty, price, gst ->
                cart.addCustom(name = name, qty = qty, unitPrice = price, gstRate = gst)
                showCustomItem = false
            },
        )
    }
}

/** "Add custom item" — bill something that isn't in the catalog (name + qty +
 *  unit price + GST %). Saved on the bill as a catalog-less line (no productId). */
@Composable
private fun AddCustomItemDialog(onDismiss: () -> Unit, onAdd: (name: String, qty: Int, unitPrice: Double, gstRate: Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    var gst by remember { mutableStateOf("0") }

    val qtyVal = qty.trim().toIntOrNull()
    val priceVal = price.trim().toDoubleOrNull()
    val gstVal = gst.trim().toDoubleOrNull()
    val valid = name.isNotBlank() && (qtyVal ?: 0) >= 1 && priceVal != null && priceVal >= 0.0 && gstVal != null && gstVal >= 0.0

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.widthIn(max = 440.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add custom item", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Bill an item that isn't in the catalog.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Item name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = qty, onValueChange = { qty = it },
                        label = { Text("Qty") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = price, onValueChange = { price = it },
                        label = { Text("Unit price ₹") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = gst, onValueChange = { gst = it },
                        label = { Text("GST %") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { if (valid) onAdd(name.trim(), qtyVal ?: 1, priceVal, gstVal) },
                        enabled = valid,
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("Add to cart") }
                }
            }
        }
    }
}

// Whole card is clickable = add one (the "+" behaviour). Only the − button
// decrements (it consumes the tap so the card's onClick doesn't also fire).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductCard(p: Product, qty: Int, onAdd: () -> Unit, onDec: () -> Unit) {
    Card(
        onClick = onAdd,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (qty > 0) 3.dp else 1.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(p.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                if (qty > 0) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).size(20.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("$qty", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
            Text(p.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(40.dp))
            Text("₹ ${formatDecimal2(p.effectivePrice)}" + if (p.effectiveGst > 0) " · ${p.effectiveGst.toInt()}% GST" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (qty > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("$qty in cart", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    // Red circular "remove one" button.
                    FilledIconButton(
                        onClick = onDec,
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Remove one", modifier = Modifier.size(18.dp))
                    }
                }
            } else {
                Text("Tap to add", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
