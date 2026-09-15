package com.bnm.lab

import com.bnm.lab.navigation.BillsTab
import kotlinx.serialization.json.jsonObject
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.api.BillingApi
import com.bnm.lab.api.LabBillingSeries
import com.bnm.lab.billing.BillingScope
import com.bnm.lab.billing.GstLine
import com.bnm.lab.billing.PaymentChoice
import com.bnm.lab.billing.ensureLabBillingSeriesWith
import com.bnm.lab.chat.BillingRepository
import com.bnm.lab.chat.currentFy
import com.bnm.lab.chat.paidAmountOf
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.RefRange
import com.bnm.lab.lab.TestParameter
import com.bnm.lab.revenue.RevenueRange
import com.bnm.lab.revenue.RevenueRepository
import com.bnm.lab.navigation.RouteGuard
import com.bnm.lab.navigation.Screen
import com.bnm.lab.staff.LabPermission
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffRole
import com.bnm.lab.staff.allows
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An offline-edition lab bills, and the bills stay on the PC.
 *
 * Before this, a fresh offline install resolved its business id to "", the bill
 * bootstrap gave up on it, and every registration ended in "bill not created" —
 * so there was nothing for a revenue dashboard to show. These tests run against
 * a real in-memory database.
 */
class OfflineBillingTest {

    private class Harness {
        val db: AppDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
            AppDatabase.Schema.create(it)
            AppDatabase(it)
        }
        // A client that is never meant to be used: every call these tests make must
        // stay local, and a real request would fail the assertions below.
        val billing = BillingRepository(db, BillingApi(HttpClient(), tokenProvider = { null }))
        val lab = LabRepository(db, ApiClient.json)
    }

    private val neverCalled: suspend (String) -> Result<LabBillingSeries> = { fail("an offline lab must not ask the server for a series") }

    private fun line(amount: Double) = GstLine(description = "CBC", hsn = null, quantity = 1.0, rate = amount, gstRate = 0.0)

    @Test
    fun `an offline lab numbers its own bills without touching the network`() = runBlocking {
        val h = Harness()
        val biz = BillingScope.issuingBusinessId(selected = null, licensed = null)
        assertEquals(BillingScope.OFFLINE_BUSINESS_ID, biz, "no business must no longer mean a blank id")

        assertTrue(ensureLabBillingSeriesWith(h.billing, biz, standalone = true, offlineSeries = "LO1", fetchServerSeries = neverCalled))
        val inv = h.billing.createInvoiceLocal(
            businessId = biz, supplierStateCode = null, placeOfSupply = null,
            customerName = "Asha", customerPhone = null, customerGstin = null,
            lines = listOf(line(450.0)), payment = PaymentChoice(method = "cash", markPaid = true),
        ).getOrThrow()

        assertEquals("LAB-LO1-0001", inv.invoiceNumber)
        assertEquals("paid", inv.status)
        // Idempotent: a second call finds the series bound and does not re-mint.
        assertTrue(ensureLabBillingSeriesWith(h.billing, biz, standalone = true, offlineSeries = "LO1", fetchServerSeries = neverCalled))
    }

    @Test
    fun `even a stale business id stays local on the offline edition`() = runBlocking {
        // A PC that once ran a connected licence keeps that business selected.
        val h = Harness()
        assertTrue(ensureLabBillingSeriesWith(h.billing, "biz-from-an-old-licence", standalone = true, offlineSeries = "LO1", fetchServerSeries = neverCalled))
    }

    @Test
    fun `offline series can never collide — across computers or with a connected seat`() = runBlocking {
        // Per install, for single-seat licences too: a seat handed to a replacement
        // PC (or a hardware swap mid-year) must not restart a shared series at 0001.
        val a = BillingScope.offlineSeriesCode(deviceId = "0f6b2c3e-1111-4a2b-9c33-5d6e7f8a9b0c")
        val b = BillingScope.offlineSeriesCode(deviceId = "a91d7e20-2222-4c3d-8e44-6f7a8b9c0d1e")
        assertTrue(a != b, "two computers printed the same numbers: $a")
        assertEquals(a, BillingScope.offlineSeriesCode(deviceId = "0f6b2c3e-1111-4a2b-9c33-5d6e7f8a9b0c"), "stable per install")
        for (code in listOf(a, b)) {
            assertTrue(Regex("^LO[0-9A-Z]{4}$").matches(code), code)
            assertFalse(Regex("^L\\d+$").matches(code), "must not look like a server series: $code")
        }
        // Spread, not just two lucky ids: a thousand installs, essentially no clashes.
        val codes = (0 until 1000).map { BillingScope.offlineSeriesCode("install-$it-${it * 7919}") }.toSet()
        assertTrue(codes.size >= 999, "hash spread too narrow: ${codes.size}")

        // After a move to connected, the server's L1 starts its own sequence — no
        // LAB-L1-0001 can repeat an offline LAB-LO1-0001.
        val h = Harness()
        val offline = BillingScope.OFFLINE_BUSINESS_ID
        ensureLabBillingSeriesWith(h.billing, offline, standalone = true, offlineSeries = "LO1", fetchServerSeries = neverCalled)
        val old = h.billing.createInvoiceLocal(offline, null, null, "P", null, null, listOf(line(100.0))).getOrThrow()
        assertTrue(ensureLabBillingSeriesWith(h.billing, "biz-1", standalone = false, offlineSeries = "LO1") { _ ->
            Result.success(LabBillingSeries(seriesCode = "L1", highWater = 0))
        })
        val next = h.billing.createInvoiceLocal("biz-1", null, null, "Q", null, null, listOf(line(100.0))).getOrThrow()
        assertEquals("LAB-LO1-0001", old.invoiceNumber)
        assertEquals("LAB-L1-0001", next.invoiceNumber)
    }

    @Test
    fun `offline bills never claim to be waiting for a sync`() = runBlocking {
        val h = Harness()
        val offline = BillingScope.OFFLINE_BUSINESS_ID
        ensureLabBillingSeriesWith(h.billing, offline, standalone = true, offlineSeries = "LO1", fetchServerSeries = neverCalled)
        val inv = h.billing.createInvoiceLocal(offline, null, null, "A", null, null, listOf(line(1000.0))).getOrThrow()
        h.billing.recordPaymentLocal(offline, inv.id, 300.0, "cash").getOrThrow()
        h.billing.recordPaymentLocal(offline, inv.id, 200.0, "upi").getOrThrow()

        val b = h.billing.invoiceBalancesFlow(offline).first().single()
        assertTrue(b.offlineArchive)
        assertFalse(b.isPendingSync, "there is no server to be pending for")
        assertFalse(b.hasQueuedPayment)
        // …but the money itself still counts, tender by tender.
        assertEquals(500.0, b.collected)
        assertEquals(mapOf<String?, Double>("cash" to 300.0, "upi" to 200.0), b.queuedByMethod)
    }

    @Test
    fun `offline bills stay visible after the lab moves to the connected edition`() = runBlocking {
        val h = Harness()
        val offline = BillingScope.OFFLINE_BUSINESS_ID
        ensureLabBillingSeriesWith(h.billing, offline, standalone = true, offlineSeries = "LO1", fetchServerSeries = neverCalled)
        val old = h.billing.createInvoiceLocal(offline, null, null, "Old", null, null, listOf(line(700.0))).getOrThrow()

        val seen = h.billing.invoiceBalancesFlow("biz-after-migration").first().map { it.invoice.id }
        assertTrue(old.id in seen, "the archive must be part of every balance read")
    }

    @Test
    fun `clear and re-sync never wipes a bill no server holds`() = runBlocking {
        val h = Harness()
        val offline = BillingScope.OFFLINE_BUSINESS_ID
        ensureLabBillingSeriesWith(h.billing, offline, standalone = true, offlineSeries = "LO1", fetchServerSeries = neverCalled)
        val kept = h.billing.createInvoiceLocal(offline, null, null, "Kept", null, null, listOf(line(300.0))).getOrThrow()
        h.billing.upsertLocal(BillingRepository.INVOICE, "cached", "biz-1", """{"id":"cached","total":10.0}""")

        h.billing.clearLocalData()

        assertNotNull(h.billing.invoiceById(kept.id), "an offline bill is a record, not cache")
        assertEquals(null, h.billing.invoiceById("cached"), "synced cache is still cleared")
    }

    @Test
    fun `sync refuses the offline key`() = runBlocking {
        val h = Harness()
        // Would be a network call (and a failure) without the guard.
        assertTrue(h.billing.syncInvoices(BillingScope.OFFLINE_BUSINESS_ID).isSuccess)
        assertEquals(null, h.billing.lastSyncedFlow(BillingRepository.INVOICE, BillingScope.OFFLINE_BUSINESS_ID).first())
    }

    @Test
    fun `a bill paid at the counter reads paid when the server row says paid_amount 0`() {
        // The server create path never writes a payment row for paid-at-save
        // bills; 14 of SRT Diagnostics' 19 lab bills look exactly like this.
        assertEquals(1000.0, paidAmountOf("paid", 1000.0, JsonPrimitive(0.0)))
        assertEquals(1000.0, paidAmountOf("paid", 1000.0, null))
        assertEquals(1200.0, paidAmountOf("paid", 1000.0, JsonPrimitive(1200.0)))
        assertEquals(300.0, paidAmountOf("partial", 1000.0, JsonPrimitive(300.0)))
        assertEquals(0.0, paidAmountOf("pending", 1000.0, null))
    }

    @Test
    fun `the dashboard sees an offline lab's order and its bill`() = runBlocking {
        val h = Harness()
        val biz = BillingScope.OFFLINE_BUSINESS_ID
        h.lab.upsertTest(LabTest(id = "t-glu", code = "GLU", name = "Glucose", price = 120.0,
            parameters = listOf(TestParameter(key = "glu", name = "Glucose", unit = "mg/dL", decimals = 0,
                ranges = listOf(RefRange(low = 70.0, high = 110.0))))))
        val p = h.lab.upsertPatient(Patient(id = "p1", name = "Asha", sex = "F", ageYears = 30))
        val order = h.lab.createLabOrder(p.id, testIds = listOf("t-glu")).getOrThrow()
        ensureLabBillingSeriesWith(h.billing, biz, standalone = true, offlineSeries = "LO1", fetchServerSeries = neverCalled)
        val inv = h.billing.createInvoiceLocal(biz, null, null, "Asha", null, null, listOf(line(120.0)),
            payment = PaymentChoice(method = "upi", markPaid = false)).getOrThrow()
        h.lab.linkInvoice(order.id, inv.id)
        h.billing.recordPaymentLocal(biz, inv.id, 50.0, "upi").getOrThrow()

        // Bills are dated in IST (issued_at); orders by the zone passed here.
        val ist = TimeZone.of("Asia/Kolkata")
        val today = kotlin.time.Clock.System.now().toLocalDateTime(ist).date
        val report = RevenueRepository(h.db, h.billing).reportFlow(biz, RevenueRange(today, today), ist).first()

        assertEquals(120.0, report.current.billed)
        assertEquals(50.0, report.current.collected, "a tender queued on an offline PC is the record of that money")
        assertEquals(70.0, report.current.due)
        assertEquals(1, report.current.orders)
        assertEquals(0, report.unbilledOrders)
    }

    @Test
    fun `revenue is owner-only on the shared Bills page`() {
        val owner = Staff(id = "o", name = "Owner", role = StaffRole.OWNER)
        for (role in listOf(StaffRole.PATHOLOGIST, StaffRole.TECHNICIAN, StaffRole.RECEPTIONIST)) {
            val who = Staff(id = role, name = role, role = role)
            assertFalse(who.allows(LabPermission.REVENUE), role)
            // Asking for the revenue tab still lands on Bills, with no tab row.
            assertEquals(BillsTab.BILLS, BillsTab.resolve("revenue", who), role)
            assertEquals(listOf(BillsTab.BILLS), BillsTab.visibleTo(who), role)
        }
        assertTrue(owner.allows(LabPermission.REVENUE))
        assertEquals(BillsTab.REVENUE, BillsTab.resolve("revenue", owner))
        assertEquals(listOf(BillsTab.BILLS, BillsTab.REVENUE), BillsTab.visibleTo(owner))
        // An owner who is also the pathologist keeps every owner surface.
        assertEquals(BillsTab.REVENUE, BillsTab.resolve("revenue", owner.copy(alsoPathologist = true)))
        // Nobody signed in, an unknown slug, or the bare route pattern: Bills.
        assertEquals(BillsTab.BILLS, BillsTab.resolve("revenue", null))
        assertEquals(BillsTab.BILLS, BillsTab.resolve("{tab}", owner))
        assertEquals(BillsTab.BILLS, BillsTab.resolve(null, owner))
        // The page itself is open to the desk — people collect payments there.
        assertEquals(null, RouteGuard.requirement(Screen.Bills.route))
        assertEquals("bills?tab=revenue", Screen.Bills.createRoute(BillsTab.REVENUE))
        assertEquals("bills", Screen.Bills.createRoute())
    }

    @Test
    fun `a pull never writes back an older copy of a bill the drain just refreshed`() = runBlocking {
        val h = Harness()
        fun row(seq: Long, paid: Double) = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"inv-1","total":1000.0,"status":"partial","paid_amount":$paid,"series_code":"L1","seq":$seq}""")
        // The drain stored the post-tender row (seq 42, ₹700 in)…
        h.billing.storeDeltaPage(BillingRepository.INVOICE, "biz-1", listOf(row(42, 700.0)))
        // …then a pull that fetched before the tender writes its page (seq 41, ₹0).
        h.billing.storeDeltaPage(BillingRepository.INVOICE, "biz-1", listOf(row(41, 0.0)))
        assertEquals(700.0, h.billing.invoiceBalancesFlow("biz-1").first().single().paidAmount,
            "the older row must not erase a payment")
        // A genuinely newer server row still wins.
        h.billing.storeDeltaPage(BillingRepository.INVOICE, "biz-1", listOf(row(43, 1000.0)))
        assertEquals(1000.0, h.billing.invoiceBalancesFlow("biz-1").first().single().paidAmount)
    }

    @Test
    fun `a licence with biz null has no business — not one called "null"`() {
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"lid":"l1","biz":null,"ed":"standalone","lab":"Asha Labs"}""").jsonObject
        assertEquals(null, com.bnm.lab.license.claimString(payload, "biz"),
            "JSON null read as the text \"null\" filed every offline bill under a phantom business")
        assertEquals("standalone", com.bnm.lab.license.claimString(payload, "ed"))
        assertEquals(null, com.bnm.lab.license.claimString(payload, "missing"))
        assertEquals(BillingScope.OFFLINE_BUSINESS_ID, BillingScope.issuingBusinessId(null, com.bnm.lab.license.claimString(payload, "biz")))
    }

    @Test
    fun `bills an older offline build filed under "null" move to the archive, numbers intact`() = runBlocking {
        val h = Harness()
        // What an installed offline build did: series L1 under the phantom key.
        h.billing.registerSeriesLocal("null", "L1", currentFy(), "LAB", "{prefix}-{series}-{seq}", 0)
        val old = h.billing.createInvoiceLocal("null", null, null, "Old", null, null, listOf(line(400.0))).getOrThrow()
        h.billing.recordPaymentLocal("null", old.id, 150.0, "cash").getOrThrow()
        assertEquals("LAB-L1-0001", old.invoiceNumber)

        assertEquals(1L, h.billing.adoptNullBusinessBills())
        assertEquals(0L, h.billing.adoptNullBusinessBills(), "idempotent")

        val b = h.billing.invoiceBalancesFlow(BillingScope.OFFLINE_BUSINESS_ID).first().single()
        assertEquals(old.id, b.invoice.id)
        assertEquals("LAB-L1-0001", b.invoice.invoiceNumber, "a printed number never changes")
        assertEquals(150.0, b.collected, "its queued tender moved with it")
        assertTrue(b.offlineArchive)

        // New offline bills get the per-device series…
        ensureLabBillingSeriesWith(h.billing, BillingScope.OFFLINE_BUSINESS_ID, standalone = true, offlineSeries = "LOAB12", fetchServerSeries = neverCalled)
        val fresh = h.billing.createInvoiceLocal(BillingScope.OFFLINE_BUSINESS_ID, null, null, "New", null, null, listOf(line(10.0))).getOrThrow()
        assertEquals("LAB-LOAB12-0001", fresh.invoiceNumber)
        // …and if the lab later goes connected and the server hands this seat L1,
        // it continues past the LAB-L1-0001 already printed here.
        ensureLabBillingSeriesWith(h.billing, "biz-1", standalone = false, offlineSeries = "LOAB12") { _ ->
            Result.success(LabBillingSeries(seriesCode = "L1", highWater = 0))
        }
        val connected = h.billing.createInvoiceLocal("biz-1", null, null, "C", null, null, listOf(line(10.0))).getOrThrow()
        assertEquals("LAB-L1-0002", connected.invoiceNumber)
    }

    @Test
    fun `a pulled bill that already holds a still-queued tender is not counted twice`() = runBlocking {
        val h = Harness()
        fun row(seq: Long, paid: Double, status: String) = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"inv-9","total":1000.0,"status":"$status","paid_amount":$paid,"series_code":"L1","seq":$seq}""")
        h.billing.storeDeltaPage(BillingRepository.INVOICE, "biz-1", listOf(row(10, 0.0, "pending")))
        // ₹500 taken at the desk; its POST reaches the server but the reply is lost,
        // so it stays queued while the server row already includes it.
        h.billing.recordPaymentLocal("biz-1", "inv-9", 500.0, "cash").getOrThrow()
        val written = h.billing.storeDeltaPage(BillingRepository.INVOICE, "biz-1", listOf(row(11, 500.0, "partial")))

        val b = h.billing.invoiceBalancesFlow("biz-1").first().single()
        assertEquals(500.0, b.collected, "server 500 + queued 500 would read Paid and release the report")
        assertFalse(b.isSettled)
        assertEquals(11L, written.maxSeq, "the cursor still moves on; the drain adopts the bill when the tender lands")
    }

    @Test
    fun `a bill with a queued tender but no local row is still shown`() = runBlocking {
        val h = Harness()
        // Clear & re-sync removed the row; the tender is still queued.
        h.billing.recordPaymentLocal("biz-1", "inv-7", 200.0, "upi").getOrThrow()
        val row = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"inv-7","total":900.0,"status":"pending","paid_amount":0.0,"series_code":"L1","seq":5}""")
        h.billing.storeDeltaPage(BillingRepository.INVOICE, "biz-1", listOf(row))
        assertEquals(listOf("inv-7"), h.billing.invoiceBalancesFlow("biz-1").first().map { it.invoice.id })
    }

    @Test
    fun `a licence moved to another business starts that business's own serial`() = runBlocking {
        val h = Harness()
        h.billing.registerSeriesLocal("biz-1", "L1", currentFy(), "LAB", "{prefix}-{series}-{seq}", 300)
        ensureLabBillingSeriesWith(h.billing, "biz-2", standalone = false, offlineSeries = "LOAB12") { _ ->
            Result.success(LabBillingSeries(seriesCode = "L1", highWater = 0))
        }
        val first = h.billing.createInvoiceLocal("biz-2", null, null, "X", null, null, listOf(line(10.0))).getOrThrow()
        assertEquals("LAB-L1-0001", first.invoiceNumber, "another business's 300 bills are not this one's history")
    }
}
