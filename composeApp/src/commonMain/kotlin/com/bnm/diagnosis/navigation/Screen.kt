package com.bnm.diagnosis.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object BusinessSelector : Screen("business_selector")
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
