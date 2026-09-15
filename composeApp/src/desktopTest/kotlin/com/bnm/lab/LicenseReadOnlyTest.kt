package com.bnm.lab

import com.bnm.lab.license.LicenseClaims
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.license.LicenseStanding
import com.bnm.lab.license.LicenseState
import com.bnm.lab.license.ReadOnlyCopy
import com.bnm.lab.license.ReadOnlyReason
import com.bnm.lab.license.SubscriptionState
import com.bnm.lab.license.subscriptionStatus
import com.bnm.lab.license.subscriptionStatusOf
import com.bnm.lab.navigation.LicenceGate
import com.bnm.lab.navigation.Screen
import com.russhwolf.settings.PropertiesSettings
import java.util.Base64
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A lapsed subscription is READ-ONLY, not locked out.
 *
 * The app used to pick its first screen with `isLicensed()`, so a subscription
 * past lic_exp + gr opened on Activation and could reach none of its records —
 * while the licence card, the KDoc and CLAUDE.md all promised they "stay
 * readable/printable". And the renewal warning counted a fixed 45 days on the
 * wall clock, so a trial key signed with `gr=0` announced days of grace after it
 * had already stopped working.
 *
 * Pure checks pin the rules; the LicenseManager checks run the real class on an
 * in-memory settings store (the no-arg store on JVM is the operator's real
 * preferences — a test must never write there).
 */
class LicenseReadOnlyTest {

    private val t = 1_800_000_000L // lic_exp used throughout
    private val hour = 3_600L
    private val day = 24 * hour

    private fun sub(licExp: Long? = t, gr: Long? = null, exp: Long? = null, issuer: String? = LicenseManager.ISSUER) =
        LicenseClaims(
            issuer = issuer, licenseId = "lic-1", labName = "Test Lab", mode = LicenseManager.MODE_SUBSCRIPTION,
            seats = 2, businessId = null, edition = LicenseManager.EDITION_STANDALONE,
            licExp = licExp, graceSeconds = gr, issuedAt = t - 30 * day, exp = exp,
        )

    private fun standing(c: LicenseClaims?, now: Long, signatureValid: Boolean = true) =
        LicenseManager.standingOf(c, signatureValid, storedMode = null, nowSeconds = now)

    // ── Standing: the one answer behind entry, lock and warning ──────────────

    @Test
    fun `no licence, a token that fails verification, or a foreign issuer is not an activation`() {
        assertEquals(LicenseStanding.NONE, standing(null, t))
        assertEquals(LicenseStanding.NONE, standing(sub(), t - day, signatureValid = false))
        assertEquals(LicenseStanding.NONE, standing(sub(issuer = "someone-else"), t - day))
    }

    @Test
    fun `a perpetual licence never lapses`() {
        val perpetual = sub(licExp = null).copy(mode = LicenseManager.MODE_PERPETUAL)
        assertEquals(LicenseStanding.CURRENT, standing(perpetual, t + 3650 * day))
    }

    @Test
    fun `a subscription is current through the last second of its signed grace, then lapsed`() {
        val week = sub(gr = 7 * day)
        assertEquals(LicenseStanding.CURRENT, standing(week, t + 7 * day))
        assertEquals(LicenseStanding.LAPSED, standing(week, t + 7 * day + 1))
    }

    @Test
    fun `a trial key signed with no grace lapses the second its term ends`() {
        val trial = sub(gr = 0)
        assertEquals(LicenseStanding.CURRENT, standing(trial, t))
        assertEquals(LicenseStanding.LAPSED, standing(trial, t + 1))
    }

    @Test
    fun `a token minted before the gr claim keeps the 45-day default`() {
        for (legacy in listOf(sub(gr = null), sub(gr = -5))) {
            assertEquals(LicenseStanding.CURRENT, standing(legacy, t + 45 * day))
            assertEquals(LicenseStanding.LAPSED, standing(legacy, t + 45 * day + 1))
        }
    }

    @Test
    fun `without lic_exp the JWS exp is the end of term`() {
        val c = sub(licExp = null, gr = 0, exp = t + 2 * day)
        assertEquals(LicenseStanding.CURRENT, standing(c, t + 2 * day))
        assertEquals(LicenseStanding.LAPSED, standing(c, t + 2 * day + 1))
    }

    // ── Entry: only a computer with no licence activates ─────────────────────

    @Test
    fun `only a computer without a genuine licence opens on Activation`() {
        assertEquals(Screen.Activation.route, LicenceGate.entryRoute(activated = false))
        assertEquals(Screen.StaffSignIn.route, LicenceGate.entryRoute(activated = true))

        assertFalse(LicenseState().activated, "never activated")
        assertTrue(LicenseState(lapsed = true).activated, "a lapsed subscription still signs staff in")
        assertTrue(LicenseState(licensed = true, blocked = true).activated, "a blocked device still signs staff in")
    }

    // ── New work: refused while read-only, everything else open ──────────────

    private val newWork = listOf(
        Screen.NewOrder.route, Screen.CreateInvoice.route, Screen.Cart.route, Screen.CustomerDetails.route,
    )
    private val readable = listOf(
        Screen.StaffSignIn.route, Screen.LabHome.route, Screen.Patients.route, Screen.LabOrderDetail.route,
        Screen.Bills.route, Screen.InvoiceDetail.route, Screen.Catalog.route, Screen.EmrInbox.route,
        Screen.Settings.route, Screen.PrintSettings.route, Screen.LicenseDevices.route, Screen.Revenue.route,
    )

    @Test
    fun `a lapsed licence reaches every record but cannot start new work`() {
        val lapsed = LicenseState(lapsed = true)
        assertEquals(ReadOnlyReason.EXPIRED, lapsed.readOnlyReason)
        assertFalse(lapsed.canStartNewWork)
        newWork.forEach { assertFalse(LicenceGate.allows(it, lapsed), "$it must refuse a lapsed licence") }
        readable.forEach { assertTrue(LicenceGate.allows(it, lapsed), "$it must stay open to a lapsed licence") }
    }

    @Test
    fun `a current licence may start new work and a block outranks a lapse`() {
        val current = LicenseState(licensed = true)
        assertNull(current.readOnlyReason)
        (newWork + readable).forEach { assertTrue(LicenceGate.allows(it, current), it) }

        assertEquals(ReadOnlyReason.DEACTIVATED, LicenseState(licensed = true, blocked = true).readOnlyReason)
        assertEquals(ReadOnlyReason.DEACTIVATED, LicenseState(lapsed = true, blocked = true).readOnlyReason,
            "renewing does not undo a revoked device — the lab must be told to contact BNM")
    }

    @Test
    fun `the lab is told its records are still there and how new work comes back`() {
        val title = ReadOnlyCopy.title(ReadOnlyReason.EXPIRED)
        val detail = ReadOnlyCopy.detail(ReadOnlyReason.EXPIRED)
        assertTrue("read-only" in title, title)
        for (word in listOf("view", "print", "export", "renew")) assertTrue(word in detail, "detail must say $word: $detail")
        assertTrue("renewed" in ReadOnlyCopy.inline(ReadOnlyReason.EXPIRED))
        assertTrue("contact BNM" in ReadOnlyCopy.title(ReadOnlyReason.DEACTIVATED))
    }

    // ── Warning: signed grace, same arithmetic as the lock ───────────────────

    private fun status(c: LicenseClaims, now: Long) =
        subscriptionStatusOf(c, storedMode = null, storedExpiresAt = null, nowSeconds = now)

    @Test
    fun `a trial key with no grace never offers grace after it stopped working`() {
        val trial = sub(gr = 0)
        val after = status(trial, t + hour)
        assertEquals(SubscriptionState.EXPIRED, after.state)
        assertNull(after.graceDaysLeft)
        assertFalse("grace" in after.notice!!, after.notice)

        val before = status(trial, t - 2 * hour)
        assertEquals(SubscriptionState.EXPIRING_SOON, before.state)
        assertTrue("within a day" in before.notice!! && "new orders stop" in before.notice!!, before.notice)
    }

    @Test
    fun `the signed grace, not a fixed 45 days, drives the countdown`() {
        val week = status(sub(gr = 7 * day), t + 3 * day)
        assertEquals(SubscriptionState.IN_GRACE, week.state)
        assertEquals(4L, week.graceDaysLeft)
        assertTrue("4 days of grace left" in week.notice!!, week.notice)
        assertTrue(week.urgent)

        val legacy = status(sub(gr = null), t + 10 * day)
        assertEquals(35L, legacy.graceDaysLeft, "a token without gr keeps the 45-day default")

        val lastHours = status(sub(gr = 7 * day), t + 7 * day - 3 * hour)
        assertTrue("less than a day of grace left" in lastHours.notice!!, lastHours.notice)
    }

    @Test
    fun `the warning says expired exactly when the lock says lapsed`() {
        val graces = listOf(0L, hour, 7 * day, 45 * day, null)
        for (gr in graces) {
            val g = gr ?: LicenseManager.DEFAULT_GRACE_SECONDS
            val offsets = listOf(-15 * day, -14 * day, -1, 0, 1, g - 1, g, g + 1, g + day)
            for (claims in listOf(sub(gr = gr), sub(licExp = null, gr = gr, exp = t + g))) {
                for (off in offsets) {
                    val now = t + off
                    val expired = status(claims, now).state == SubscriptionState.EXPIRED
                    val lapsed = standing(claims, now) == LicenseStanding.LAPSED
                    assertEquals(lapsed, expired, "gr=$gr lic_exp=${claims.licExp} offset=$off")
                }
            }
        }
    }

    @Test
    fun `a perpetual licence never warns`() {
        val perpetual = sub(licExp = null).copy(mode = LicenseManager.MODE_PERPETUAL)
        val s = status(perpetual, t + 3650 * day)
        assertEquals(SubscriptionState.PERPETUAL, s.state)
        assertNull(s.notice)
    }

    // ── The real LicenseManager, on an in-memory store ───────────────────────

    private class Clock(var now: Long)

    private fun manager(clock: Clock, signatureValid: Boolean = true) =
        LicenseManager(PropertiesSettings(Properties()), { signatureValid }, { clock.now })

    private fun jwt(licExp: Long?, gr: Long?, mode: String = LicenseManager.MODE_SUBSCRIPTION): String {
        val claims = buildList {
            add("\"iss\":\"${LicenseManager.ISSUER}\"")
            add("\"lid\":\"lic-1\"")
            add("\"lab\":\"Test Lab\"")
            add("\"mode\":\"$mode\"")
            add("\"seats\":2")
            add("\"biz\":null")
            add("\"ed\":\"standalone\"")
            licExp?.let { add("\"lic_exp\":$it") }
            gr?.let { add("\"gr\":$it") }
            if (licExp != null) add("\"exp\":${licExp + (gr ?: LicenseManager.DEFAULT_GRACE_SECONDS)}")
        }.joinToString(",", "{", "}")
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{\"alg\":\"ES256\"}".toByteArray()) + "." +
            enc.encodeToString(claims.toByteArray()) + ".c2ln"
    }

    private fun LicenseManager.activate(token: String) = saveActivation(
        licenseJwt = token, deviceToken = "device-token", deviceRowId = "row-1", labName = "Test Lab",
        mode = LicenseManager.MODE_SUBSCRIPTION, seats = 2, expiresAt = null, businessId = null,
    )

    @Test
    fun `a lapsed trial install opens on staff sign-in, read-only, and says expired`() {
        val clock = Clock(t - hour)
        val lm = manager(clock)
        lm.activate(jwt(licExp = t, gr = 0))
        assertTrue(lm.state.value.licensed)

        clock.now = t + hour
        lm.refresh()
        assertTrue(lm.isActivated())
        assertFalse(lm.isLicensed())
        assertEquals(Screen.StaffSignIn.route, LicenceGate.entryRoute(lm.isActivated()))
        assertEquals(ReadOnlyReason.EXPIRED, lm.state.value.readOnlyReason)
        assertFalse(LicenceGate.allows(Screen.NewOrder.route, lm.state.value))
        assertEquals(SubscriptionState.EXPIRED, lm.subscriptionStatus().state)
    }

    @Test
    fun `a renewed licence lifts read-only without a restart`() {
        val clock = Clock(t + hour)
        val lm = manager(clock)
        lm.activate(jwt(licExp = t, gr = 0))
        assertTrue(lm.state.value.lapsed)

        lm.applyHeartbeat(jwt(licExp = t + 365 * day, gr = 0), mode = null, seats = null, expiresAt = null, labName = null)
        assertTrue(lm.state.value.canStartNewWork)
        assertEquals(SubscriptionState.ACTIVE, lm.subscriptionStatus().state)
    }

    @Test
    fun `winding the clock back revives neither the lock nor the grace countdown`() {
        val clock = Clock(t + 2 * day)
        val lm = manager(clock)
        lm.activate(jwt(licExp = t, gr = day))
        assertFalse(lm.isLicensed())

        clock.now = t - 3 * day // operator sets the PC's date back
        assertFalse(lm.isLicensed())
        val s = lm.subscriptionStatus()
        assertEquals(SubscriptionState.EXPIRED, s.state, "the warning must read the guarded clock, not the wall clock")
    }

    @Test
    fun `deactivating, or a token that fails verification, goes back to Activation`() {
        val clock = Clock(t + hour)
        val lm = manager(clock)
        lm.activate(jwt(licExp = t, gr = 0))
        assertTrue(lm.isActivated())
        lm.clearLicense()
        assertFalse(lm.isActivated())
        assertEquals(Screen.Activation.route, LicenceGate.entryRoute(lm.isActivated()))

        val forged = manager(Clock(t - day), signatureValid = false)
        forged.activate(jwt(licExp = t, gr = 0))
        assertEquals(LicenseStanding.NONE, forged.standing())
        assertEquals(Screen.Activation.route, LicenceGate.entryRoute(forged.isActivated()))
    }

    @Test
    fun `a perpetual install is never read-only`() {
        val clock = Clock(t)
        val lm = manager(clock)
        lm.activate(jwt(licExp = null, gr = null, mode = LicenseManager.MODE_PERPETUAL))
        clock.now = t + 3650 * day
        lm.refresh()
        assertTrue(lm.state.value.canStartNewWork)
        assertEquals(SubscriptionState.PERPETUAL, lm.subscriptionStatus().state)
    }
}
