package com.bnm.diagnosis.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** One line item on a manual invoice. */
@Serializable
data class InvoiceLineItem(
    @SerialName("description") val description: String = "",
    @SerialName("hsn") val hsn: String? = null,
    @SerialName("quantity") val quantity: Double = 1.0,
    @SerialName("rate") val rate: Double = 0.0,
    @SerialName("amount") val amount: Double = 0.0,
    @SerialName("gst_rate") val gstRate: Double = 0.0,
    @SerialName("cgst") val cgst: Double = 0.0,
    @SerialName("sgst") val sgst: Double = 0.0,
    @SerialName("igst") val igst: Double = 0.0,
    @SerialName("product_id") val productId: String? = null,
)

/** One row of the GST tax breakup, grouped by rate. */
@Serializable
data class TaxBreakupRow(
    @SerialName("rate") val rate: Double = 0.0,
    @SerialName("taxable") val taxable: Double = 0.0,
    @SerialName("cgst") val cgst: Double = 0.0,
    @SerialName("sgst") val sgst: Double = 0.0,
    @SerialName("igst") val igst: Double = 0.0,
)

/** A synced invoice (offline). Tolerant: server sends extra fields we ignore. */
@Serializable
data class Invoice(
    @SerialName("id") val id: String,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    @SerialName("customer_gstin") val customerGstin: String? = null,
    @SerialName("place_of_supply") val placeOfSupply: String? = null,
    @SerialName("issued_at") val issuedAt: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("subtotal") val subtotal: Double = 0.0,
    @SerialName("discount") val discount: Double = 0.0,
    @SerialName("tax") val tax: Double = 0.0,
    @SerialName("total") val total: Double = 0.0,
    @SerialName("status") val status: String = "pending",
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("payment_reference") val paymentReference: String? = null,
    // Cash-drawer detail recorded at save time (cash payments only).
    @SerialName("amount_tendered") val amountTendered: Double? = null,
    @SerialName("change_due") val changeDue: Double? = null,
    @SerialName("pdf_url") val pdfUrl: String? = null,
    @SerialName("series_code") val seriesCode: String? = null,
    @SerialName("local_seq") val localSeq: Long? = null,
    @SerialName("line_items") val lineItems: List<InvoiceLineItem> = emptyList(),
    @SerialName("tax_breakup") val taxBreakup: List<TaxBreakupRow> = emptyList(),
    @SerialName("notes") val notes: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    // Local-only: 'pending_sync' until the outbox drains, else absent/synced.
    @SerialName("sync_status") val syncStatus: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val displayNumber: String get() = invoiceNumber ?: id.take(8)
    val resolvedCustomer: String get() = customerName?.takeIf { it.isNotBlank() } ?: "Guest"
    /** True only when the invoice hasn't uploaded yet (NOT the unpaid status). */
    val isPendingSync: Boolean get() = syncStatus == "pending_sync"
    /** Human payment label — never the raw "pending" enum (that reads like a sync state). */
    val paymentLabel: String get() = when (status) {
        "paid" -> "Paid"; "partial" -> "Partially paid"; "cancelled" -> "Cancelled"; else -> "Unpaid"
    }
}

/** Per-business invoice settings (numbering, bank, GST id, template). */
@Serializable
data class InvoiceSettings(
    @SerialName("template_id") val templateId: String? = null,
    @SerialName("legal_name") val legalName: String? = null,
    @SerialName("tax_id") val taxId: String? = null,
    @SerialName("bank_name") val bankName: String? = null,
    @SerialName("account_name") val accountName: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
    @SerialName("ifsc") val ifsc: String? = null,
    @SerialName("branch") val branch: String? = null,
    @SerialName("upi_vpa") val upiVpa: String? = null,
    @SerialName("invoice_prefix") val invoicePrefix: String? = "INV",
    @SerialName("invoice_number_format") val invoiceNumberFormat: String? = null,
    @SerialName("footer_note") val footerNote: String? = null,
    @SerialName("terms_and_conditions") val terms: String? = null,
    @SerialName("due_days") val dueDays: Int = 7,
    @SerialName("send_via_default") val sendViaDefault: String? = "whatsapp",
    // Supplier identity for a GST tax invoice (Rule 46): name + address from the
    // business profile, GSTIN = taxId. Populated by admin-billing /invoice-settings.
    @SerialName("supplier_name") val supplierName: String? = null,
    @SerialName("supplier_address") val supplierAddress: String? = null,
    @SerialName("supplier_phone") val supplierPhone: String? = null,
    @SerialName("supplier_email") val supplierEmail: String? = null,
    // POS sale-flow config (business-level, edited in BNMAdmin/BusinessStudio —
    // NOT in this app). Maps a fulfillment key picked at the counter to how the
    // mirrored order enters the order workflow server-side.
    @SerialName("pos_flows") val posFlows: PosFlows? = null,
    // Collection-token stations (self-service restaurants / food courts).
    // Tolerant default: absent/empty = no stations = printing exactly as before.
    @SerialName("stations") val stations: List<BillingStation> = emptyList(),
) {
    /** Supplier display name: explicit legal name, else the business name. */
    val supplierDisplayName: String? get() = legalName?.takeIf { it.isNotBlank() } ?: supplierName

    /** Enabled fulfillment options in a stable, operator-friendly order.
     *  Empty/absent config = single implicit "instant" flow → no picker shown. */
    val enabledPosOptions: List<Pair<String, PosFlowOption>>
        get() {
            val opts = posFlows?.options ?: return emptyList()
            val order = listOf("instant", "dine_in", "takeaway", "delivery")
            return opts.entries
                .filter { it.value.enabled }
                .sortedBy { order.indexOf(it.key).let { i -> if (i == -1) order.size else i } }
                .map { it.key to it.value }
        }
}

/** One POS fulfillment option (key → how the mirrored order starts). The
 *  server (admin-billing ensurePosOrder) owns the actual mapping; the app only
 *  renders labels and sends the chosen KEY on the invoice. */
@Serializable
data class PosFlowOption(
    @SerialName("label") val label: String = "",
    @SerialName("order_type") val orderType: String? = null,
    @SerialName("initial_status") val initialStatus: String? = null,
    @SerialName("enabled") val enabled: Boolean = true,
    // Delivery-style flows need a reachable customer (dispatch needs phone/address).
    @SerialName("requires_customer") val requiresCustomer: Boolean = false,
)

@Serializable
data class PosFlows(
    @SerialName("default") val default: String? = null,
    @SerialName("options") val options: Map<String, PosFlowOption> = emptyMap(),
)

/** One collection-token station (`invoice_settings.stations`, server-owned,
 *  synced to the device). A bill line joins a station when its product id is
 *  in [productIds] (EXPLICIT WINS), else when its product's category name is
 *  in [categories]. [printerIp] non-blank = the station's own LAN printer. */
@Serializable
data class BillingStation(
    @SerialName("key") val key: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("printer_ip") val printerIp: String? = null,
    @SerialName("product_ids") val productIds: List<String> = emptyList(),
    @SerialName("categories") val categories: List<String> = emptyList(),
)

/** A store product for the POS grid (offline). Tolerant of missing fields. */
@Serializable
data class Product(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("selling_price") val sellingPrice: Double? = null,
    @SerialName("price") val price: Double? = null,
    @SerialName("mrp") val mrp: Double? = null,
    @SerialName("hsn_code") val hsnCode: String? = null,
    @SerialName("tax_rate") val taxRate: Double? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
) {
    val effectivePrice: Double get() = sellingPrice ?: price ?: mrp ?: 0.0
    val effectiveGst: Double get() = taxRate ?: 0.0
    val categoryLabel: String get() = category?.takeIf { it.isNotBlank() } ?: "Uncategorized"

    /**
     * Human-readable name. `products.name` can arrive as a plain string
     * ("Masala Chips") OR as the canonical multilingual JSON literal
     * `{"en":"Cold Brew Coffee 250ml"}` (rows written by the BS web / ecommerce
     * CRUD). Resolve to English so the POS never shows raw braces.
     */
    val displayName: String get() = resolveProductName(name)
}

// Compiled ONCE — these run per product on a hot grid path.
private val EN_NAME_RE = Regex("\"en\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
private val FIRST_PAIR_RE = Regex("\"[a-zA-Z_][\\w-]*\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

/**
 * Resolve a possibly-multilingual product name to readable text. Tolerates:
 *   • "Shirt"                    → "Shirt"
 *   • {"en":"Shirt"}             → "Shirt"
 *   • {"hi":"...","en":"Shirt"}  → "Shirt"
 *   • {"hi":"..."} (no en)       → first value
 *   • {"en":""} / malformed      → original string (caller may show empty)
 * Pure regex — safe to call synchronously from Compose.
 */
internal fun resolveProductName(raw: String): String {
    val t = raw.trim()
    if (!t.startsWith("{")) return raw
    EN_NAME_RE.find(t)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { return unescapeJson(it) }
    FIRST_PAIR_RE.find(t)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { return unescapeJson(it) }
    return raw
}

private fun unescapeJson(s: String): String =
    s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t").replace("\\/", "/")

@Serializable
data class TaxRate(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("rate") val rate: Double = 0.0,
    @SerialName("hsn_sac") val hsnSac: String? = null,
    @SerialName("category") val category: String? = "GST",
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class BillingCounter(
    @SerialName("id") val id: String,
    @SerialName("series_code") val seriesCode: String = "",
    @SerialName("label") val label: String? = null,
    @SerialName("prefix") val prefix: String? = "INV",
    @SerialName("number_format") val numberFormat: String? = "{prefix}-{series}-{seq}",
    @SerialName("fy") val fy: String? = null,
    @SerialName("high_water") val highWater: Long = 0,
    // Counter's UPI ID (set in BNM Admin → Settings → Billing Counters). A changed
    // VPA reaches the device on normal counter sync.
    @SerialName("upi_vpa") val upiVpa: String? = null,
)

/** Body for creating an invoice (device-authoritative manual create). */
@Serializable
data class InvoiceCreateRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("invoice_number") val invoiceNumber: String,
    @SerialName("series_code") val seriesCode: String,
    @SerialName("counter_code") val counterCode: String,
    @SerialName("fy") val fy: String,
    @SerialName("local_seq") val localSeq: Long,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    @SerialName("customer_gstin") val customerGstin: String? = null,
    @SerialName("place_of_supply") val placeOfSupply: String? = null,
    @SerialName("line_items") val lineItems: List<InvoiceLineItem> = emptyList(),
    @SerialName("subtotal") val subtotal: Double,
    @SerialName("discount") val discount: Double = 0.0,
    @SerialName("tax") val tax: Double,
    @SerialName("total") val total: Double,
    @SerialName("tax_breakup") val taxBreakup: List<TaxBreakupRow> = emptyList(),
    @SerialName("issued_at") val issuedAt: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("status") val status: String = "pending",
    @SerialName("notes") val notes: String? = null,
    // POS fulfillment key (dine_in/takeaway/…) — server maps it via pos_flows.
    @SerialName("fulfillment") val fulfillment: String? = null,
    // Payment recorded at the counter (status 'paid' allowed) — the server stores
    // these verbatim and mirrors payment onto the POS order.
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("payment_reference") val paymentReference: String? = null,
    @SerialName("amount_tendered") val amountTendered: Double? = null,
    @SerialName("change_due") val changeDue: Double? = null,
)
