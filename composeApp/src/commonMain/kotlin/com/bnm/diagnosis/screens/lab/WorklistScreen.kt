package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.components.StatusBadge
import com.bnm.diagnosis.lab.LabStatus
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.lab.WorklistEntry
import kotlinx.coroutines.flow.combine

/** One worklist tab: label + the statuses it aggregates. "Registered" folds in
 *  `collected` so sample-collected orders never vanish between tabs. */
private data class WorklistTab(val label: String, val statuses: List<String>)

private val TABS = listOf(
    WorklistTab("Registered", listOf(LabStatus.REGISTERED, LabStatus.COLLECTED)),
    WorklistTab("In progress", listOf(LabStatus.IN_PROGRESS)),
    WorklistTab("Entered", listOf(LabStatus.ENTERED)),
    WorklistTab("Verified", listOf(LabStatus.VERIFIED)),
    WorklistTab("Approved", listOf(LabStatus.APPROVED)),
    WorklistTab("Reported", listOf(LabStatus.REPORTED)),
)

/** Status-tabbed pipeline worklist. Rows: accession (mono/bold) · patient +
 *  age/sex · N tests · time. Fully reactive on the lab_orders flows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorklistScreen(
    initialStatus: String,
    onOpenOrder: (orderId: String) -> Unit,
    onBack: () -> Unit,
) {
    val repo = LocalLabRepository.current
    var tabIndex by remember {
        mutableStateOf(TABS.indexOfFirst { initialStatus in it.statuses }.takeIf { it >= 0 } ?: 0)
    }
    val tab = TABS[tabIndex]

    // Merge the tab's status flows into one reactive list (newest first).
    val entries by remember(tabIndex) {
        tab.statuses.map { repo.worklistFlow(it) }
            .reduce { acc, f -> acc.combine(f) { a, b -> a + b } }
    }.collectAsState(emptyList())
    val sorted = remember(entries) { entries.sortedByDescending { it.order.createdAt } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Worklist") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 12.dp) {
                TABS.forEachIndexed { i, t ->
                    Tab(selected = i == tabIndex, onClick = { tabIndex = i }, text = { Text(t.label) })
                }
            }
            if (sorted.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No ${tab.label.lowercase()} orders",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sorted, key = { it.order.id }) { e ->
                        WorklistRow(e, showStatus = tab.statuses.size > 1, onOpen = onOpenOrder)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorklistRow(e: WorklistEntry, showStatus: Boolean, onOpen: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(e.order.id) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.order.accessionNo,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!e.order.priority.equals("routine", ignoreCase = true)) StatusBadge(e.order.priority)
                    if (showStatus) StatusBadge(e.order.status)
                    Text(
                        shortTimeLabel(e.order.createdAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${e.patientName} · ${ageSexLabel(e.patientDob, e.patientAgeYears, e.patientSex)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${e.testCount} test${if (e.testCount == 1L) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
