package com.bnm.diagnosis.screens.license

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.bnm.diagnosis.license.SubscriptionState
import com.bnm.diagnosis.license.subscriptionStatus
import com.bnm.diagnosis.api.LabHeartbeatResult
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.api.LabApi
import com.bnm.diagnosis.ui.theme.AppTheme
import com.bnm.diagnosis.api.LabSeatDevice
import com.bnm.diagnosis.license.LicenseManager
import kotlinx.coroutines.launch
import androidx.compose.material3.Button

/**
 * License & devices — Licensed-to card (lab name READ-ONLY), seat list with
 * self badge + per-device deactivation, and "Deactivate this device" (clears
 * the LOCAL license only; lab data is never deleted).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseDevicesScreen(
    labApi: LabApi,
    licenseManager: LicenseManager,
    onBack: () -> Unit,
    onDeactivatedSelf: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val license by licenseManager.state.collectAsState()

    var devices by remember { mutableStateOf<List<LabSeatDevice>>(emptyList()) }
    var selfId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busyDeviceId by remember { mutableStateOf<String?>(null) }
    var confirmDeactivate by remember { mutableStateOf<LabSeatDevice?>(null) }
    var confirmSelf by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    var checkingRenewal by remember { mutableStateOf(false) }
    var renewalMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadTick) {
        loading = true
        loadError = null
        labApi.listDevices()
            .onSuccess { info ->
                devices = info.devices
                selfId = info.selfId ?: license.deviceRowId
                loading = false
            }
            .onFailure {
                loading = false
                loadError = it.message ?: "Couldn't load devices"
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("License & devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Licensed-to card ──
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Licensed to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            license.labName ?: "—",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModeChip(license.mode)
                            Text(
                                "${license.seats} device${if (license.seats == 1) "" else "s"} allowed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (license.mode == LicenseManager.MODE_SUBSCRIPTION && !license.expiresAt.isNullOrBlank()) {
                            Text(
                                "Valid until ${license.expiresAt!!.take(10)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "The lab name is set by BNM and is read-only in the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Subscription / renewal (P4) — perpetual licences show nothing:
            // they are sold outright and must never nag.
            item {
                val sub = licenseManager.subscriptionStatus()
                if (sub.isSubscription) {
                    val tone = when (sub.state) {
                        SubscriptionState.EXPIRED -> AppTheme.colors.dangerSoft
                        SubscriptionState.IN_GRACE -> AppTheme.colors.warningSoft
                        SubscriptionState.EXPIRING_SOON -> AppTheme.colors.warningSoft
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = tone)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Subscription",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                sub.notice ?: "Active" + (sub.daysToExpiry?.let { " · renews in $it days" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Records always stay readable, printable and exportable — renewal only gates registering new orders.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            renewalMessage?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (checkingRenewal) return@Button
                                        checkingRenewal = true
                                        renewalMessage = null
                                        scope.launch {
                                            labApi.heartbeat()
                                                .onSuccess { hb ->
                                                    when (hb) {
                                                        is LabHeartbeatResult.Ok -> {
                                                            licenseManager.applyHeartbeat(
                                                                hb.licenseJwt, hb.mode, hb.seats, hb.expiresAt, hb.labName,
                                                            )
                                                            renewalMessage = "Licence refreshed."
                                                        }
                                                        is LabHeartbeatResult.Blocked ->
                                                            renewalMessage = hb.message
                                                        LabHeartbeatResult.InvalidSession ->
                                                            renewalMessage = "Session expired — reactivate this device."
                                                    }
                                                }
                                                .onFailure {
                                                    renewalMessage = "Couldn't reach BNM — try again when online."
                                                }
                                            checkingRenewal = false
                                        }
                                    },
                                    enabled = !checkingRenewal,
                                ) {
                                    if (checkingRenewal) {
                                        CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                                    }
                                    Text("Check for renewal")
                                }
                            }
                        }
                    }
                }
            }

            // ── Devices ──
            item { Text("Activated devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }

            if (loading) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (loadError != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Couldn't load devices — managing devices needs an internet connection.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(loadError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = { reloadTick++ }) { Text("Retry") }
                        }
                    }
                }
            } else {
                items(devices.size) { idx ->
                    val d = devices[idx]
                    val isSelf = d.id == selfId
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        d.deviceName ?: "Unnamed device",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (isSelf) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        ) {
                                            Text(
                                                "This device",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    listOfNotNull(
                                        d.platform,
                                        d.status?.takeIf { it.isNotBlank() && it != "active" },
                                        d.lastSeen?.let { "last seen ${it.take(10)}" },
                                    ).joinToString(" · ").ifBlank { "—" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!isSelf) {
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = { actionError = null; confirmDeactivate = d },
                                    enabled = busyDeviceId == null,
                                ) {
                                    if (busyDeviceId == d.id) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("Deactivate", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            actionError?.let {
                item {
                    Text(
                        "$it — deactivating a device needs an internet connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ── Deactivate this device ──
            item { Spacer(Modifier.height(8.dp)) }
            item {
                OutlinedButton(
                    onClick = { confirmSelf = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Deactivate this device") }
            }
        }
    }

    // ── Confirm: deactivate ANOTHER device ──
    confirmDeactivate?.let { d ->
        AlertDialog(
            onDismissRequest = { if (busyDeviceId == null) confirmDeactivate = null },
            title = { Text("Deactivate ${d.deviceName ?: "this device"}?") },
            text = { Text("It will lose its license seat and stop being able to create new work. Its local data is not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = d.id
                    confirmDeactivate = null
                    busyDeviceId = id
                    actionError = null
                    scope.launch {
                        labApi.deactivateDevice(id)
                            .onSuccess { busyDeviceId = null; reloadTick++ }
                            .onFailure { busyDeviceId = null; actionError = it.message ?: "Failed" }
                    }
                }) { Text("Deactivate", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeactivate = null }) { Text("Cancel") } },
        )
    }

    // ── Confirm: deactivate SELF (local license only) ──
    if (confirmSelf) {
        AlertDialog(
            onDismissRequest = { confirmSelf = false },
            title = { Text("Deactivate this device?") },
            text = {
                Text(
                    "This device will return to the activation screen and free up its license seat. " +
                        "Your lab data on this device is NOT deleted — patients, orders, results and bills stay on disk " +
                        "and reappear when you activate again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSelf = false
                    // Local-only clear: the server rejects self-deactivation
                    // (422) by design — this seat becomes replaceable after 7
                    // silent days, or the owner removes it from another device.
                    licenseManager.clearLicense()
                    onDeactivatedSelf()
                }) { Text("Deactivate", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSelf = false }) { Text("Cancel") } },
        )
    }
}
