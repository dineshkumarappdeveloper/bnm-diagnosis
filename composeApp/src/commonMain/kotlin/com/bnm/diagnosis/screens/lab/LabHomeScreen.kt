package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.LocalLabRepository

/**
 * LIMS home — the app's main surface (replaces the billing home). Header
 * carries the READ-ONLY lab name (LicenseManager), today's pipeline counters
 * are tappable chips into the matching worklist tab, and the primary actions
 * fan out to registration / worklist / masters / bills / settings.
 * 100% offline (LabRepository).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LabHomeScreen(
    labName: String,
    licenseBlocked: Boolean,
    accessionNotice: String?,
    onNoticeShown: () -> Unit,
    onNewOrder: () -> Unit,
    onWorklist: (tab: String) -> Unit,
    onPatients: () -> Unit,
    onReferrers: () -> Unit,
    onCatalog: () -> Unit,
    onBills: () -> Unit,
    onSettings: () -> Unit,
) {
    val repo = LocalLabRepository.current
    val snackbar = remember { SnackbarHostState() }

    // Today's pipeline counters (orders CREATED today, per stage).
    var registered by remember { mutableStateOf(0L) }
    var inProgress by remember { mutableStateOf(0L) }
    var awaitingVerify by remember { mutableStateOf(0L) }
    var approved by remember { mutableStateOf(0L) }
    var reported by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        runCatching {
            registered = repo.countByStatusToday(LabStatus.REGISTERED) + repo.countByStatusToday(LabStatus.COLLECTED)
            inProgress = repo.countByStatusToday(LabStatus.IN_PROGRESS)
            awaitingVerify = repo.countByStatusToday(LabStatus.ENTERED)
            approved = repo.countByStatusToday(LabStatus.APPROVED)
            reported = repo.countByStatusToday(LabStatus.REPORTED)
        }
    }

    // Post-registration confirmation: the accession number, prominent.
    LaunchedEffect(accessionNotice) {
        if (accessionNotice != null) {
            snackbar.showSnackbar("Order registered — accession $accessionNotice")
            onNoticeShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("BNM Diagnosis", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(labName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Today counters → worklist tabs ──
            Text("Today", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("Registered", registered) { onWorklist(LabStatus.REGISTERED) }
                StatChip("In progress", inProgress) { onWorklist(LabStatus.IN_PROGRESS) }
                StatChip("Awaiting verify", awaitingVerify) { onWorklist(LabStatus.ENTERED) }
                StatChip("Approved", approved) { onWorklist(LabStatus.APPROVED) }
                StatChip("Reported", reported) { onWorklist(LabStatus.REPORTED) }
            }

            // ── Primary action: register a new order ──
            Button(
                onClick = onNewOrder,
                enabled = !licenseBlocked,
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("  New order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (licenseBlocked) {
                Text(
                    "License deactivated — registering new orders is disabled. Everything else stays available.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }

            // ── Navigation cards ──
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeCard("Worklist", "Pipeline by stage", Icons.AutoMirrored.Outlined.ListAlt) { onWorklist(LabStatus.REGISTERED) }
                HomeCard("Patients", "Search & manage", Icons.Outlined.PersonAddAlt) { onPatients() }
                HomeCard("Referrers", "Doctors & clinics", Icons.Outlined.People) { onReferrers() }
                HomeCard("Test catalog", "Tests, panels & prices", Icons.Outlined.Biotech) { onCatalog() }
                HomeCard("Bills", "GST invoices", Icons.AutoMirrored.Outlined.ReceiptLong) { onBills() }
                HomeCard("Settings", "Printer · License", Icons.Outlined.Settings) { onSettings() }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, count: Long, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun HomeCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(170.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
