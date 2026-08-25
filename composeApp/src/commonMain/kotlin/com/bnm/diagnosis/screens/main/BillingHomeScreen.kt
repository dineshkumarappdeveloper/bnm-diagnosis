package com.bnm.diagnosis.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import com.bnm.diagnosis.billing.LocalCart
import com.bnm.diagnosis.chat.LocalOutboxSender
import com.bnm.diagnosis.chat.LocalSyncEngine
import com.bnm.diagnosis.screens.billing.CartContent
import com.bnm.diagnosis.screens.billing.CustomerDetailsContent
import com.bnm.diagnosis.screens.billing.HeldBillsRow
import com.bnm.diagnosis.screens.billing.InvoiceListScreen
import com.bnm.diagnosis.screens.billing.ProductGridContent
import com.bnm.diagnosis.util.formatDecimal2
import kotlinx.coroutines.launch

/**
 * Billing home. The product grid is the launcher. On wide screens (≥840dp) it's a
 * single 3-pane screen — [collapsible past-bills] · [grid] · [cart side panel] —
 * with no separate View-Cart page; on phones it's the grid + a bottom cart bar that
 * opens the cart screen. The held-bills chip row sits above the grid on both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingHomeScreen(
    businessId: String,
    businessName: String,
    onOpenInvoice: (String) -> Unit,
    onViewCart: () -> Unit,
    onSaved: (String) -> Unit,
    onBills: () -> Unit,
    onSettings: () -> Unit,
    onManual: () -> Unit,
) {
    val syncEngine = LocalSyncEngine.current
    val outbox = LocalOutboxSender.current
    val cart = LocalCart.current
    val scope = rememberCoroutineScope()
    val isSyncing by syncEngine.isSyncing.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var leftOpen by remember { mutableStateOf(false) }
    var showCustomer by remember { mutableStateOf(false) }

    BoxWithConstraints {
        val wide = maxWidth >= 840.dp
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(businessName, style = MaterialTheme.typography.titleMedium) },
                    actions = {
                        TextButton(onClick = { if (wide) leftOpen = !leftOpen else onBills() }) { Text("Bills") }
                        IconButton(onClick = { outbox.kick(); scope.launch { syncEngine.syncAll(businessId) } }, enabled = !isSyncing) {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync")
                        }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Manual invoice") }, onClick = { menuOpen = false; onManual() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onSettings() })
                        }
                    }
                )
            }
        ) { inner ->
            Box(Modifier.padding(inner).fillMaxSize()) {
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        if (leftOpen) {
                            Column(Modifier.width(300.dp).fillMaxHeight()) {
                                PaneHeader("Past bills")
                                Box(Modifier.weight(1f)) { InvoiceListScreen(businessId, onOpenInvoice) }
                            }
                            VDivider()
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            HeldBillsRow(cart)
                            ProductGridContent(businessId, Modifier.weight(1f))
                        }
                        VDivider()
                        Column(Modifier.width(340.dp).fillMaxHeight()) {
                            PaneHeader("Cart" + if (cart.count > 0) " (${cart.count})" else "")
                            CartContent(
                                businessId = businessId,
                                businessName = businessName,
                                onEnterDetails = { showCustomer = true },
                                onSaved = onSaved,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        HeldBillsRow(cart)
                        ProductGridContent(businessId, Modifier.weight(1f))
                        if (cart.count > 0) {
                            Surface(tonalElevation = 3.dp) {
                                Row(
                                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${cart.count} item${if (cart.count == 1) "" else "s"} · ₹ ${formatDecimal2(cart.total())}",
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Button(onClick = onViewCart) { Text("View cart") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Desktop "Customer details" → dialog (no separate screen on wide layouts).
    if (showCustomer) {
        Dialog(onDismissRequest = { showCustomer = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.width(440.dp)) {
                    PaneHeader("Customer details")
                    CustomerDetailsContent(
                        businessId = businessId,
                        businessName = businessName,
                        onSaved = { id -> showCustomer = false; onSaved(id) },
                    )
                }
            }
        }
    }
}

/** Mobile "Bills" route — the past-invoice list with a back button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(businessId: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Past bills") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) { InvoiceListScreen(businessId, onOpen) }
    }
}

@Composable
private fun PaneHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun VDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
}
