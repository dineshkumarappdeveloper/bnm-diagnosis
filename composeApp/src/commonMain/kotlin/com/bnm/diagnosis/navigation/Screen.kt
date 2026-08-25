package com.bnm.diagnosis.navigation

sealed class Screen(val route: String) {
    /** License activation — the app's entry when this device isn't licensed. */
    data object Activation : Screen("activation")
    /** License & devices management (reached from Settings). */
    data object LicenseDevices : Screen("license_devices")
    data object Login : Screen("login")
    data object BusinessSelector : Screen("business_selector")

    // ── LIMS (P1b) — the app's main surface ──
    /** LIMS home: counters + New order + worklist/masters/bills/settings. */
    data object LabHome : Screen("lab_home")
    /** Registration desk: patient + tests + referrer → order + GST bill. */
    data object NewOrder : Screen("new_order")
    /** Status-tabbed pipeline worklist; tab = the initial LabStatus. */
    data object Worklist : Screen("worklist/{tab}") {
        fun createRoute(tab: String = "registered") = "worklist/$tab"
    }
    /** One order's workbench (entry → verify → approve → report). */
    data object LabOrderDetail : Screen("lab_order/{orderId}") {
        fun createRoute(orderId: String) = "lab_order/$orderId"
    }
    data object Patients : Screen("patients")
    data object Referrers : Screen("referrers")
    data object Catalog : Screen("catalog")

    // ── Billing (kept; reachable via Home ▸ Bills and Settings only) ──
    /** Legacy billing home (product grid + cart) — NOT registered in the nav
     *  graph anymore; LabHome replaced it as the main surface. */
    data object Main : Screen("main")
    data object InvoiceDetail : Screen("invoice/{invoiceId}") {
        fun createRoute(invoiceId: String) = "invoice/$invoiceId"
    }
    data object CreateInvoice : Screen("create_invoice")
    data object Bills : Screen("bills")
    data object Cart : Screen("cart")
    data object CustomerDetails : Screen("customer_details")
    data object Settings : Screen("settings")
}
