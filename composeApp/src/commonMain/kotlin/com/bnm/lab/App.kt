package com.bnm.lab

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
import com.bnm.lab.api.ApiClient
import com.bnm.lab.api.BillingApi
import com.bnm.lab.api.LabApi
import com.bnm.lab.api.LabHeartbeatResult
import com.bnm.lab.api.LocalLabApi
import com.bnm.lab.billing.CartStore
import com.bnm.lab.billing.LocalCart
import com.bnm.lab.billing.ensureLabBillingSeries
import com.bnm.lab.auth.AuthRepository
import com.bnm.lab.auth.FirebaseAuthManager
import com.bnm.lab.auth.SessionManager
import com.bnm.lab.chat.BillingOutboxSender
import com.bnm.lab.chat.BillingRepository
import com.bnm.lab.chat.BillingSyncManager
import com.bnm.lab.chat.LocalBillingRepository
import com.bnm.lab.chat.LocalOutboxSender
import com.bnm.lab.chat.LocalSyncEngine
import com.bnm.lab.chat.SyncBus
import com.bnm.lab.chat.SyncEngine
import com.bnm.lab.connectivity.ConnectivityMonitor
import com.bnm.lab.connectivity.LocalConnectivity
import com.bnm.lab.db.createAppDatabase
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LocalLabRepository
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.license.OfflinePolicy
import com.bnm.lab.navigation.GuardedRoute
import com.bnm.lab.navigation.RouteGuardEffect
import com.bnm.lab.navigation.Screen
import com.bnm.lab.screens.billing.BillingSettingsScreen
import com.bnm.lab.screens.billing.CartScreen
import com.bnm.lab.screens.billing.CreateInvoiceScreen
import com.bnm.lab.screens.billing.CustomerDetailsScreen
import com.bnm.lab.screens.billing.InvoiceDetailScreen
import com.bnm.lab.screens.business.BusinessSelectorScreen
import com.bnm.lab.screens.lab.CatalogScreen
import com.bnm.lab.sync.MasterCatalogImporter
import com.bnm.lab.screens.lab.EmrInboxScreen
import com.bnm.lab.screens.lab.LabHomeScreen
import com.bnm.lab.screens.lab.NewOrderScreen
import com.bnm.lab.screens.lab.OrderDetailScreen
import com.bnm.lab.screens.lab.PatientsScreen
import com.bnm.lab.screens.lab.ReferrersScreen
import com.bnm.lab.sync.LabSyncEngine
import com.bnm.lab.screens.license.ActivationScreen
import com.bnm.lab.screens.license.LicenseDevicesScreen
import com.bnm.lab.screens.login.LoginScreen
import com.bnm.lab.screens.staff.MySignatureScreen
import com.bnm.lab.screens.staff.StaffScreen
import com.bnm.lab.screens.staff.StaffSignInScreen
import com.bnm.lab.staff.LocalStaffRepository
import com.bnm.lab.staff.LocalStaffSession
import com.bnm.lab.staff.StaffRepository
import com.bnm.lab.staff.StaffSession
import com.bnm.lab.screens.main.BillsScreen
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.ui.theme.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.bnm.lab.license.subscriptionStatus
import com.bnm.lab.report.ReportAssembler
import com.bnm.lab.report.ReportUploader
import com.bnm.lab.update.quitForUpdate
import com.bnm.lab.billing.PrintProfiles
import com.bnm.lab.screens.settings.InstrumentsScreen
import com.bnm.lab.screens.settings.PrintSettingsScreen
import com.bnm.lab.billing.PrintKind
import com.bnm.lab.print.BtPrinter
import com.bnm.lab.screens.billing.BtPrinterPickerPage

@Composable
fun App() {
    val firebaseAuthManager = remember { FirebaseAuthManager() }
    val sessionManager = remember { SessionManager() }
    val authRepository = remember { AuthRepository(firebaseAuthManager, sessionManager) }
    val httpClient = remember { ApiClient.create() }
    // License first: billing calls authenticate with the lab-device session
    // token (admin-billing accepts lab_device sessions) — the legacy counter
    // token only exists on a seat paired via the old BNMBilling flow.
    // Seed the report print profile from the previously-shared printer
    // settings, once — an upgrade must not silently unconfigure a lab.
    remember { PrintProfiles.migrateOnce() }

    val licenseManager = remember { LicenseManager() }
    val api = remember {
        BillingApi(
            httpClient,
            tokenProvider = { licenseManager.deviceToken() ?: authRepository.getAuthToken() },
            onUnauthorized = { authRepository.signOut() },
        )
    }
    val database = remember { createAppDatabase() }
    val repo = remember { BillingRepository(database, api) }
    val labRepo = remember { LabRepository(database, ApiClient.json) }
    // ── P4: staff accounts + local RBAC. The session is in-memory ONLY — a
    // restarted seat comes back to the sign-in grid. ──
    val staffRepo = remember { StaffRepository(database, ApiClient.json) }
    val staffSession = remember { StaffSession() }
    val licenseState by licenseManager.state.collectAsState()

    // Old result rows carry only the signatory's NAME; give them the person's
    // id so a reprint follows a rename (the seeded "Lab Owner" above all).
    LaunchedEffect(Unit) {
        runCatching { labRepo.backfillSignatoryIds(staffRepo.listAll(), StaffRepository.DEFAULT_OWNER_ID) }
    }
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

    // ── License (P2): activation + device management via admin-lab
    // (licenseManager itself is created above, before BillingApi) ──
    val labApi = remember { LabApi(httpClient, deviceTokenProvider = { licenseManager.deviceToken() }) }

    // Catalog bootstrap — the MASTER catalog, not a bundled starter set.
    //
    // An OFFLINE lab has no business whose `products` it could sync, so the
    // global `lab_test_catalog` is where its menu comes from. This runs only
    // when the catalog is EMPTY, which is exactly two moments: the first launch
    // after activation, and the first launch after a tenant-switch wipe. Both
    // sit right next to the one online moment a standalone licence has.
    //
    // It replaced a bundled ~40-test starter catalog. That set overlapped the
    // master catalog on 22 codes and was the THINNER copy of each (its CBC
    // carried 13 analytes against the master's 22), so a lab that seeded it and
    // then pulled kept the poorer version of every overlapping test.
    //
    // Failure is deliberately quiet: the catalog screen's "Pull master catalog"
    // is the retry, and a lab with no connection at first launch is not blocked
    // from anything else.
    LaunchedEffect(licenseState.licensed, licenseState.edition) {
        if (!licenseState.licensed || !licenseState.isStandalone) return@LaunchedEffect
        if (licenseManager.deviceToken().isNullOrEmpty()) return@LaunchedEffect
        if (runCatching { labRepo.countTests() }.getOrDefault(1L) > 0L) return@LaunchedEffect
        runCatching {
            MasterCatalogImporter(database, ApiClient.json)
                .apply(labApi.masterCatalog().getOrThrow())
        }
    }

    // ── P3: additive lab sync (push/pull lab_entities + EMR inbox). The app is
    // the system of record — every phase is best-effort and never blocks UI. ──
    // Report publishing rides the normal sync sweep: printing works offline, and
    // the PDF reaches the server (making the printed QR resolvable) whenever
    // connectivity next returns. Standalone licences never get here — both the
    // engine and ReportUploader return early for them.
    val reportUploader = remember(labRepo, staffRepo, labApi) {
        ReportUploader(labRepo, labApi, ReportAssembler(labRepo, staffRepo))
    }
    val labSync = remember {
        LabSyncEngine(database, ApiClient.json, labApi, licenseManager,
            drainReports = { reportUploader.drain() })
    }

    // ── I0/I1: analyzer interfacing — always-on serial/TCP listeners that feed
    // instrument results (and measured histograms) straight into lab orders.
    // Purely local: no licence/connectivity gate, results sync later as usual.
    val instrumentEngine = remember { InstrumentEngine(database, labRepo, ApiClient.json) }
    LaunchedEffect(Unit) { instrumentEngine.start() }
    // A tenant switch stops the listeners before wiping (ActivationScreen's
    // onBeforeTenantWipe); bring them back once a (new) licence is in place.
    // Keyed on the licence identity, not every state emission — heartbeats
    // must not churn open serial ports.
    LaunchedEffect(Unit) {
        var lastIdentity: String? = null
        licenseManager.state.collect { st ->
            val identity = if (st.licensed) "${st.businessId}|${st.labName}" else null
            if (identity != null && lastIdentity != null && identity != lastIdentity) {
                instrumentEngine.restartAll()
            }
            lastIdentity = identity
        }
    }

    // Heartbeat on app start (when online) + on every reconnect: refresh the
    // license JWT; 403 device_revoked/license_inactive → persist the blocked
    // flag (banner + new-work gate); 401 = ignore (offline semantics unchanged).
    LaunchedEffect(Unit) {
        var first = true
        var wasOnline = false
        connectivity.isOnline.collect { online ->
            if (online && (first || !wasOnline) && licenseManager.deviceToken() != null &&
                OfflinePolicy.allowsHeartbeat(licenseManager.state.value.isStandalone)
            ) {
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
            if (online && (first || !wasOnline) && licenseManager.deviceToken() != null &&
                OfflinePolicy.allowsSync(licenseManager.state.value.isStandalone)
            ) {
                // Bind this device's invoice numbering series before the sweep
                // (no-op once bound) so billing works even if the operator's
                // first bill happens offline later.
                licenseManager.state.value.businessId?.takeIf { it.isNotBlank() }?.let { biz ->
                    runCatching { ensureLabBillingSeries(labApi, repo, biz) }
                }
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
            if (licenseManager.deviceToken() != null &&
                OfflinePolicy.allowsSync(licenseManager.state.value.isStandalone)
            ) labSync.syncNow()
        }
    }

    // Drain the offline write outbox on start + every reconnect — CONNECTED
    // editions only. An offline licence keeps its bills entirely on this PC.
    LaunchedEffect(Unit) {
        // Reactive, not a one-shot check: a lab that migrates from the offline
        // edition mid-session must start draining without a restart.
        val scope = this
        var started = false
        licenseManager.state.collect { st ->
            if (!started && OfflinePolicy.allowsBillingSync(st.isStandalone)) {
                started = true
                billingSync.start(scope)
            }
        }
    }
    // A migration to the connected edition syncs at once rather than waiting
    // for the five-minute sweep, and the notice below tells the lab it is
    // happening.
    LaunchedEffect(Unit) {
        var wasStandalone: Boolean? = null
        licenseManager.state.collect { st ->
            val now = st.isStandalone
            if (wasStandalone == true && !now && licenseManager.deviceToken() != null) {
                runCatching { labSync.syncNow() }
            }
            wasStandalone = now
        }
    }
    // Push tickle → targeted pull (FCM-ready, not used in v1).
    LaunchedEffect(Unit) {
        SyncBus.requests.collect { t ->
            if (OfflinePolicy.allowsBillingSync(licenseManager.state.value.isStandalone)) {
                runCatching { syncEngine.sync(t.businessId, t.entities) }
            }
        }
    }
    // Reconnect safety net → full pull for the selected business.
    LaunchedEffect(Unit) {
        var wasOnline = true
        connectivity.isOnline.collect { online ->
            if (online && !wasOnline && OfflinePolicy.allowsBillingSync(licenseManager.state.value.isStandalone)) {
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
            LocalStaffRepository provides staffRepo,
            LocalStaffSession provides staffSession,
            LocalSyncEngine provides syncEngine,
            LocalOutboxSender provides outboxSender,
            LocalCart provides cart,
            LocalConnectivity provides connectivity,
            LocalLabApi provides labApi,
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
                // P4: who is at this seat. Entry flow = license check → staff
                // sign-in → LabHome; the license-blocked banner/notice semantics
                // below are unchanged (a blocked device still signs people in and
                // stays fully readable/printable/exportable).
                val signedInStaff by staffSession.current.collectAsState()
                val startDestination = remember(isLoggedIn) {
                    if (!licenseManager.isLicensed()) Screen.Activation.route else Screen.StaffSignIn.route
                }

                // "Switch user", "Sign out" and the auto-lock are one action:
                // drop the in-memory session (lab data untouched) and go back to
                // the sign-in grid with nothing left on the back stack.
                fun lockSeat() {
                    staffSession.signOut()
                    navController.navigate(Screen.StaffSignIn.route) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }

                // Cheap idle tracking: a stamp on every destination change, plus
                // the explicit touches the results workbench fires. No per-screen
                // listeners, no recomposition churn.
                LaunchedEffect(navController) {
                    navController.currentBackStackEntryFlow.collect { staffSession.touch() }
                }
                // Auto-lock: one low-frequency poll; 15 min of nothing → sign-in.
                LaunchedEffect(navController) {
                    while (true) {
                        delay(AUTO_LOCK_POLL_MS)
                        if (staffSession.isIdle()) lockSeat()
                    }
                }

                // Backstop for the money/staff gates: pops any destination the
                // signed-in person may not be on — including a back stack restored
                // across a "switch user", which GuardedRoute alone cannot catch.
                RouteGuardEffect(navController, signedInStaff)

                NavHost(navController = navController, startDestination = startDestination) {

                    // ── Seat sign-in gate (P4) ──
                    composable(Screen.StaffSignIn.route) {
                        StaffSignInScreen(
                            labName = licState.labName
                                ?: authRepository.getSelectedBusinessName()
                                ?: "BNM Lab",
                            onSignedIn = { person ->
                                staffSession.signIn(person)
                                navController.navigate(Screen.LabHome.route) {
                                    popUpTo(Screen.StaffSignIn.route) { inclusive = true }
                                }
                            },
                            // Deliberately reachable with nobody signed in: it is
                            // where a lab checks its licence, migrates edition, or
                            // hands this PC's seat back — and the sign-in grid is
                            // the only screen a locked-out lab can see.
                            onLicense = { navController.navigate(Screen.LicenseDevices.route) },
                        )
                    }

                    // ── Staff & roles (owner only; guarded again inside) ──
                    composable(Screen.Staff.route) {
                        GuardedRoute(Screen.Staff.route, signedInStaff,
                            onBack = { navController.popBackStack() }) {
                            StaffScreen(onBack = { navController.popBackStack() })
                        }
                    }
                    // Self-service: the screen itself asks for a sign-in when there is none.
                    composable(Screen.MySignature.route) {
                        MySignatureScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.Activation.route) {
                        ActivationScreen(
                            labApi = labApi,
                            licenseManager = licenseManager,
                            onBeforeTenantWipe = { instrumentEngine.stopAll() },
                            onActivated = { a ->
                                // A license bound to a BNM business pre-selects it
                                // so the billing sync spine keeps working.
                                a.businessId?.takeIf { it.isNotBlank() }?.let {
                                    authRepository.saveSelectedBusiness(it, a.labName)
                                }
                            },
                            onEnterApp = {
                                // Licensed now → the staff sign-in gate, not straight in.
                                navController.navigate(Screen.StaffSignIn.route) {
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
                                // back to the activation entry, with NOTHING
                                // behind it: this screen is now reachable from
                                // the sign-in grid too, and backing into a
                                // licence screen that has no licence is a trap.
                                navController.navigate(Screen.Activation.route) {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                    launchSingleTop = true
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
                            ?: "BNM Lab"

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
                    subscriptionNotice = run {
                        val sub = licenseManager.subscriptionStatus()
                        sub.notice
                    },
                    subscriptionUrgent = licenseManager.subscriptionStatus().state in setOf(
                        com.bnm.lab.license.SubscriptionState.IN_GRACE,
                        com.bnm.lab.license.SubscriptionState.EXPIRED,
                    ),
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
                                    licenseMode = licState.mode,
                                    businessId = businessId,
                                    labSync = labSync,
                                    signedInStaff = signedInStaff,
                                    onSwitchUser = { lockSeat() },
                                    onSignOut = { lockSeat() },
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
                        val labName = licState.labName ?: authRepository.getSelectedBusinessName() ?: "BNM Lab"
                        NewOrderScreen(
                            businessId = businessId,
                            labName = labName,
                            onBack = { navController.popBackStack() },
                            onFinished = { accession, invoiceId ->
                                lastAccession = accession
                                // Home, whatever opened the registration (the EMR inbox
                                // does too) — LabHome is always on the stack once signed
                                // in; the snackbar there shows the accession.
                                if (!navController.popBackStack(Screen.LabHome.route, inclusive = false)) {
                                    navController.navigate(Screen.LabHome.route) { launchSingleTop = true }
                                }
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
                        val labName = licState.labName ?: authRepository.getSelectedBusinessName() ?: "BNM Lab"
                        OrderDetailScreen(
                            orderId = orderId,
                            businessId = authRepository.getSelectedBusinessId() ?: licState.businessId ?: "",
                            labName = labName,
                            onBack = { navController.popBackStack() },
                            onOpenInvoice = { id -> navController.navigate(Screen.InvoiceDetail.createRoute(id)) },
                        )
                    }

                    composable(Screen.Patients.route) {
                        PatientsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Screen.Referrers.route) {
                        GuardedRoute(Screen.Referrers.route, signedInStaff,
                            onBack = { navController.popBackStack() }) {
                            ReferrersScreen(
                                onBack = { navController.popBackStack() },
                                businessId = licState.businessId.orEmpty(),
                            )
                        }
                    }

                    composable(Screen.PrintSettings.route) {
                        PrintSettingsScreen(
                            onBack = { navController.popBackStack() },
                            labName = licState.labName
                                ?: authRepository.getSelectedBusinessName()
                                ?: "BNM Lab",
                            // A separate route, not an in-place swap: returning
                            // recomposes the settings screen, so the newly picked
                            // printer name is read back from the profile straight
                            // away instead of only after leaving and re-entering.
                            onPickBluetooth = { kind ->
                                navController.navigate(Screen.BtPrinterPicker.createRoute(kind.slug))
                            },
                        )
                    }

                    composable(
                        Screen.BtPrinterPicker.route,
                        arguments = listOf(navArgument("kind") { type = NavType.StringType }),
                    ) { entry ->
                        // Which profile this pick belongs to. Unknown//missing falls
                        // back to INVOICE rather than crashing on a hand-typed route.
                        val kind = PrintKind.entries
                            .firstOrNull {
                                it.slug == entry.arguments?.let { a -> NavType.StringType.get(a, "kind") }
                            }
                            ?: PrintKind.INVOICE
                        val profile = PrintProfiles.of(kind)
                        BtPrinterPickerPage(
                            btPrinter = BtPrinter.getInstance(),
                            selectedAddress = profile.btAddress,
                            onSelect = { dev ->
                                profile.btAddress = dev.address
                                profile.btName = dev.displayName
                                navController.popBackStack()
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable(Screen.Catalog.route) {
                        CatalogScreen(
                            onBack = { navController.popBackStack() },
                            // OFFLINE edition only, and NOT because the platform
                            // sweep would prune these — it cannot, since it is
                            // scoped to `platform_product_id IS NOT NULL` and
                            // master rows carry NULL. The real hazard is DUPLICATE
                            // ROWS: master-pull "CBC" on a connected device, then
                            // let Studio publish a Complete Blood Count product —
                            // PlatformCatalogImporter derives the code "CBC", finds
                            // it taken by a different id, and falls back to the
                            // product id, leaving the lab with two CBC entries.
                            // A connected lab's catalog is authored in
                            // Studio/BNMAdmin as `products`, which already has its
                            // own one-click import of this same master catalog —
                            // that is where a connected lab gets these tests.
                            // An offline lab has no business and no products, so
                            // this is its only route to the national set.
                            onPullMasterCatalog = if (
                                licState.isStandalone &&
                                licenseManager.deviceToken() != null &&
                                OfflinePolicy.allowsMasterCatalogPull(userInitiated = true)
                            ) {
                                {
                                    val pulled = labApi.masterCatalog().getOrThrow()
                                    val outcome = MasterCatalogImporter(database, ApiClient.json).apply(pulled)
                                    outcome.added to outcome.skipped
                                }
                            } else null,
                        )
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
                        val instStatuses by instrumentEngine.status.collectAsState()
                        BillingSettingsScreen(
                            onQuitForUpdate = { quitForUpdate() },
                            api = api,
                            authRepository = authRepository,
                            businessId = businessId,
                            onBack = { navController.popBackStack() },
                            onOpenLicense = { navController.navigate(Screen.LicenseDevices.route) },
                            onOpenPrintSettings = { navController.navigate(Screen.PrintSettings.route) },
                            onOpenStaff = { navController.navigate(Screen.Staff.route) },
                            onOpenMySignature = { navController.navigate(Screen.MySignature.route) },
                            staffManageAllowed = signedInStaff?.canManageStaff == true,
                            labSync = labSync,
                            labName = licState.labName ?: authRepository.getSelectedBusinessName() ?: "BNM Lab",
                            onOpenInstruments = { navController.navigate(Screen.Instruments.route) },
                            instrumentsSummary = when {
                                instStatuses.isEmpty() -> "Connect analyzers — results enter themselves"
                                instStatuses.values.any { it.state == "error" } -> "Attention needed — a listener is down"
                                instStatuses.values.any { it.state == "listening" } ->
                                    "${instStatuses.values.count { it.state == "listening" }} listening"
                                else -> "All analyzers disabled"
                            },
                        )
                    }

                    composable(Screen.Instruments.route) {
                        InstrumentsScreen(
                            engine = instrumentEngine,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

/** How often the auto-lock poll wakes up to check the idle stamp (P4). */
private const val AUTO_LOCK_POLL_MS = 30_000L

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
