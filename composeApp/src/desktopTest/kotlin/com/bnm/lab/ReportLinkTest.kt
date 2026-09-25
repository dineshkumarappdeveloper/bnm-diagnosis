package com.bnm.lab

import com.bnm.lab.api.Constants
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.report.ReportShare
import com.russhwolf.settings.PropertiesSettings
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which link a printed QR encodes.
 *
 * The rule this file exists to hold: PAPER CANNOT BE RECALLED. app.bnmapp.com/r/
 * ships on BNMClient's timetable, not the lab app's, and a sheet printed the day
 * before that page is live is dead for the life of the sheet — worse than a 404,
 * because BNMClient serves its SPA shell for unknown paths, so the patient lands
 * on a phone-OTP screen and the link merely looks like it worked.
 *
 * So the app prints the page link only when a server has said the page is live,
 * and otherwise prints the permanent `admin-lab /reports/r/` resolver, which
 * decides at SCAN time (302 to the page once it is live, the old way until
 * then) — a decision that keeps working for paper already in patients' hands.
 */
class ReportLinkTest {

    private val token = "a".repeat(64)

    @Test
    fun `by default the printed link is the resolver that works in every state of the rollout`() {
        assertEquals(
            "${Constants.EDGE_FUNCTIONS_BASE_URL}/admin-lab/reports/r/$token",
            ReportShare.resolveUrl(token),
            "a caller that says nothing about the page must get the link that always resolves",
        )
        assertEquals(ReportShare.resolverUrl(token), ReportShare.resolveUrl(token))
        // The India ref, spelled out: that function name and that project are on
        // paper in patients' hands and can never move.
        assertTrue("voelldnyfamrbzvthgfk" in ReportShare.resolveUrl(token))
        assertTrue("/admin-lab/reports/r/" in ReportShare.resolveUrl(token))
    }

    @Test
    fun `once the server says the page is live the token moves into the fragment`() {
        val url = ReportShare.resolveUrl(token, pageLive = true)
        assertEquals("https://app.bnmapp.com/r/#$token", url)
        assertTrue(url.substringBefore('#').endsWith("/r/"))
        // The whole point of the fragment: a browser never sends it, so the
        // capability stays out of edge logs and Referer headers.
        assertFalse(token in url.substringBefore('#'), "the token must never sit in the path")
    }

    @Test
    fun `both links encode as a QR at ECC M — the longer one included`() {
        val resolver = assertNotNull(ReportShare.qrFor(token), "the resolver link must still fit a symbol")
        assertEquals(ReportShare.resolverUrl(token), resolver.url)
        assertTrue(resolver.matrix.size >= 21 && resolver.matrix.size % 4 == 1, "a real QR version")

        val page = assertNotNull(ReportShare.qrFor(token, pageLive = true))
        assertEquals("https://app.bnmapp.com/r/#$token", page.url)
        assertTrue(
            page.matrix.size <= resolver.matrix.size,
            "the page link is the shorter payload, so it can never need a bigger symbol",
        )
    }

    // ── The flag itself ──────────────────────────────────────────────────────

    private fun manager() = LicenseManager(PropertiesSettings(Properties()), { true }, { 1_800_000_000L })

    @Test
    fun `a lab that has never heard from the server prints the resolver`() {
        assertFalse(manager().state.value.reportPageLive)
    }

    @Test
    fun `the heartbeat turns the page on, and silence does not turn it off`() {
        val lm = manager()
        lm.applyHeartbeat(null, null, null, null, null, reportPageLive = true)
        assertTrue(lm.state.value.reportPageLive, "the server said the page is live")

        // An older admin-lab that does not send the field at all must not be
        // read as "the page is gone" — the lab would silently go back to
        // printing the other link mid-week.
        lm.applyHeartbeat(null, null, null, null, null, reportPageLive = null)
        assertTrue(lm.state.value.reportPageLive, "silence keeps the last answer")

        // An explicit false (page rolled back) is obeyed.
        lm.applyHeartbeat(null, null, null, null, null, reportPageLive = false)
        assertFalse(lm.state.value.reportPageLive)
    }

    @Test
    fun `the answer survives the app being closed, so an offline print still knows it`() {
        val store = PropertiesSettings(Properties())
        LicenseManager(store, { true }, { 1_800_000_000L })
            .applyHeartbeat(null, null, null, null, null, reportPageLive = true)
        // A fresh manager over the SAME storage: the lab has restarted, and may
        // well be printing offline for a week before the next heartbeat.
        assertTrue(LicenseManager(store, { true }, { 1_800_000_000L }).state.value.reportPageLive)
    }

    @Test
    fun `deactivating the device forgets it with everything else`() {
        val store = PropertiesSettings(Properties())
        val lm = LicenseManager(store, { true }, { 1_800_000_000L })
        lm.applyHeartbeat(null, null, null, null, null, reportPageLive = true)
        lm.clearLicense()
        assertFalse(lm.state.value.reportPageLive)
    }
}
