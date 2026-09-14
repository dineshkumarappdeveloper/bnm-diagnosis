package com.bnm.lab

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.BillingApi
import com.bnm.lab.billing.BillingScope
import com.bnm.lab.chat.BillingRepository
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.revenue.RevenueRepository
import com.bnm.lab.screens.lab.RevenueScreen
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.ui.theme.ThemeChoice
import io.ktor.client.HttpClient
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours

/**
 * Draws the Revenue screen with a month of data. Unit tests never lay out
 * Compose, and a layout-time crash (a SubcomposeLayout inside the KPI rows'
 * IntrinsicSize.Min) once passed every one of them while closing the app the
 * moment an owner opened the dashboard. Rendering is the only honest check.
 */
class RevenueScreenRenderTest {
    @Test
    fun `the revenue screen lays out with a month of data`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val db = AppDatabase(driver)
        val billing = BillingRepository(db, BillingApi(HttpClient(), tokenProvider = { null }))
        val tz = TimeZone.currentSystemDefault()
        val today = kotlin.time.Clock.System.now().toLocalDateTime(tz).date
        val tests = listOf("CBC" to 350.0, "Lipid profile" to 650.0, "HbA1c" to 480.0, "Thyroid (TSH)" to 300.0,
            "LFT" to 550.0, "KFT" to 500.0, "Vitamin D" to 1200.0, "Urine routine" to 150.0, "Dengue NS1" to 600.0, "CRP" to 400.0)
        driver.execute(null, "INSERT INTO referrers(id,name,kind,phone,commission_pct,created_at) VALUES ('r1','Dr. Meena Rao','doctor',NULL,10,'2026-01-01'),('r2','City Clinic','clinic',NULL,15,'2026-01-01')", 0)
        var n = 0
        val methods = listOf("cash", "upi", "upi", "card", null)
        for (back in 20 downTo 0) {
            val day = today.minus(DatePeriod(days = back))
            val count = 2 + (back * 7) % 5
            repeat(count) { k ->
                n++
                val created = (day.atStartOfDayIn(tz) + (9 + k).hours).toString()
                val picks = listOf(tests[(n + k) % tests.size], tests[(n * 3) % tests.size]).distinct()
                val ref = when (n % 3) { 0 -> "'r1'"; 1 -> "'r2'"; else -> "NULL" }
                val status = if (n % 17 == 0) "cancelled" else "reported"
                val inv = "inv$n"
                driver.execute(null, "INSERT INTO lab_orders(id,accession_no,patient_id,referrer_id,invoice_id,status,priority,created_at,updated_at) VALUES ('o$n','ACC-S1-$n','p1',$ref,'$inv','$status','routine','$created','$created')", 0)
                var total = 0.0
                picks.forEachIndexed { i, (name, price) ->
                    total += price
                    val pct = if (ref == "NULL") 0 else 10
                    driver.execute(null, "INSERT INTO lab_order_tests(id,order_id,test_id,test_name,price,status,commission_pct) VALUES ('ot$n-$i','o$n','t-$name','$name',$price,'entered',$pct)", 0)
                }
                val m = methods[n % methods.size]
                val paidStatus = when { n % 6 == 0 -> "pending"; n % 4 == 0 -> "partial"; else -> "paid" }
                val paidAmount = when (paidStatus) { "paid" -> total; "partial" -> total / 2; else -> 0.0 }
                val json = """{"id":"$inv","invoice_number":"LAB-L1-${n.toString().padStart(4,'0')}","issued_at":"$day","total":$total,"subtotal":$total,"status":"$paidStatus","paid_amount":$paidAmount,"series_code":"L1","payment_method":${m?.let { "\"$it\"" } ?: "null"},"created_at":"$created"}"""
                billing.run { kotlinx.coroutines.runBlocking { upsertLocal(BillingRepository.INVOICE, inv, BillingScope.OFFLINE_BUSINESS_ID, json, created) } }
            }
        }
        val revenue = RevenueRepository(db, billing)
        val out = File("build/revenue-render").apply { mkdirs() }
        // One scene per run: a second ImageComposeScene in the same test never
        // received its data in this harness.
        ImageComposeScene(1440, 1500, Density(1f)) {
            AppTheme(themeChoice = ThemeChoice.LIGHT) {
                RevenueScreen(revenue, BillingScope.OFFLINE_BUSINESS_ID, offlineEdition = true, onBack = {})
            }
        }.use { scene ->
            var img = scene.render(0)
            // Frames until the report has arrived and the tiles are laid out; a
            // layout exception surfaces from render() and fails the test.
            repeat(120) { i -> Thread.sleep(50); img = scene.render((i + 1) * 50_000_000L) }
            File(out, "revenue-wide.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
