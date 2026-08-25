package com.bnm.diagnosis.screens.business

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.api.BillingApi
import com.bnm.diagnosis.api.models.Business
import com.bnm.diagnosis.auth.AuthRepository
import com.bnm.diagnosis.components.LoadingScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessSelectorScreen(
    api: BillingApi,
    authRepository: AuthRepository,
    onBusinessSelected: (businessId: String, businessName: String) -> Unit,
    onSessionExpired: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var businesses by remember { mutableStateOf<List<Business>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            api.getBusinesses()
        }.onSuccess {
            businesses = it
            isLoading = false
            if (it.size == 1) {
                onBusinessSelected(it[0].id, it[0].name)
            }
        }.onFailure { err ->
            val msg = err.message ?: "Unknown error"
            if (msg.contains("Session expired", ignoreCase = true) ||
                msg.contains("401", ignoreCase = true)) {
                // Token expired — sign out and go back to login
                withContext(Dispatchers.Default) { authRepository.signOut() }
                onSessionExpired()
            } else {
                errorMessage = msg
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Select Business") })
        }
    ) { padding ->
        when {
            isLoading -> LoadingScreen("Loading businesses...")
            errorMessage != null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Failed to load businesses", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(errorMessage ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            businesses.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No businesses found", style = MaterialTheme.typography.titleMedium)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(businesses) { biz ->
                    BusinessCard(biz) { onBusinessSelected(biz.id, biz.name) }
                }
            }
        }
    }
}

@Composable
private fun BusinessCard(business: Business, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Store,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = business.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                business.category?.let { cat ->
                    Text(
                        text = cat,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (business.status != null) {
                StatusChip(business.status)
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (bg, fg) = when (status.lowercase()) {
        "active" -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
        "inactive" -> Color(0xFFF5F5F5) to Color(0xFF616161)
        else -> Color(0xFFF5F5F5) to Color(0xFF616161)
    }
    Surface(color = bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
        Text(
            text = status.replaceFirstChar { it.uppercase() },
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
