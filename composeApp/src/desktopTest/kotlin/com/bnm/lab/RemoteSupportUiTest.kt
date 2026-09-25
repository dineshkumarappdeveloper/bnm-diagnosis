package com.bnm.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.bnm.lab.remote.RemoteSupportBanner
import com.bnm.lab.remote.RemoteSupportCopy
import com.bnm.lab.remote.RemoteSupportDialogBody
import com.bnm.lab.remote.RemoteSupportStatus
import com.bnm.lab.remote.SupportAuditRow
import com.bnm.lab.remote.SupportConsent
import com.bnm.lab.remote.SupportSession
import com.bnm.lab.remote.SupportStarter
import com.bnm.lab.staff.SecretHash
import com.bnm.lab.staff.Staff
import com.bnm.lab.staff.StaffRole
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.ui.theme.ThemeChoice
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Draws each state of the remote-support UI (owner gate, consent, running
 * session with history, the banner) — one ImageComposeScene per test, as
 * RevenueScreenRenderTest does, because a layout-time crash never shows in a
 * unit test and would close the app the moment an owner opened the dialog.
 * Frames land in build/remote-support-render for a human look.
 */
class RemoteSupportUiTest {

    private val owner = Staff(id = "owner-1", name = "Dr. Meena Rao", role = StaffRole.OWNER, pinHash = SecretHash.hashPin("2196", "salt"))
    private val technician = Staff(id = "tech-1", name = "Arun", role = StaffRole.TECHNICIAN)
    private val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    private val session = SupportSession(
        id = "3f1c2a8e-6d0b-4c7e-9a1f-000000000003",
        code = "7HN4K2PX",
        startedAtMs = now - 15 * 60_000L,
        durationS = 4 * 3_600L,
        consent = SupportConsent(records = true),
        startedBy = SupportStarter(owner.id, owner.name),
    )
    private val history = listOf(
        row("instruments.restart", "restart mispa-1", SupportAuditRow.Outcome.OK, now - 60_000L),
        row("db.query", "refused: Not allowed: the lab owner did not give records consent", SupportAuditRow.Outcome.REFUSED, now - 120_000L),
        row("instruments.probe", "failed: Port COM3 could not be opened", SupportAuditRow.Outcome.FAILED, now - 200_000L),
        row("instruments.list", "2 instruments", SupportAuditRow.Outcome.OK, now - 300_000L),
        row("lab.overview", "overview", SupportAuditRow.Outcome.OK, now - 400_000L),
    )

    private fun row(tool: String, summary: String, outcome: SupportAuditRow.Outcome, at: Long) =
        SupportAuditRow("id-$tool-$at", session.id, at, tool, summary, outcome, 120L, owner.id)

    private fun render(name: String, width: Int = 720, height: Int = 1_000, content: @Composable () -> Unit) {
        val out = File("build/remote-support-render").apply { mkdirs() }
        ImageComposeScene(width, height, Density(1f)) {
            AppTheme(themeChoice = ThemeChoice.LIGHT) {
                Surface { Column(Modifier.padding(16.dp)) { content() } }
            }
        }.use { scene ->
            var img = scene.render(0)
            repeat(6) { i -> img = scene.render((i + 1) * 50_000_000L) }
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test
    fun `the consent screen lays out for a signed-in owner`() = render("consent") {
        RemoteSupportDialogBody(
            status = RemoteSupportStatus(),
            signedIn = owner,
            owners = listOf(owner),
            verifyOwnerPin = { _, _ -> true },
            history = emptyList(),
            onStart = { _, _, _ -> Result.failure(IllegalStateException("no relay in a render test")) },
            onEnd = {},
        )
    }

    @Test
    fun `a technician at the seat is asked for the owner's PIN`() = render("owner-gate") {
        RemoteSupportDialogBody(
            status = RemoteSupportStatus(phase = RemoteSupportStatus.Phase.ENDED_ERROR, lastError = "no internet connection, or BNM's relay is not reachable"),
            signedIn = technician,
            owners = listOf(owner, owner.copy(id = "owner-2", name = "Suresh")),
            verifyOwnerPin = { _, _ -> false },
            history = emptyList(),
            onStart = { _, _, _ -> Result.failure(IllegalStateException("unreachable")) },
            onEnd = {},
        )
    }

    @Test
    fun `the running session shows the code, the live status and Support history`() = render("running", height = 1_100) {
        RemoteSupportDialogBody(
            status = RemoteSupportStatus(
                phase = RemoteSupportStatus.Phase.ENGINEER_CONNECTED,
                session = session,
                remainingS = 3 * 3_600L + 45 * 60L,
                peerConnected = true,
                actions = history.size,
            ),
            signedIn = owner,
            owners = listOf(owner),
            verifyOwnerPin = { _, _ -> true },
            history = history,
            onStart = { _, _, _ -> Result.success(session) },
            onEnd = {},
        )
    }

    @Test
    fun `the banner draws each state and nothing when off`() = render("banner", width = 1_280, height = 260) {
        RemoteSupportBanner(RemoteSupportStatus(), onEnd = {})
        RemoteSupportBanner(RemoteSupportStatus(RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER, session, 4 * 3_600L, false, 0), onEnd = {})
        RemoteSupportBanner(RemoteSupportStatus(RemoteSupportStatus.Phase.ENGINEER_CONNECTED, session, 3 * 3_600L, true, 3), onEnd = {})
        RemoteSupportBanner(RemoteSupportStatus(RemoteSupportStatus.Phase.RECONNECTING, session, 3_000L, false, 12, lastError = "the connection closed"), onEnd = {})
        RemoteSupportBanner(RemoteSupportStatus(RemoteSupportStatus.Phase.CONNECTING, session, 4 * 3_600L, false, 0), onEnd = {})
    }

    // ── the words ──

    @Test
    fun `the banner line names the code, the end time and the count`() {
        val text = RemoteSupportCopy.bannerText(RemoteSupportStatus(RemoteSupportStatus.Phase.ENGINEER_CONNECTED, session, 3_600L, true, 3))
        assertTrue(text.startsWith("BNM support session · code 7HN4-K2PX · ends "), text)
        assertTrue(text.endsWith(" · 3 actions"), text)
        assertTrue(RemoteSupportCopy.bannerText(RemoteSupportStatus(RemoteSupportStatus.Phase.RECONNECTING, session, 3_600L, false, 1)).endsWith("1 action · reconnecting"))
        assertEquals("BNM support session", RemoteSupportCopy.bannerText(RemoteSupportStatus()))
    }

    @Test
    fun `the consent text is the contract's, for the chosen length`() {
        val t = RemoteSupportCopy.consentText(4 * 3_600L)
        assertTrue(t.startsWith("Start a BNM support session? For the next 4 hours, BNM's engineer can see this computer's diagnostics"), t)
        for (phrase in listOf("Every action is recorded in Support history", "cannot see patient records, results or analyzer data unless you allow it below", "You can end the session at any time")) {
            assertTrue(phrase in t, phrase)
        }
        assertTrue("For the next 1 hour," in RemoteSupportCopy.consentText(3_600L))
        assertTrue("For the next 24 hours," in RemoteSupportCopy.consentText(24 * 3_600L))
        assertEquals(4 * 3_600L, RemoteSupportCopy.DEFAULT_DURATION_S)
        assertEquals(listOf(3_600L, 4 * 3_600L, 24 * 3_600L), RemoteSupportCopy.DURATIONS.map { it.first })
    }

    @Test
    fun `remaining time and consent read as plain words`() {
        assertEquals("3 h 45 min left", RemoteSupportCopy.remainingLabel(3 * 3_600L + 45 * 60L))
        assertEquals("12 min left", RemoteSupportCopy.remainingLabel(12 * 60L + 30L))
        assertEquals("under a minute left", RemoteSupportCopy.remainingLabel(30L))
        assertEquals("ending", RemoteSupportCopy.remainingLabel(0L))
        assertEquals("Diagnostics only — no patient records, results or analyzer data.", RemoteSupportCopy.consentSummary(SupportConsent()))
        assertEquals("Diagnostics, plus: analyzer data, screen view.", RemoteSupportCopy.consentSummary(SupportConsent(analyzerData = true, screen = true)))
        assertFalse(RemoteSupportCopy.settingsSubtitle(RemoteSupportStatus()).contains("code"))
        assertTrue("code 7HN4-K2PX" in RemoteSupportCopy.settingsSubtitle(RemoteSupportStatus(RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER, session, 10L, false, 0)))
    }
}
