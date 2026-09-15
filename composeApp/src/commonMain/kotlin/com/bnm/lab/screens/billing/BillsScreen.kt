package com.bnm.lab.screens.billing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bnm.lab.navigation.BillsTab
import com.bnm.lab.revenue.RevenueRepository
import com.bnm.lab.screens.lab.RevenueDashboard
import com.bnm.lab.staff.LocalStaffSession
import kotlinx.coroutines.launch

/**
 * Bills and Revenue on one page.
 *
 * Everyone who bills sees the bill list. The owner — whoever holds
 * LabPermission.REVENUE — also gets a Revenue tab. Two tabs rather than one
 * scrolling page because each owns its own scroll: the bill list is a lazy
 * list and the dashboard a long column, and nesting one inside the other does
 * not measure.
 *
 * The tab is resolved from the LIVE session on every composition: if the owner
 * steps away and a technician signs in on this seat, the Revenue tab is gone
 * and its report is never built or queried.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    businessId: String,
    revenue: RevenueRepository,
    offlineEdition: Boolean,
    /** The tab asked for by the route (`bills?tab=revenue`), before permissions. */
    requestedTab: String?,
    onBack: () -> Unit,
    onOpen: (invoiceId: String) -> Unit,
) {
    val who by LocalStaffSession.current.current.collectAsState()
    val tabs = BillsTab.visibleTo(who)
    // Saved with the back-stack entry: open a bill, come back, same tab.
    var chosen by rememberSaveable { mutableStateOfSlug(requestedTab) }
    val tab = BillsTab.resolve(chosen, who)

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (BillsTab.REVENUE in tabs) "Bills & revenue" else "Bills") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (tabs.size > 1) {
                PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), modifier = Modifier.fillMaxWidth()) {
                    tabs.forEach { t ->
                        Tab(selected = t == tab, onClick = { chosen = t.slug }, text = { Text(t.label) })
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    BillsTab.BILLS -> InvoiceListScreen(businessId, onOpen)
                    BillsTab.REVENUE -> RevenueDashboard(
                        revenue = revenue,
                        businessId = businessId,
                        offlineEdition = offlineEdition,
                        onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    )
                }
            }
        }
    }
}

/** rememberSaveable needs a saveable value: the slug string, not the enum. */
private fun mutableStateOfSlug(slug: String?) = androidx.compose.runtime.mutableStateOf(slug ?: BillsTab.BILLS.slug)
