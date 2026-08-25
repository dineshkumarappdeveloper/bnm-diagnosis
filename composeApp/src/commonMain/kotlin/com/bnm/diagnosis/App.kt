package com.bnm.diagnosis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.api.BillingApi
import com.bnm.diagnosis.billing.CartStore
import com.bnm.diagnosis.billing.LocalCart
import com.bnm.diagnosis.auth.AuthRepository
import com.bnm.diagnosis.auth.FirebaseAuthManager
import com.bnm.diagnosis.auth.SessionManager
import com.bnm.diagnosis.chat.BillingOutboxSender
import com.bnm.diagnosis.chat.BillingRepository
import com.bnm.diagnosis.chat.BillingSyncManager
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.chat.LocalOutboxSender
import com.bnm.diagnosis.chat.LocalSyncEngine
import com.bnm.diagnosis.chat.SyncBus
import com.bnm.diagnosis.chat.SyncEngine
import com.bnm.diagnosis.connectivity.ConnectivityMonitor
import com.bnm.diagnosis.connectivity.LocalConnectivity
import com.bnm.diagnosis.db.createAppDatabase
import com.bnm.diagnosis.navigation.Screen
import com.bnm.diagnosis.screens.billing.BillingSettingsScreen
import com.bnm.diagnosis.screens.billing.CartScreen
import com.bnm.diagnosis.screens.billing.CreateInvoiceScreen
import com.bnm.diagnosis.screens.billing.CustomerDetailsScreen
import com.bnm.diagnosis.screens.billing.InvoiceDetailScreen
import com.bnm.diagnosis.screens.business.BusinessSelectorScreen
import com.bnm.diagnosis.screens.login.LoginScreen
import com.bnm.diagnosis.screens.main.BillingHomeScreen
import com.bnm.diagnosis.screens.main.BillsScreen
import com.bnm.diagnosis.ui.theme.AppTheme
import com.bnm.diagnosis.ui.theme.ThemeManager
import kotlinx.coroutines.flow.first

@Composable
fun App() {
    val firebaseAuthManager = remember { FirebaseAuthManager() }
    val sessionManager = remember { SessionManager() }
    val authRepository = remember { AuthRepository(firebaseAuthManager, sessionManager) }
    val httpClient = remember { ApiClient.create() }
    val api = remember {
        BillingApi(
            httpClient,
            tokenProvider = { authRepository.getAuthToken() },
            onUnauthorized = { authRepository.signOut() },
        )
    }
    val database = remember { createAppDatabase() }
    val repo = remember { BillingRepository(database, api) }
    val syncEngine = remember {
        SyncEngine().apply {
            register("customer", "Customers")     { repo.syncCustomerDirectory(it) }
            register("product", "Products")        { repo.syncProducts(it) }
            register("invoice", "Invoices")        { repo.syncInvoices(it) }
            register("invoice_setting", "Settings"){ repo.syncInvoiceSettings(it) }
            register("tax_rate", "Tax rates")      { repo.syncTaxRates(it) }
            register("billing_counter", "Counters"){ repo.syncCounters(it) }
        }
    }
    val connectivity = remember { ConnectivityMonitor() }
    val cart = remember { CartStore() }
    val outboxSender = remember { BillingOutboxSender(database, api) }
    val billingSync = remember { BillingSyncManager(outboxSender, connectivity) }

    // Drain the offline write outbox on start + every reconnect.
    LaunchedEffect(Unit) { billingSync.start(this) }
    // Push tickle → targeted pull (FCM-ready, not used in v1).
    LaunchedEffect(Unit) {
        SyncBus.requests.collect { t -> runCatching { syncEngine.sync(t.businessId, t.entities) } }
    }
    // Reconnect safety net → full pull for the selected business.
    LaunchedEffect(Unit) {
        var wasOnline = true
        connectivity.isOnline.collect { online ->
            if (online && !wasOnline) {
                authRepository.getSelectedBusinessId()?.let { bid -> runCatching { syncEngine.syncAll(bid) } }
            }
            wasOnline = online
        }
    }

    val isLoggedIn by authRepository.isLoggedIn.collectAsState()
    val themeManager = remember { ThemeManager() }
    val themeChoice by themeManager.choice.collectAsState()

    AppTheme(themeChoice = themeChoice, themeManager = themeManager) {
        CompositionLocalProvider(
            LocalBillingRepository provides repo,
            LocalSyncEngine provides syncEngine,
            LocalOutboxSender provides outboxSender,
            LocalCart provides cart,
            LocalConnectivity provides connectivity,
        ) {
            val savedBusinessId = authRepository.getSelectedBusinessId()
            val startDestination = when {
                !isLoggedIn -> Screen.Login.route
                !savedBusinessId.isNullOrEmpty() -> Screen.Main.route
                else -> Screen.BusinessSelector.route
            }

            key(isLoggedIn) {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = startDestination) {

                    composable(Screen.Login.route) {
                        LoginScreen(
                            authRepository = authRepository,
                            onLoggedIn = {
                                navController.navigate(Screen.BusinessSelector.route) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable(Screen.BusinessSelector.route) {
                        BusinessSelectorScreen(
                            api = api,
                            authRepository = authRepository,
                            onBusinessSelected = { bid, name ->
                                authRepository.saveSelectedBusiness(bid, name)
                                navController.navigate(Screen.Main.route) {
                                    popUpTo(Screen.BusinessSelector.route) { inclusive = true }
                                }
                            },
                            onSessionExpired = {}
                        )
                    }

                    composable(Screen.Main.route) {
                        val businessId = authRepository.getSelectedBusinessId() ?: run {
                            navController.navigate(Screen.BusinessSelector.route) {
                                popUpTo(Screen.Main.route) { inclusive = true }
                            }
                            return@composable
                        }
                        val businessName = authRepository.getSelectedBusinessName() ?: "Business"

                        // First-sync once per device per business, gated on the invoice cursor.
                        LaunchedEffect(businessId) {
                            val everSynced = repo.lastSyncedFlow(BillingRepository.INVOICE, businessId).first()
                            if (everSynced == null) runCatching { syncEngine.syncAll(businessId) }
                        }

                        BillingHomeScreen(
                            businessId = businessId,
                            businessName = businessName,
                            onOpenInvoice = { id -> navController.navigate(Screen.InvoiceDetail.createRoute(id)) },
                            onViewCart = { navController.navigate(Screen.Cart.route) },
                            onSaved = { id -> navController.navigate(Screen.InvoiceDetail.createRoute(id)) },
                            onBills = { navController.navigate(Screen.Bills.route) },
                            onSettings = { navController.navigate(Screen.Settings.route) },
                            onManual = { navController.navigate(Screen.CreateInvoice.route) },
                        )
                    }

                    composable(Screen.CreateInvoice.route) {
                        val businessId = authRepository.getSelectedBusinessId() ?: ""
                        CreateInvoiceScreen(
                            businessId = businessId,
                            onBack = { navController.popBackStack() },
                            onCreated = { id ->
                                navController.navigate(Screen.InvoiceDetail.createRoute(id)) {
                                    popUpTo(Screen.CreateInvoice.route) { inclusive = true }
                                }
                            },
                        )
                    }

                    composable(Screen.Bills.route) {
                        val businessId = authRepository.getSelectedBusinessId() ?: ""
                        BillsScreen(
                            businessId = businessId,
                            onBack = { navController.popBackStack() },
                            onOpen = { id -> navController.navigate(Screen.InvoiceDetail.createRoute(id)) },
                        )
                    }

                    composable(Screen.Cart.route) {
                        val businessId = authRepository.getSelectedBusinessId() ?: ""
                        CartScreen(
                            businessId = businessId,
                            businessName = authRepository.getSelectedBusinessName() ?: "Business",
                            onBack = { navController.popBackStack() },
                            onEnterDetails = { navController.navigate(Screen.CustomerDetails.route) },
                            onSaved = { id ->
                                navController.navigate(Screen.InvoiceDetail.createRoute(id)) { popUpTo(Screen.Main.route) }
                            },
                        )
                    }

                    composable(Screen.CustomerDetails.route) {
                        val businessId = authRepository.getSelectedBusinessId() ?: ""
                        CustomerDetailsScreen(
                            businessId = businessId,
                            businessName = authRepository.getSelectedBusinessName() ?: "Business",
                            onBack = { navController.popBackStack() },
                            onSaved = { id ->
                                navController.navigate(Screen.InvoiceDetail.createRoute(id)) { popUpTo(Screen.Main.route) }
                            },
                        )
                    }

                    composable(
                        route = Screen.InvoiceDetail.route,
                        arguments = listOf(navArgument("invoiceId") { type = NavType.StringType })
                    ) { backStack ->
                        val invoiceId = NavType.StringType.get(backStack.arguments!!, "invoiceId") ?: return@composable
                        val businessId = authRepository.getSelectedBusinessId() ?: ""
                        InvoiceDetailScreen(api = api, businessId = businessId, invoiceId = invoiceId, onBack = { navController.popBackStack() })
                    }

                    composable(Screen.Settings.route) {
                        val businessId = authRepository.getSelectedBusinessId() ?: ""
                        BillingSettingsScreen(api = api, authRepository = authRepository, businessId = businessId, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
