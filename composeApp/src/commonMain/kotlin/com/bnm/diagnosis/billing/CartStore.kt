package com.bnm.diagnosis.billing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.bnm.diagnosis.api.models.Product
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A catalog-less "free-style" line typed by the operator at the counter
 *  (name/qty/price/GST). Becomes a GstLine with productId = null, hsn = null —
 *  the counter is never blocked by what's synced in the product catalog. */
data class CustomLine(
    val id: String,
    val name: String,
    val unitPrice: Double,
    val gstRate: Double,
    val qty: Int,
)

/** One open sale (a parked or active cart). */
@OptIn(ExperimentalUuidApi::class)
class Sale(val id: String, val seq: Int) {
    /** productId -> qty (reactive — drives the grid badges + cart). */
    val items = mutableStateMapOf<String, Int>()
    private val products = mutableMapOf<String, Product>()

    /** Catalog-less lines, alongside [items] (reactive — drives the cart list).
     *  Held bills keep them like product lines; clear() drops them the same way. */
    val customLines = mutableStateListOf<CustomLine>()

    /** POS fulfillment key for THIS sale (dine_in/takeaway/…). Null = business
     *  default. Per-sale (not global) so a held dine-in bill keeps its mode
     *  while the operator rings up a takeaway. */
    var fulfillment by mutableStateOf<String?>(null)

    fun add(p: Product) { products[p.id] = p; items[p.id] = (items[p.id] ?: 0) + 1 }
    fun setQty(p: Product, qty: Int) { products[p.id] = p; if (qty <= 0) items.remove(p.id) else items[p.id] = qty }
    fun qty(id: String): Int = items[id] ?: 0
    fun lines(): List<Pair<Product, Int>> = items.mapNotNull { (id, q) -> products[id]?.let { it to q } }

    fun addCustom(name: String, qty: Int, unitPrice: Double, gstRate: Double) {
        customLines.add(CustomLine(Uuid.random().toString(), name, unitPrice, gstRate, qty.coerceAtLeast(1)))
    }
    /** qty <= 0 removes the line (mirrors [setQty] for products). */
    fun setCustomQty(lineId: String, qty: Int) {
        val i = customLines.indexOfFirst { it.id == lineId }
        if (i < 0) return
        if (qty <= 0) customLines.removeAt(i) else customLines[i] = customLines[i].copy(qty = qty)
    }
    fun removeCustom(lineId: String) { customLines.removeAll { it.id == lineId } }

    val count: Int get() = items.values.sum() + customLines.sumOf { it.qty }
    fun total(): Double = lines().sumOf { (p, q) -> p.effectivePrice * q } + customLines.sumOf { it.unitPrice * it.qty }
    fun clear() { items.clear(); products.clear(); customLines.clear() }
    val isEmpty: Boolean get() = items.isEmpty() && customLines.isEmpty()
}

/**
 * POS cart shared across the billing UI. Holds MULTIPLE open sales (one active +
 * any number on hold) so an operator can park a bill and ring up another, then
 * switch back — surfaced as a chip row. The legacy single-cart API delegates to
 * the active sale, so existing screens keep working unchanged.
 */
@OptIn(ExperimentalUuidApi::class)
class CartStore {
    private var counter = 1
    private fun newId() = Uuid.random().toString()

    /** All open sales (active + held), in creation order — drives the chip row. */
    val sales = mutableStateListOf(Sale(newId(), counter++))
    var activeId by mutableStateOf(sales.first().id)
        private set

    val active: Sale get() = sales.firstOrNull { it.id == activeId } ?: sales.first()

    // ── Legacy single-cart API → active sale (keeps grid/cart screens working) ──
    fun add(p: Product) = active.add(p)
    fun setQty(p: Product, qty: Int) = active.setQty(p, qty)
    fun qty(id: String): Int = active.qty(id)
    fun lines(): List<Pair<Product, Int>> = active.lines()
    fun addCustom(name: String, qty: Int, unitPrice: Double, gstRate: Double) = active.addCustom(name, qty, unitPrice, gstRate)
    fun setCustomQty(lineId: String, qty: Int) = active.setCustomQty(lineId, qty)
    fun removeCustom(lineId: String) = active.removeCustom(lineId)
    val customLines get() = active.customLines
    val count: Int get() = active.count
    fun total(): Double = active.total()
    /** Clear the ACTIVE sale's items (used by "start a new sale" on the FAB). */
    fun clear() = active.clear()

    // ── Multi-sale management ──────────────────────────────────────────────
    /** A short label for a chip, e.g. "Bill 2". */
    fun label(s: Sale): String = "Bill ${s.seq}"

    /** Park the active sale (keep it in the list) and start a fresh active one.
     *  No-op when the active sale is empty (nothing to hold). */
    fun hold() {
        if (active.isEmpty) return
        val s = Sale(newId(), counter++); sales.add(s); activeId = s.id
    }

    /** Start a new empty sale (or focus an existing empty one) — the "+ New" chip. */
    fun newSale() {
        val empty = sales.firstOrNull { it.isEmpty }
        if (empty != null) activeId = empty.id
        else { val s = Sale(newId(), counter++); sales.add(s); activeId = s.id }
    }

    fun switchTo(id: String) { if (sales.any { it.id == id }) activeId = id }

    fun discard(id: String) { sales.removeAll { it.id == id }; ensureOne() }

    /** After a successful save: drop the active sale and focus another (or a fresh one). */
    fun completeActive() { sales.removeAll { it.id == activeId }; ensureOne() }

    private fun ensureOne() {
        if (sales.isEmpty()) sales.add(Sale(newId(), counter++))
        if (sales.none { it.id == activeId }) activeId = sales.first().id
    }
}

val LocalCart = staticCompositionLocalOf<CartStore> { error("LocalCart not provided") }

/**
 * How the operator settled (or deferred) payment at save time.
 * [method] cash/upi/card/payment_link, null = saved without payment (legacy flow).
 * [markPaid] true → the invoice saves as status 'paid' with paid_at stamped.
 * [tendered]/[change] cash-drawer detail (cash only).
 * [reference] transaction correlation id, saved as the invoice's
 * payment_reference — for UPI this is the `tr` embedded in the QR's deep
 * link, so a future bank-email webhook can match tr → invoice → mark paid.
 */
data class PaymentChoice(
    val method: String?,
    val markPaid: Boolean,
    val tendered: Double? = null,
    val change: Double? = null,
    val reference: String? = null,
)

/** The cart's lines as GST engine input — the SAME mapping [createFromCart]
 *  sends, so any total previewed from these matches what gets billed. */
fun cartGstLines(cart: CartStore): List<GstLine> =
    cart.lines().map { (p, q) ->
        GstLine(description = p.displayName, hsn = p.hsnCode, quantity = q.toDouble(), rate = p.effectivePrice, gstRate = p.effectiveGst, productId = p.id)
    } + cart.customLines.map { c ->
        // Free-style counter lines: no catalog product behind them (productId/hsn null).
        GstLine(description = c.name, hsn = null, quantity = c.qty.toDouble(), rate = c.unitPrice, gstRate = c.gstRate, productId = null)
    }

/** The tax-inclusive amount the customer owes for the cart — what the payment
 *  sheet charges. Mirrors [createFromCart]'s GST computation exactly. */
fun cartPayableTotal(
    settings: com.bnm.diagnosis.api.models.InvoiceSettings?,
    cart: CartStore,
    placeOfSupply: String? = null,
): Double = GstTaxEngine.compute(
    cartGstLines(cart),
    settings?.taxId?.trim()?.take(2),
    placeOfSupply?.trim()?.ifBlank { null },
).total

/** Build line items from the cart and create the invoice offline (series number
 *  + GST + outbox). Blank [customerName] → walk-in "Guest". [payment] records
 *  how the sale was settled (null = saved without payment). */
suspend fun com.bnm.diagnosis.chat.BillingRepository.createFromCart(
    businessId: String,
    settings: com.bnm.diagnosis.api.models.InvoiceSettings?,
    cart: CartStore,
    customerName: String?,
    customerPhone: String?,
    customerGstin: String?,
    placeOfSupply: String?,
    payment: PaymentChoice? = null,
): Result<com.bnm.diagnosis.api.models.Invoice> {
    val lines = cartGstLines(cart)
    // Fulfillment key: the sale's explicit pick, else the business default, else
    // the first enabled option — the SAME resolution the cart chips display, so
    // what the operator sees selected is what gets sent. Null when the business
    // never configured pos_flows (server then uses the instant fallback).
    val posOptions = settings?.enabledPosOptions ?: emptyList()
    val fulfillment = cart.active.fulfillment?.takeIf { k -> posOptions.any { it.first == k } }
        ?: settings?.posFlows?.default?.takeIf { d -> posOptions.any { it.first == d } }
        ?: posOptions.firstOrNull()?.first
    return createInvoiceLocal(
        businessId = businessId,
        supplierStateCode = settings?.taxId?.trim()?.take(2),
        placeOfSupply = placeOfSupply?.trim()?.ifBlank { null },
        customerName = customerName?.trim()?.ifBlank { null } ?: "Guest",
        customerPhone = customerPhone?.trim()?.ifBlank { null },
        customerGstin = customerGstin?.trim()?.ifBlank { null },
        lines = lines,
        dueDays = settings?.dueDays ?: 7,
        fulfillment = fulfillment,
        payment = payment,
    )
}
