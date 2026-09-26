package com.bnm.lab

import com.bnm.lab.revenue.RevenueRepository
import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.diagnostics.DiagnosticsContext
import com.bnm.lab.diagnostics.ReportProblemDialog
import com.bnm.lab.diagnostics.SupportUi
import com.bnm.lab.diagnostics.logFailure
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.bnm.lab.billing.BillingScope
import com.bnm.lab.billing.ensureLabBillingSeries
import com.bnm.lab.auth.AuthRepository
import com.bnm.lab.auth.FirebaseAuthManager
import com.bnm.lab.backup.BackupGeneration
import com.bnm.lab.backup.platformBackupController
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
import com.bnm.lab.lab.DiagnosisPrefs
import com.bnm.lab.lab.ViewMode
import com.bnm.lab.screens.machine.MachineHomeScreen
import com.bnm.lab.lab.AccessionSeat
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LocalLabRepository
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.license.OfflinePolicy
import com.bnm.lab.license.ReadOnlyCopy
import com.bnm.lab.navigation.GuardedRoute
import com.bnm.lab.navigation.LicenceGate
import com.bnm.lab.navigation.LicenceGatedRoute
import com.bnm.lab.navigation.ReadOnlyBanner
import com.bnm.lab.navigation.RouteGuardEffect
import com.bnm.lab.navigation.Screen
import com.bnm.lab.remote.RemoteSupportBanner
import com.bnm.lab.remote.RemoteSupportDialog
import com.bnm.lab.remote.RemoteSupportStatus
import com.bnm.lab.remote.RemoteSupportUi
import com.bnm.lab.remote.platformRemoteSupportController
import com.bnm.lab.screens.backup.BackupNotBackedUpBanner
import com.bnm.lab.screens.backup.BackupRestoreOfferDialog
import com.bnm.lab.screens.backup.BackupSettingsScreen
import com.bnm.lab.screens.backup.RestoreDialog
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
import com.bnm.lab.staff.StaffRole
import com.bnm.lab.staff.StaffSession
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.ui.theme.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
import com.bnm.lab.remote.RemoteSupportKeys
import com.bnm.lab.remote.SqlSupportAuditStore
import com.bnm.lab.remote.SupportAuditRow
import com.bnm.lab.remote.remoteToolHost
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    // Offline installs used to file bills under a phantom business "null";
    // move them to the offline archive before anything reads or bills.
    LaunchedEffect(Unit) {
        runCatching { repo.adoptNullBusinessBills() }
            .onSuccess { if (it > 0) AppLog.i("Billing", "moved $it offline bill(s) off the 'null' business key") }
            .logFailure("Billing", "adopt null-business bills")
    }
    // Revenue dashboard: reads the same bills and orders, never writes.
    val revenueRepo = remember { RevenueRepository(database, repo) }
    val labRepo = remember {
        LabRepository(database, ApiClient.json, accessionSeat = { AccessionSeat.of(licenseManager) })
    }
    // ── P4: staff accounts + local RBAC. The session is in-memory ONLY — a
    // restarted seat comes back to the sign-in grid. ──
    val staffRepo = remember { StaffRepository(database, ApiClient.json) }
    val staffSession = remember { StaffSession() }
    // Keep the signed-in person's rights current. A role or pathologist tick changed
    // on another seat arrives by sync; without this the approve button kept
    // showing (or hiding) from the stale sign-in copy until the next sign-in.
    LaunchedEffect(staffRepo, staffSession) {
        staffRepo.listAllFlow().collect { all ->
            val id = staffSession.current.value?.id ?: return@collect
            all.firstOrNull { it.id == id }?.let { staffSession.refresh(it) }
        }
    }
    val licenseState by licenseManager.state.collectAsState()
    LaunchedEffect(licenseState.licensed, licenseState.lapsed, licenseState.blocked, licenseState.edition, licenseState.expiresAt) {
        AppLog.i("Licence", "licensed=${licenseState.licensed} lapsed=${licenseState.lapsed} blocked=${licenseState.blocked} " +
            "edition=${licenseState.edition} mode=${licenseState.mode} expires=${licenseState.expiresAt}")
    }
    // A subscription can lapse while the app is open — a trial key signed with
    // no grace, or the last day of grace — and nothing else re-reads the term on
    // a desktop that stays up for days. Re-evaluate once a minute; the state only
    // emits on a real change, so this costs a signature check and no recomposition.
    LaunchedEffect(Unit) {
        while (true) {
            delay(LICENCE_RECHECK_MS)
            licenseManager.refresh()
        }
    }

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
    val outboxSender = remember {
        BillingOutboxSender(database, api, paymentToken = { licenseManager.deviceToken() ?: authRepository.getAuthToken() })
    }
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
        AppLog.i("Catalog", "catalog empty on an offline licence — bootstrapping from the master catalog")
        runCatching {
            MasterCatalogImporter(database, ApiClient.json)
                .apply(labApi.masterCatalog().getOrThrow())
        }.onSuccess { AppLog.i("Catalog", "master catalog bootstrap: added ${it.added}, skipped ${it.skipped}") }
            .logFailure("Catalog", "master catalog bootstrap")
    }

    // The bundled starter catalog is gone from the app, but not from the
    // machines that already seeded it — so sweep it out here, at every launch.
    // Unused rows are deleted; a row an old order still names is deactivated
    // and its code freed, because results and graphs key on test_id and
    // deleting it would strand a finished report. No-op on a fresh install.
    LaunchedEffect(Unit) { runCatching { labRepo.retireLegacySeedCatalog() } }

    // ── P3: additive lab sync (push/pull lab_entities + EMR inbox). The app is
    // the system of record — every phase is best-effort and never blocks UI. ──
    // Report publishing rides the normal sync sweep: printing works offline, and
    // the report snapshot reaches the server (making the printed QR resolvable)
    // whenever connectivity next returns. Standalone licences never get here —
    // both the engine and ReportUploader return early for them.
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

    // ── Remote support ("Maintenance mode"): the desktop engine, or null on
    // Android/iOS. Nothing runs until the owner starts a session from the
    // dialog. Tool hosts (RemoteTools) and the audit store are attached here,
    // once the database and the analyzer engine exist. ──
    val remoteSupport = remember { platformRemoteSupportController() }
    // ── Backup pendrive (offline edition, desktop): the engine, or null where
    // there is none. The chip, the Settings row and the banner show only for a
    // standalone licence; the Activation screen offers Restore whenever an
    // engine exists, because the edition is unknown before activation. ──
    val backupController = remember { platformBackupController() }
    // Asked ONCE per session: a bound pendrive holding a backup, on a PC with
    // no records (fresh install / reinstall) — LabHome or Activation offers it.
    var restoreOffer by remember { mutableStateOf<BackupGeneration?>(null) }
    LaunchedEffect(backupController) {
        restoreOffer = backupController?.let { c ->
            runCatching { c.restoreOfferAtLaunch() }.logFailure("Backup", "restore offer at launch").getOrNull()
        }
    }

    // ── Support report context: the questions support asks first, answered at
    // the top of the report. Flags, counts and IDs ONLY — the report is emailed,
    // so no patient, no result, no token, no licence key ever goes in here. ──
    LaunchedEffect(Unit) {
        DiagnosticsContext.setHeadline {
            val st = licenseManager.state.value
            listOfNotNull(
                st.labName,
                if (st.isStandalone) "offline edition" else "connected edition",
                "device ${licenseManager.deviceId.take(8)}",
            ).joinToString(" · ")
        }
        DiagnosticsContext.register("Licence") {
            val st = licenseManager.state.value
            "licensed=${st.licensed} lapsed=${st.lapsed} blocked=${st.blocked} edition=${st.edition} mode=${st.mode} " +
                "seats=${st.seats}\nexpires=${st.expiresAt} lab=${st.labName}\n" +
                "business=${st.businessId} deviceRow=${st.deviceRowId} install=${licenseManager.deviceId}"
        }
        DiagnosticsContext.register("Data on this computer") {
            val c = labRepo.tenantRowCounts()
            "patients=${c.patients} orders=${c.orders} results=${c.results} staff=${c.staff} tests=${c.tests}"
        }
        DiagnosticsContext.register("Sync") {
            val st = labSync.state.value
            "syncing=${st.syncing} disabled=${st.disabled} lastSyncAt=${st.lastSyncAt}\nlastError=${st.lastError}"
        }
        DiagnosticsContext.register("Analyzers") {
            instrumentEngine.status.value.entries
                .joinToString("\n") { (id, s) -> "$id: ${s.state} lastFrame=${s.lastFrameAt} ${s.detail.orEmpty()}" }
                .ifBlank { "none configured" }
        }
    }
    LaunchedEffect(Unit) { instrumentEngine.start() }

    // ── Remote support: the audit trail (support history) and the tool host
    // attach to the session engine (declared above) once the database and the
    // analyzer engine exist. Nothing is reachable until the OWNER starts a
    // session; `lab.overview` reports whether this build still trusts the
    // committed DEV support key. ──
    val supportAudit = remember { SqlSupportAuditStore(database) }
    LaunchedEffect(Unit) {
        remoteSupport?.let { rs ->
            rs.attachAuditStore(supportAudit)
            rs.attachToolHost(remoteToolHost(
                database, instrumentEngine, labRepo, licenseManager, controller = rs,
                supportKeyLabel = { if (RemoteSupportKeys.isDevKey) "dev" else "prod" },
            ))
        }
    }
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
                        is LabHeartbeatResult.Ok -> {
                            AppLog.i("Licence", "heartbeat ok (mode=${hb.mode} seats=${hb.seats} expires=${hb.expiresAt})")
                            licenseManager.applyHeartbeat(hb.licenseJwt, hb.mode, hb.seats, hb.expiresAt, hb.labName, hb.seatNo, hb.reportPageLive)
                        }
                        is LabHeartbeatResult.Blocked -> {
                            AppLog.w("Licence", "heartbeat: this device's licence is BLOCKED — new work gated")
                            licenseManager.setBlocked(true)
                        }
                        LabHeartbeatResult.InvalidSession -> AppLog.w("Licence", "heartbeat: device session rejected (401)")
                    }
                }.logFailure("Licence", "heartbeat")
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
                    runCatching { ensureLabBillingSeries(labApi, repo, biz, licenseManager) }
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
            val st = licenseManager.state.value
            if (licenseManager.deviceToken() != null && OfflinePolicy.allowsSync(st.isStandalone)) {
                labSync.syncNow()
                // Bills ride a different spine from orders. Orders converge across
                // seats on this sweep, but bills used to arrive only on a seat's
                // first visit, a manual re-sync or a reconnect — and the desktop
                // never sees a reconnect (its connectivity monitor is always
                // online). So one seat's revenue and dues never included another
                // seat's bills or collections. The delta is cursor-based: an idle
                // pull costs one small request.
                if (OfflinePolicy.allowsBillingSync(st.isStandalone)) {
                    val biz = BillingScope.issuingBusinessId(authRepository.getSelectedBusinessId(), st.businessId)
                    if (!BillingScope.isOffline(biz)) {
                        // Under the outbox lock, after a drain: a pull racing a
                        // drain can write back a pre-tender row over the fresh one.
                        runCatching {
                            outboxSender.drainThen { syncEngine.sync(biz, setOf(BillingRepository.INVOICE)).getOrThrow() }
                        }.logFailure("Billing", "periodic invoice pull")
                    }
                }
            }
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
            // The business every bill on this seat is filed under. An offline lab
            // has none, and resolving it to "" used to leave it unable to bill at
            // all — BillingScope files those bills under a key that never leaves
            // this computer instead.
            fun billingBusinessId(): String =
                BillingScope.issuingBusinessId(authRepository.getSelectedBusinessId(), licState.businessId)

            val supportRequest by SupportUi.request.collectAsState()
            supportRequest?.let { ReportProblemDialog(it, onDismiss = { SupportUi.close() }) }
            val remoteDialogOpen by RemoteSupportUi.open.collectAsState()
            if (remoteDialogOpen && remoteSupport != null) {
                RemoteSupportDialog(remoteSupport, onDismiss = { RemoteSupportUi.close() })
            }

            // Entry gate (P2): no genuine licence on this computer → Activation.
            // A lapsed or blocked licence is NOT that: it signs staff in and runs
            // read-only (LicenceGate). The old billing counter-pairing screen
            // (LoginScreen) is intentionally UNREACHABLE from the entry flow —
            // license activation replaces pairing.
            key(isLoggedIn) {
                val navController = rememberNavController()
                LaunchedEffect(navController) {
                    navController.currentBackStackEntryFlow.collect { entry ->
                        AppLog.i("Nav", entry.destination.route ?: "(unnamed screen)")
                    }
                }
                // Accession of the order just registered (P1b) — shown as the
                // confirmation snackbar once LabHome is back on screen.
                var lastAccession by remember { mutableStateOf<String?>(null) }
                // P4: who is at this seat. Entry flow = license check → staff
                // sign-in → LabHome. A blocked device AND a subscription past
                // lic_exp + gr both still sign people in and stay fully readable/
                // printable/exportable; only new-work routes refuse them.
                val signedInStaff by staffSession.current.collectAsState()
                val startDestination = remember(isLoggedIn) {
                    LicenceGate.entryRoute(activated = licenseManager.isActivated())
                }

                // "Switch user", "Sign out" and the auto-lock are one action:
                // drop the in-memory session (lab data untouched) and go back to
                // the sign-in grid with nothing left on the back stack.
                val uiScope = rememberCoroutineScope()
                fun lockSeat(signOut: Boolean = false) {
                    // Only the owner's Sign out ends the support session they are
                    // answerable for — see seatExitEndsSupport.
                    if (remoteSupport != null &&
                        seatExitEndsSupport(signOut, remoteSupport.status.value.isActive, staffSession.signedIn?.role)
                    ) {
                        uiScope.launch { remoteSupport.end("owner signed out") }
                    }
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

                // The support banner sits above EVERY screen — activation and
                // sign-in included — for as long as a session runs.
                val remoteStatus by (remoteSupport?.status ?: NO_REMOTE_SUPPORT).collectAsState()
                Column(Modifier.fillMaxSize()) {
                RemoteSupportBanner(
                    remoteStatus,
                    onEnd = { uiScope.launch { remoteSupport?.end("owner pressed End") } },
                    onOpen = { RemoteSupportUi.open() },
                )
                Box(Modifier.fillMaxWidth().weight(1f)) {
                NavHost(navController = navController, startDestination = startDestination) {

                    // ── Seat sign-in gate (P4) ──
                    composable(Screen.StaffSignIn.route) {
                        Column(Modifier.fillMaxSize()) {
                            // The sign-in grid is one of the two places the
                            // "not backed up" banner may show (the other is home).
                            BackupNotBackedUpBanner(
                                backupController?.takeIf { licState.isStandalone },
                                onOpenBackup = { navController.navigate(Screen.BackupSettings.route) },
                            )
                            Box(Modifier.fillMaxWidth().weight(1f)) {
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
                        }
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
                            // Listeners first, then the last snapshot of the old
                            // lab — so no analyzer frame lands after the copy.
                            onBeforeTenantWipe = {
                                instrumentEngine.stopAll()
                                backupController?.beforeTenantWipe()
                            },
                            // The vault was the old lab's: forget it, the new
                            // lab sets up its own pendrive.
                            onAfterTenantWipe = { backupController?.afterTenantWipe() },
                            backupController = backupController,
                            restoreOffer = restoreOffer,
                            onRestoreOfferHandled = { restoreOffer = null },
                            onActivated = { a ->
                                // A license bound to a BNM business pre-selects it
                                // so the billing sync spine keeps working.
                                a.businessId?.takeIf { it.isNotBlank() }?.let {
                                    authRepository.saveSelectedBusiness(it, a.labName)
                                }
                                // BNM now knows this computer: a PC restored from
                                // a backup pendrive drops its "register" notice.
                                backupController?.markRegisteredOnline()
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
                            backupController = backupController,
                            // Same key, same lab: the tenant guard sees the same
                            // fingerprint and re-activates without a wipe.
                            onRegisterRestoredPc = { navController.navigate(Screen.Activation.route) },
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
                        val businessId = billingBusinessId()
                        val labName = licState.labName
                            ?: authRepository.getSelectedBusinessName()
                            ?: "BNM Lab"

                        // Machine-only mode (Settings ▸ View mode) replaces home
                        // outright. Branching HERE rather than at every navigate()
                        // means the dozen existing paths back to LabHome keep
                        // working and the setting is the single switch — and a
                        // lab that switches back finds everything where it was,
                        // because nothing else in the graph changed.
                        if (DiagnosisPrefs().viewMode == ViewMode.MACHINE_ONLY) {
                            MachineHomeScreen(
                                engine = instrumentEngine,
                                labName = labName,
                                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                                onOpenInstruments = { navController.navigate(Screen.Instruments.route) },
                            )
                            return@composable
                        }

                        // First-sync once per device per business, gated on the invoice cursor.
                        LaunchedEffect(businessId, licState.isStandalone) {
                            if (businessId.isBlank() || BillingScope.isOffline(businessId)) return@LaunchedEffect
                            if (!OfflinePolicy.allowsBillingSync(licState.isStandalone)) return@LaunchedEffect
                            // Keyed on the invoice-SETTINGS cursor, which only a full
                            // sync writes. The five-minute loop pulls invoices alone and
                            // stamps the invoice cursor, so a seat left on the sign-in
                            // grid past its first sweep would otherwise never download
                            // its settings, tax rates, products or customers.
                            val everSynced = repo.lastSyncedFlow(BillingRepository.INVOICE_SETTING, businessId).first()
                            if (everSynced == null) runCatching { syncEngine.syncAll(businessId) }
                        }

                        val subscription = licenseManager.subscriptionStatus()
                        val readOnly = licState.readOnlyReason
                        // Offline edition only: a connected lab has a server copy.
                        val standaloneBackup = backupController?.takeIf { licState.isStandalone }
                        var restoreFrom by remember { mutableStateOf<BackupGeneration?>(null) }
                        Column(Modifier.fillMaxSize()) {
                            readOnly?.let {
                                ReadOnlyBanner(it, onOpenLicence = { navController.navigate(Screen.LicenseDevices.route) })
                            }
                            BackupNotBackedUpBanner(
                                standaloneBackup,
                                onOpenBackup = { navController.navigate(Screen.BackupSettings.route) },
                            )
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                LabHomeScreen(
                                    subscriptionNotice = subscription.notice,
                                    subscriptionUrgent = subscription.urgent,
                                    labName = labName,
                                    newWorkLockedNote = readOnly?.let(ReadOnlyCopy::inline),
                                    accessionNotice = lastAccession,
                                    onNoticeShown = { lastAccession = null },
                                    // Blocked or lapsed seats keep everything readable/
                                    // printable/exportable but can't START new work.
                                    onNewOrder = { if (licState.canStartNewWork) navController.navigate(Screen.NewOrder.createRoute()) },
                                    onPatients = { navController.navigate(Screen.Patients.route) },
                                    onReferrers = { navController.navigate(Screen.Referrers.route) },
                                    onCatalog = { navController.navigate(Screen.Catalog.route) },
                                    onBills = { navController.navigate(Screen.Bills.createRoute()) },
                                    onSettings = { navController.navigate(Screen.Settings.route) },
                                    onEmrInbox = { navController.navigate(Screen.EmrInbox.route) },
                                    onOpenOrder = { id -> navController.navigate(Screen.LabOrderDetail.createRoute(id)) },
                                    onNewPatient = { navController.navigate(Screen.Patients.route) },
                                    licenseMode = licState.mode,
                                    businessId = businessId,
                                    labSync = labSync,
                                    signedInStaff = signedInStaff,
                                    onSwitchUser = { lockSeat() },
                                    onSignOut = { lockSeat(signOut = true) },
                                    revenue = revenueRepo,
                                    onRevenue = { navController.navigate(Screen.Bills.createRoute(com.bnm.lab.navigation.BillsTab.REVENUE)) },
                                    backupController = standaloneBackup,
                                    onBackup = { navController.navigate(Screen.BackupSettings.route) },
                                )
                            }
                        }
                        // A reinstalled PC that is still activated: its pendrive
                        // holds the records — offer them once.
                        if (standaloneBackup != null) {
                            restoreOffer?.let { gen ->
                                BackupRestoreOfferDialog(
                                    gen = gen,
                                    onRestore = { restoreOffer = null; restoreFrom = gen },
                                    onNotNow = { restoreOffer = null },
                                )
                            }
                            restoreFrom?.let { gen ->
                                RestoreDialog(
                                    controller = standaloneBackup,
                                    activated = true,
                                    initialGeneration = gen,
                                    onDismiss = { restoreFrom = null },
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
                        LicenceGatedRoute(Screen.NewOrder.route, licState, onBack = { navController.popBackStack() }) {
                            val emrId = backStack.arguments?.let { NavType.StringType.get(it, "emrId") }
                            val businessId = billingBusinessId()
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
                    }

                    composable(Screen.EmrInbox.route) {
                        EmrInboxScreen(
                            onBack = { navController.popBackStack() },
                            onRegister = { emrId ->
                                if (licState.canStartNewWork) {
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
                            businessId = billingBusinessId(),
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
                                businessId = billingBusinessId(),
                            )
                        }
                    }

                    composable(Screen.BackupSettings.route) {
                        val controller = backupController
                        if (controller == null) {
                            // No engine on this platform: a hand-typed route just goes back.
                            LaunchedEffect(Unit) { navController.popBackStack() }
                        } else {
                            BackupSettingsScreen(
                                controller = controller,
                                licenseManager = licenseManager,
                                labName = licState.labName ?: "BNM Lab",
                                signedInStaff = signedInStaff,
                                verifyPin = { who, pin -> staffRepo.verifyPin(who.id, pin) },
                                rowCounts = { runCatching { labRepo.tenantRowCounts() }.getOrNull() },
                                onBack = { navController.popBackStack() },
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
                                    AppLog.i("Catalog", "master catalog pull: ${pulled.size} received, " +
                                        "added ${outcome.added}, kept ${outcome.skipped}")
                                    outcome.added to outcome.skipped
                                }
                            } else null,
                        )
                    }

                    composable(Screen.CreateInvoice.route) {
                        LicenceGatedRoute(Screen.CreateInvoice.route, licState, onBack = { navController.popBackStack() }) {
                            val businessId = billingBusinessId()
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
                    }

                    composable(
                        route = Screen.Bills.route,
                        arguments = listOf(navArgument("tab") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }),
                    ) { backStack ->
                        com.bnm.lab.screens.billing.BillsScreen(
                            businessId = billingBusinessId(),
                            revenue = revenueRepo,
                            offlineEdition = licState.isStandalone,
                            requestedTab = backStack.arguments?.let { NavType.StringType.get(it, "tab") },
                            onBack = { navController.popBackStack() },
                            onOpen = { id -> navController.navigate(Screen.InvoiceDetail.createRoute(id)) },
                        )
                    }

                    composable(Screen.Cart.route) {
                        LicenceGatedRoute(Screen.Cart.route, licState, onBack = { navController.popBackStack() }) {
                            val businessId = billingBusinessId()
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
                    }

                    composable(Screen.CustomerDetails.route) {
                        LicenceGatedRoute(Screen.CustomerDetails.route, licState, onBack = { navController.popBackStack() }) {
                            val businessId = billingBusinessId()
                            CustomerDetailsScreen(
                                businessId = businessId,
                                businessName = authRepository.getSelectedBusinessName() ?: "Business",
                                onBack = { navController.popBackStack() },
                                onSaved = { id ->
                                    navController.navigate(Screen.InvoiceDetail.createRoute(id)) { popUpTo(Screen.Main.route) }
                                },
                            )
                        }
                    }

                    composable(
                        route = Screen.InvoiceDetail.route,
                        arguments = listOf(navArgument("invoiceId") { type = NavType.StringType })
                    ) { backStack ->
                        val invoiceId = NavType.StringType.get(backStack.arguments!!, "invoiceId") ?: return@composable
                        val businessId = billingBusinessId()
                        InvoiceDetailScreen(api = api, businessId = businessId, invoiceId = invoiceId, onBack = { navController.popBackStack() },
                            labName = licState.labName ?: authRepository.getSelectedBusinessName() ?: "BNM Lab")
                    }

                    composable(Screen.Settings.route) {
                        val businessId = billingBusinessId()
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
                            backupController = backupController?.takeIf { licState.isStandalone },
                            onOpenBackup = { navController.navigate(Screen.BackupSettings.route) },
                        )
                    }

                    composable(Screen.Instruments.route) {
                        InstrumentsScreen(
                            engine = instrumentEngine,
                            labName = licState.labName ?: authRepository.getSelectedBusinessName() ?: "BNM Lab",
                            // Only the queue's "Create order" reads these two: it is
                            // the one door on this screen that starts new work, and
                            // the accession it mints comes from this seat's series.
                            licence = licState,
                            accessionSeries = labRepo.ownAccessionSeries(),
                            onBack = { navController.popBackStack() },
                            onOpenOrder = { id -> navController.navigate(Screen.LabOrderDetail.createRoute(id)) },
                            onVerified = { inst ->
                                // Support history row: the bench confirmed the settings support changed.
                                runCatching {
                                    supportAudit.append(SupportAuditRow(
                                        id = uuid4(), sessionId = "", atMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                                        tool = "instruments.verified", summary = "${inst.name} verified at the bench",
                                        outcome = SupportAuditRow.Outcome.OK, ms = 0L,
                                        startedBy = signedInStaff?.id ?: "",
                                    ))
                                }.logFailure("RemoteSupport", "verified audit row")
                            },
                        )
                    }
                }
                }
                }
            }
        }
    }
}

/** How often the auto-lock poll wakes up to check the idle stamp (P4). */
private const val AUTO_LOCK_POLL_MS = 30_000L

/**
 * Does leaving the seat end a running support session? Only the owner's
 * explicit **Sign out** does. "Switch user" hands the bench to a technician
 * mid-repair — RUNBOOK §3.7 asks for exactly that, so the lab can run one
 * known sample and press Verified while the engineer watches — and the idle
 * auto-lock fires while the bench is quiet. Neither may close the engineer's
 * connection; End on the banner stays one click away for the owner.
 */
internal fun seatExitEndsSupport(signOut: Boolean, supportActive: Boolean, role: String?): Boolean =
    signOut && supportActive && role == StaffRole.OWNER

@OptIn(ExperimentalUuidApi::class)
private fun uuid4(): String = Uuid.random().toString()

/** How often the licence term is re-read while the app is open. */
private const val LICENCE_RECHECK_MS = 60_000L

/** What a platform without a remote-support engine reports: never active. */
private val NO_REMOTE_SUPPORT = MutableStateFlow(RemoteSupportStatus())
