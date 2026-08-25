package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import com.bnm.diagnosis.print.BtDeviceKind
import com.bnm.diagnosis.print.BtPrinter
import com.bnm.diagnosis.print.BtPrinterDevice
import com.bnm.diagnosis.print.BtScanState
import com.bnm.diagnosis.print.RequestBtPrinterPermissions

/**
 * Full-page Bluetooth printer picker. Auto-starts a scan on entry (through the permission gate)
 * and buckets results by [BtDeviceKind] so nearby TVs/earbuds/phones don't drown out the printer:
 * "Printers" (identified as printers), "Nearby devices" (named unknowns — many cheap ESC/POS
 * printers report no class), and a hidden "Everything else" bucket behind a Show-all switch.
 * Tap a row → [onSelect] — the caller persists the choice and closes the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtPrinterPickerPage(
    btPrinter: BtPrinter,
    selectedAddress: String,
    onSelect: (BtPrinterDevice) -> Unit,
    onBack: () -> Unit,
) {
    val scanState by btPrinter.scanState.collectAsState()
    val devices by btPrinter.discovered.collectAsState()
    var askPerm by remember { mutableStateOf(true) } // auto-start scan on entry
    var permDenied by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    if (askPerm) {
        RequestBtPrinterPermissions { granted ->
            askPerm = false
            if (granted) btPrinter.startScan() else permDenied = true
        }
    }
    DisposableEffect(Unit) { onDispose { btPrinter.stopScan() } }

    val printers = devices.filter { it.kind == BtDeviceKind.PRINTER }
    val nearby = devices.filter { it.kind == BtDeviceKind.UNKNOWN && !it.name.isNullOrBlank() }
    val hidden = devices.filter {
        it.kind == BtDeviceKind.OTHER || (it.kind == BtDeviceKind.UNKNOWN && it.name.isNullOrBlank())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Bluetooth printer") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (scanState == BtScanState.SCANNING) {
                        TextButton(onClick = { btPrinter.stopScan() }) { Text("Stop") }
                    } else {
                        TextButton(onClick = { askPerm = true }) { Text("Scan") }
                    }
                },
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Scan status ──
            val statusText = when {
                permDenied || scanState == BtScanState.PERMISSION_DENIED -> "Bluetooth permission denied"
                scanState == BtScanState.UNSUPPORTED -> "Bluetooth printing isn't available on this device — use Network (LAN)."
                scanState == BtScanState.BLUETOOTH_OFF -> "Turn on Bluetooth to print"
                scanState == BtScanState.ERROR -> "Bluetooth scan failed — tap Scan to try again"
                else -> null
            }
            statusText?.let {
                item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            if (scanState == BtScanState.SCANNING) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Scanning…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Printers ──
            if (printers.isNotEmpty()) {
                item { SectionHeader("Printers") }
                items(printers.size) { i ->
                    BtDeviceRow(printers[i], printers[i].address == selectedAddress) { onSelect(printers[i]) }
                }
            }

            // ── Nearby devices (named unknowns — could be printers with no device class) ──
            if (nearby.isNotEmpty()) {
                item { SectionHeader("Nearby devices") }
                items(nearby.size) { i ->
                    BtDeviceRow(nearby[i], nearby[i].address == selectedAddress) { onSelect(nearby[i]) }
                }
            }

            // ── Empty state ──
            if (printers.isEmpty() && nearby.isEmpty() && statusText == null) {
                item {
                    Text(
                        "No printers found yet — make sure the printer is on and in range.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            // ── Everything else (confidently-not-printers + unnamed unknowns), behind Show all ──
            if (showAll && hidden.isNotEmpty()) {
                item { SectionHeader("Everything else") }
                items(hidden.size) { i ->
                    BtDeviceRow(hidden[i], hidden[i].address == selectedAddress) { onSelect(hidden[i]) }
                }
            }
            if (hidden.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${hidden.size} hidden — TVs, speakers, audio & unnamed devices",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text("Show all", style = MaterialTheme.typography.labelLarge)
                        Switch(
                            checked = showAll,
                            onCheckedChange = { showAll = it },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun BtDeviceRow(dev: BtPrinterDevice, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dev.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(dev.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
