package com.bnm.diagnosis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.api.BillingApi
import com.bnm.diagnosis.api.LabApi
import com.bnm.diagnosis.api.LabHeartbeatResult
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
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.lab.SeedCatalog
import com.bnm.diagnosis.license.LicenseManager
import com.bnm.diagnosis.navigation.Screen
import com.bnm.diagnosis.screens.billing.BillingSettingsScreen
import com.bnm.diagnosis.screens.billing.CartScreen
import com.bnm.diagnosis.screens.billing.CreateInvoiceScreen
import com.bnm.diagnosis.screens.billing.CustomerDetailsScreen
import com.bnm.diagnosis.screens.billing.InvoiceDetailScreen
import com.bnm.diagnosis.screens.business.BusinessSelectorScreen
import com.bnm.diagnosis.screens.lab.CatalogScreen
import com.bnm.diagnosis.screens.lab.EmrInboxScreen
import com.bnm.diagnosis.screens.lab.LabHomeScreen
import com.bnm.diagnosis.screens.lab.NewOrderScreen
import com.bnm.diagnosis.screens.lab.OrderDetailScreen
import com.bnm.diagnosis.screens.lab.PatientsScreen
import com.bnm.diagnosis.screens.lab.ReferrersScreen
import com.bnm.diagnosis.sync.LabSyncEngine
import com.bnm.diagnosis.screens.license.ActivationScreen
import com.bnm.diagnosis.screens.license.LicenseDevicesScreen
import com.bnm.diagnosis.screens.login.LoginScreen
import com.bnm.diagnosis.screens.main.BillsScreen
import com.bnm.diagnosis.ui.theme.AppTheme
import com.bnm.diagnosis.ui.theme.ThemeManager
import kotlinx.coroutines.delay
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
    val labRepo = remember { LabRepository(database, ApiClient.json) }

    // First-run seed: ~40 standard tests + panels, only when the catalog is empty.
    LaunchedEffect(Unit) { runCatching { SeedCatalog.seedIfEmpty(labRepo) } }
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

    // ── License (P2): activation + device management via admin-lab ──
    val licenseManager = remember { LicenseManager() }
    val labApi = remember { LabApi(httpClient, deviceTokenProvider = { licenseManager.deviceToken() }) }

    // ── P3: additive lab sync (push/pull lab_entities + EMR inbox). The app is
    // the system of record — every phase is best-effort and never blocks UI. ──
    val labSync = remember { LabSyncEngine(database, ApiClient.json, labApi, licenseManager) }

    // Heartbeat on app start (when online) + on every reconnect: refresh the
    // license JWT; 403 device_revoked/license_inactive → persist the blocked
    // flag (banner + new-work gate); 401 = ignore (offline semantics unchanged).
    LaunchedEffect(Unit) {
        var first = true
        var wasOnline = false
        connectivity.isOnline.collect { online ->
            if (online && (first || !wasOnline) && licenseManager.deviceToken() != null) {
                labApi.heartbeat().onSuccess { hb ->
                    when (hb) {
                        is LabHeartbeatResult.Ok ->
                            licenseManager.applyHeartbeat(hb.licenseJwt, hb.mode, hb.seats, hb.expiresAt, hb.labName)
                        is LabHeartbeatResult.Blocked -> licenseManager.setBlocked(true)
                        LabHeartbeatResult.InvalidSession -> Unit
                    }
                }
            }
            first = false
            wasOnline = online
        }
    }

    // P3 lab sync sweeps: app start (after the license check above; online
    // only) + every reconnect. Failures are silent — next trigger retries.
    LaunchedEffect(Unit) {
        var first = true
        var wasOnline = false
        connectivity.isOnline.collect { online ->
            if (online && (first || !wasOnline) && licenseManager.deviceToken() != null) {
                labSync.syncNow()
            }
            first = false
            wasOnline = online
        }
    }
    // P3 lab sync: periodic sweep while the app is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60_000L)
            if (licenseManager.deviceToken() != null) labSync.syncNow()
        }
    }

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
            LocalLabRepository provides labRepo,
            LocalSyncEngine provides syncEngine,
            LocalOutboxSender provides outboxSender,
            LocalCart provides cart,
            LocalConnectivity provides connectivity,
        ) {
            val licState by licenseManager.state.collectAsState()

            // Entry gate (P2): unlicensed → ActivationScreen. The old billing
            // counter-pairing screen (LoginScreen) is intentionally UNREACHABLE
            // from the entry flow — license activation replaces pairing.
            key(isLoggedIn) {
                val navController = rememberNavController()
                // Accession of the order just registered (P1b) — shown as the
                // confirmation snackbar once LabHome is back on screen.
                var lastAccession by remember { mutableStateOf<String?>(null) }
                val startDestination = remember(isLoggedIn) {
                    if (!licenseManager.isLicensed()) Screen.Activation.route else Screen.LabHome.route
                }
                NavHost(navController = navController, startDestination = startDestination) {

                    composable(Screen.Activation.route) {
                        ActivationScreen(
                            labApi = labApi,
                            licenseManager = licenseManager,
                            onActivated = { a ->
                                // A license bound to a BNM business pre-selects it
                                // so the billing sync spine keeps working.
                                a.businessId?.takeIf { it.isNotBlank() }?.let {
                                    authRepository.saveSelectedBusiness(it, a.labName)
                                }
                            },
                            onEnterApp = {
                                navController.navigate(Screen.LabHome.route) {
                                    popUpTo(Screen.Activation.route) { inclusive = true }
                                }
                            },
                        )
                    }

                    composable(Screen.LicenseDevices.route) {
                        LicenseDevicesScreen(
                            labApi = labApi,
                            licenseManager = licenseManager,
                            onBack = { navController.popBackStack() },
                            onDeactivatedSelf = {
                                // Local license cleared (lab data untouched) →
                                // back to the activation entry.
                                navController.navigate(Screen.Activation.route) {
                                    popUpTo(Screen.Main.route) { inclusive = true }
                                }
                            },
                        )
                    }

                    composable(Screen.Login.route) {
                        // Legacy counter-pairing/owner-login screen — kept
                        // compiling but no longer part of the entry flow.
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
                                navController.navigate(Screen.LabHome.route) {
                                    popUpTo(Screen.BusinessSelector.route) { inclusive = true }
                                }
                            },
                            onSessionExpired = {}
                        )
                    }

                    // ── LIMS home (P1b) — the app's main surface. The legacy
                    // billing home (product grid + ≥840dp inline cart) is NOT
                    // registered anymore, which also closes the P2 gate hole:
                    // a license-blocked device now has no cart to save from —
                    // NewOrder is the only entry into new work, and it's gated.
                    composable(Screen.LabHome.route) {
                        // Licensed devices don't need a business pick: a licensed
                        // standalone lab runs fully offline-first (blank id) and a
                        // BNM-bound license carries its business_id.
                        val businessId = authRepository.getSelectedBusinessId()
                            ?: licState.businessId
                            ?: ""
                        val labName = licState.labName
                            ?: authRepository.getSelectedBusinessName()
                            ?: "BNM Diagnosis"

                        // First-sync once per device per business, gated on the invoice cursor.
                        LaunchedEffect(businessId) {
                            if (businessId.isBlank()) return@LaunchedEffect
                            val everSynced = repo.lastSyncedFlow(BillingRepository.INVOICE, businessId).first()
                            if (everSynced == null) runCatching { syncEngine.syncAll(businessId) }
                        }

                        Column(Modifier.fillMaxSize()) {
                            if (licState.blocked) LicenseBlockedBanner()
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                LabHomeScreen(
                                    labName = labName,
                                    licenseBlocked = licState.blocked,
                                    accessionNotice = lastAccession,
                                    onNoticeShown = { lastAccession = null },
                                    // License-blocked devices keep everything readable/
                                    // printable/exportable but can't START new work.
                                    onNewOrder = { if (!licState.blocked) navController.navigate(Screen.NewOrder.createRoute()) },
                                    onPatients = { navController.navigate(Screen.Patients.route) },
                                    onReferrers = { navController.navigate(Screen.Referrers.route) },
                                    onCatalog = { navController.navigate(Screen.Catalog.route) },
                                    onBills = { navController.navigate(Screen.Bills.route) },
                                    onSettings = { navController.navigate(Screen.Settings.route) },
                                    onEmrInbox = { navController.navigate(Screen.EmrInbox.route) },
                                    onOpenOrder = { id -> navController.navigate(Screen.LabOrderDetail.createRoute(id)) },
                                    onNewPatient = { navController.navigate(Screen.Patients.route) },
                                    onLicenseDevices = { navController.navigate(Screen.LicenseDevices.route) },
                                    licenseMode = licState.mode,
                                    licenseSeats = licState.seats,
                                    businessId = businessId,
                                    labSync = labSync,
                                )
                            }
                        }
                    }

                    composable(
                        route = Screen.NewOrder.route,
                        arguments = listOf(navArgument("emrId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        })
                    ) { backStack ->
                        if (licState.blocked) {
                            LicenseBlockedNotice(onBack = { navController.popBackStack() })
                            return@composable
                        }
                        val emrId = backStack.arguments?.let { NavType.StringType.get(it, "emrId") }
                        val businessId = authRepository.getSelectedBusinessId() ?: licState.businessId ?: ""
                        val labName = licState.labName ?: authRepository.getSelectedBusinessName() ?: "BNM Diagnosis"
                        NewOrderScreen(
                            businessId = businessId,
                            labName = labName,
                            onBack = { navController.popBackStack() },
                            onFinished = { accession, invoiceId ->
                                lastAccession = accession
                                navController.popBackStack() // → LabHome (snackbar shows the accession)
                                invoiceId?.let { navController.navigate(Screen.InvoiceDetail.createRoute(it)) }
                            },
                            emrOrderId = emrId,
                            onEmrRegistered = { id, order -> labSync.onEmrOrderRegistered(id, order) },
                        )
                    }

                    composable(Screen.EmrInbox.route) {
                        EmrInboxScreen(
                            onBack = { navController.popBackStack() },
                            onRegister = { emrId ->
                                if (!licState.blocked) {
                                    navController.navigate(Screen.NewOrder.createRoute(emrId))
                                }
                            },
                        )
                    }

                    composable(
                        route = Screen.LabOrderDetail.route,
                        arguments = listOf(navArgument("orderId") { type = NavType.StringType })
                    ) { backStack ->
                        val orderId = NavType.StringType.get(backStack.arguments!!, "orderId") ?: return@composable
                        val labName = licState.labName ?: authRepository.getSelectedBusinessName() ?: "BNM Diagnosis"
                        OrderDetailScreen(
                            orderId = orderId,
                            labName = labName,
                            onBack = { navController.popBackStack() },
                            onOpenInvoice = { id -> navController.navigate(Screen.InvoiceDetail.createRoute(id)) },
                        )
                    }

                    composable(Screen.Patients.route) {
                        PatientsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.Referrers.route) {
                        ReferrersScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.Catalog.route) {
                        CatalogScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.CreateInvoice.route) {
                        if (licState.blocked) {
                            LicenseBlockedNotice(onBack = { navController.popBackStack() })
                            return@composable
                        }
                        val businessId = authRepository.getSelectedBusinessId() ?: licState.businessId ?: ""
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
                        val businessId = authRepository.getSelectedBusinessId() ?: licState.businessId ?: ""
                        BillsScreen(
                            businessId = businessId,
                            onBack = { navController.popBackStack() },
                            onOpen = { id -> navController.navigate(Screen.InvoiceDetail.createRoute(id)) },
                        )
                    }

                    composable(Screen.Cart.route) {
                        if (licState.blocked) {
                            LicenseBlockedNotice(onBack = { navController.popBackStack() })
                            return@composable
                        }
                        val businessId = authRepository.getSelectedBusinessId() ?: licState.businessId ?: ""
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
                        if (licState.blocked) {
                            LicenseBlockedNotice(onBack = { navController.popBackStack() })
                            return@composable
                        }
                        val businessId = authRepository.getSelectedBusinessId() ?: licState.businessId ?: ""
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
                        val businessId = authRepository.getSelectedBusinessId() ?: licState.businessId ?: ""
                        InvoiceDetailScreen(api = api, businessId = businessId, invoiceId = invoiceId, onBack = { navController.popBackStack() })
                    }

                    composable(Screen.Settings.route) {
                        val businessId = authRepository.getSelectedBusinessId() ?: licState.businessId ?: ""
                        BillingSettingsScreen(
                            api = api,
                            authRepository = authRepository,
                            businessId = businessId,
                            onBack = { navController.popBackStack() },
                            onOpenLicense = { navController.navigate(Screen.LicenseDevices.route) },
                            labSync = labSync,
                            labName = licState.labName ?: authRepository.getSelectedBusinessName() ?: "BNM Diagnosis",
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-width banner shown when the license heartbeat reported this device as
 * revoked/inactive. Existing data stays readable, printable and exportable —
 * only CREATING new work is blocked.
 */
@Composable
private fun LicenseBlockedBanner() {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "This device's license was deactivated — contact BNM",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "You can still view, print and export everything; creating new work is disabled.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Shown instead of a new-work screen when the device's license is blocked. */
@Composable
private fun LicenseBlockedNotice(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "This device's license was deactivated — contact BNM",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Existing data stays readable, printable and exportable.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Button(onClick = onBack) { Text("Go back") }
        }
    }
}
