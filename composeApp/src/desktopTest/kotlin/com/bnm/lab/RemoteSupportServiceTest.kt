package com.bnm.lab

import com.bnm.lab.remote.Ed25519Verifier
import com.bnm.lab.remote.RemoteSupportKeys
import com.bnm.lab.remote.RemoteSupportService
import com.bnm.lab.remote.RemoteSupportStatus
import com.bnm.lab.remote.SessionCode
import com.bnm.lab.remote.SupportAuditRow
import com.bnm.lab.remote.SupportConsent
import com.bnm.lab.remote.SupportStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.net.UnknownHostException
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session engine over a scripted transport and a hand-driven clock: the
 * hello it sends, the phases the banner sees, reconnect backoff, expiry on
 * the monotonic clock, End from both sides, and the audit of it all.
 */
class RemoteSupportServiceTest {

    private val clock = FakeRemoteClock()
    private val transports = ScriptedTransportFactory()
    private val audit = InMemoryAuditStore()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "remote-test").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val host = FakeToolHost()
    private val service = RemoteSupportService(
        relayUrl = { "wss://relay.test" },
        licenseJwt = { "eyJ.test.jwt" },
        labName = { "Test Lab" },
        appVersion = "9.9.9-test",
        transports = transports,
        verifier = Ed25519Verifier(RemoteSupportKeys.DEV_PUBLIC_KEY_SPKI_B64),   // requests are signed with the committed dev key
        clock = clock,
        scope = scope,
        newCode = { "ABCDEFGH" },
        newId = { "3f1c2a8e-6d0b-4c7e-9a1f-000000000002" },
    ).also {
        it.attachToolHost(host)
        it.attachAuditStore(audit)
    }
    private val owner = SupportStarter("owner-1", "Lab Owner")

    @AfterTest fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000L) { block() } }
    private fun phase(p: RemoteSupportStatus.Phase) = await { service.status.first { it.phase == p } }

    /** A relay that accepts the hello straight away. */
    private fun readyTransport(serverOffsetMs: Long = 0L) = transports.thenConnect().also { it.push(readyFrame(clock.wallMs() + serverOffsetMs)) }

    @Test
    fun `start says hello with the licence, the hashed code and the consent, and waits for the engineer`() {
        val t = readyTransport(serverOffsetMs = -1_500L)
        val session = await { service.start(SupportConsent(records = true), 4 * 3_600L, owner) }.getOrThrow()
        assertEquals("ABCDEFGH", session.code)
        assertEquals("ABCD-EFGH", session.displayCode)
        assertEquals(4 * 3_600L, session.durationS)

        val hello = await { t.sent.receive() }.asJson()
        assertEquals("hello", hello["t"]!!.jsonPrimitive.content)
        assertEquals("1", hello["v"]!!.jsonPrimitive.content)
        assertEquals("eyJ.test.jwt", hello["jwt"]!!.jsonPrimitive.content)
        assertEquals(session.id, hello["session_id"]!!.jsonPrimitive.content)
        assertEquals(SessionCode.hash("ABCDEFGH"), hello["code_hash"]!!.jsonPrimitive.content)
        assertFalse("ABCDEFGH" in hello.toString(), "the code itself never goes on the wire")
        assertEquals("Test Lab", hello["lab"]!!.jsonPrimitive.content)
        assertEquals("9.9.9-test", hello["app_version"]!!.jsonPrimitive.content)
        assertEquals(4 * 3_600L, hello["expires_in_s"]!!.jsonPrimitive.content.toLong())
        val consent = hello["consent"] as JsonObject
        assertEquals("false", consent["analyzer_data"]!!.jsonPrimitive.content)
        assertEquals("true", consent["records"]!!.jsonPrimitive.content)
        assertEquals("false", consent["screen"]!!.jsonPrimitive.content)
        assertEquals(listOf("wss://relay.test/v1/lab?session=3f1c2a8e-6d0b-4c7e-9a1f-000000000002"), transports.urls)

        val st = phase(RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER)
        assertTrue(st.isActive)
        assertFalse(st.peerConnected)
        assertEquals(1_500L, st.clockSkewMs, "client − server")
        assertEquals(session, st.session)
        assertEquals(listOf(session), audit.started)
    }

    @Test
    fun `the engineer joins, calls a tool, and the banner counts it`() {
        val t = readyTransport()
        val session = await { service.start(SupportConsent(), 3_600L, owner) }.getOrThrow()
        await { t.sent.receive() } // hello
        t.push("""{"t":"peer","state":"connected"}""")
        assertTrue(phase(RemoteSupportStatus.Phase.ENGINEER_CONNECTED).peerConnected)

        t.push(jsonRpc(JsonPrimitive(1), "initialize"))
        assertNotNull(await { t.sent.receive() }.asJson().result)
        t.push(signedCall(session.id, JsonPrimitive(2), "echo.text", clock.wallMs(), """{"q":"hi"}"""))
        val reply = await { t.sent.receive() }.asJson()
        assertNotNull(reply.result, reply.toString())
        assertEquals(1, await { service.status.first { it.actions == 1 } }.actions)
        assertEquals(1, host.calls.size)

        // Relay noise and a malformed line do not kill the loop.
        t.push("""{"t":"something-new","x":1}""")
        t.push("garbage")
        assertEquals(-32600, await { t.sent.receive() }.asJson().errorCode)

        t.push("""{"t":"peer","state":"disconnected"}""")
        assertFalse(phase(RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER).peerConnected)
        // …and a returning engineer may initialize again.
        t.push("""{"t":"peer","state":"connected"}""")
        t.push(jsonRpc(JsonPrimitive(3), "initialize"))
        assertNotNull(await { t.sent.receive() }.asJson().result)

        val history = await { service.history(10) }
        assertEquals(listOf("echo.text"), history.map { it.tool })
        assertEquals(SupportAuditRow.Outcome.OK, history.single().outcome)
    }

    /**
     * The consent dialog closes the moment a session starts, taking its
     * rememberCoroutineScope with it — so start() finishes inside a scope that
     * is already being cancelled. Seen in a real session: the session ran, its
     * calls were signed and consent-checked, and the audit row saying WHO
     * opened it and what they agreed to never landed.
     */
    @Test
    fun `the session is audited even when the caller's scope is cancelled under it`() {
        val t = readyTransport()
        val caller = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val started = java.util.concurrent.CountDownLatch(1)
        caller.launch {
            service.start(SupportConsent(records = true), 3_600L, owner)
            started.countDown()
            // What the dialog does: closes, cancelling everything it owns.
            caller.cancel()
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "start() returned")
        await { t.sent.receive() }

        val row = audit.started.firstOrNull()
        assertNotNull(row, "the start MUST be on record even though the caller was cancelled")
        assertTrue(row.consent.records, "and with the consent the owner actually gave")
    }

    @Test
    fun `End sends the end frame, closes, goes OFF and is audited — and a second start is refused meanwhile`() {
        val t = readyTransport()
        await { service.start(SupportConsent(), 3_600L, owner) }.getOrThrow()
        await { t.sent.receive() }
        phase(RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER)

        val again = await { service.start(SupportConsent(), 3_600L, owner) }
        assertTrue(again.isFailure)
        assertTrue("already running" in again.exceptionOrNull()!!.message!!)

        await { service.end("owner pressed End") }
        val off = phase(RemoteSupportStatus.Phase.OFF)
        assertNull(off.session)
        assertFalse(off.isActive)
        assertTrue(t.closed)
        assertEquals(RemoteSupportService.END_FRAME, await { t.sent.receive() })
        assertEquals("owner pressed End", audit.ended.single().third)

        // After End a new session may start.
        readyTransport()
        assertTrue(await { service.start(SupportConsent(), 3_600L, owner) }.isSuccess)
    }

    @Test
    fun `a first connect that fails is a plain reason for the owner, not a retry loop`() {
        transports.thenFail(UnknownHostException("lab-relay.bnmapp.com"))
        val r = await { service.start(SupportConsent(), 3_600L, owner) }
        assertTrue(r.isFailure)
        val message = r.exceptionOrNull()!!.message!!
        assertTrue("not reachable" in message, message)
        assertFalse("bnmapp.com" in message, "no host names in what the lab reads")
        val st = service.status.value
        assertEquals(RemoteSupportStatus.Phase.ENDED_ERROR, st.phase)
        assertFalse(st.isActive)
        assertEquals(message, st.lastError)
        assertTrue(audit.ended.single().third.startsWith("could not connect"))
        assertTrue(clock.sleeps.none { it >= RemoteSupportService.BACKOFF_MIN_MS }, "no backoff sleep")
    }

    @Test
    fun `a relay that refuses the hello fails start with its reason`() {
        transports.thenConnect().also { it.push("""{"t":"error","code":"bad_jwt"}""") }
        val r = await { service.start(SupportConsent(), 3_600L, owner) }
        assertTrue(r.isFailure)
        assertTrue("licence" in r.exceptionOrNull()!!.message!!, r.exceptionOrNull()!!.message)
    }

    @Test
    fun `a dropped socket reconnects with 2 s then 4 s backoff, and the backoff resets once through`() {
        val first = readyTransport()
        val session = await { service.start(SupportConsent(), 3_600L, owner) }.getOrThrow()
        await { first.sent.receive() }
        phase(RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER)

        first.drop()
        val rc = phase(RemoteSupportStatus.Phase.RECONNECTING)
        assertEquals(session, rc.session, "the session survives the drop")
        assertNotNull(rc.lastError)
        await { waitForSleep(RemoteSupportService.BACKOFF_MIN_MS) }

        transports.thenFail(UnknownHostException("relay"))
        clock.advance(RemoteSupportService.BACKOFF_MIN_MS)
        await { waitForSleep(2 * RemoteSupportService.BACKOFF_MIN_MS) }
        assertEquals(RemoteSupportStatus.Phase.RECONNECTING, service.status.value.phase)

        val third = readyTransport()
        clock.advance(2 * RemoteSupportService.BACKOFF_MIN_MS)
        await { third.sent.receive() }.asJson().let { assertEquals("hello", it["t"]!!.jsonPrimitive.content) }
        assertEquals(session, phase(RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER).session)
        assertEquals(3, transports.urls.size)

        // Back through: the next drop waits 2 s again, not 8.
        third.drop()
        phase(RemoteSupportStatus.Phase.RECONNECTING)
        await { waitForSleep(RemoteSupportService.BACKOFF_MIN_MS, atLeast = 2) }
        assertEquals(listOf(2_000L, 4_000L, 2_000L), clock.sleeps.filter { it >= RemoteSupportService.BACKOFF_MIN_MS })
    }

    @Test
    fun `the session ends on the monotonic clock when the time is up`() {
        val t = readyTransport()
        await { service.start(SupportConsent(), RemoteSupportService.MIN_DURATION_S, owner) }.getOrThrow()
        await { t.sent.receive() }
        phase(RemoteSupportStatus.Phase.WAITING_FOR_ENGINEER)
        await { waitForSleep(RemoteSupportService.TICK_MS) }

        clock.advance(30_000L)
        assertEquals(30L, await { service.status.first { it.remainingS == 30L } }.remainingS)
        await { waitForSleep(RemoteSupportService.TICK_MS, atLeast = 2) }
        // The wall clock is irrelevant: wind it back a day, the session still ends.
        clock.wall -= 86_400_000L
        clock.advance(31_000L)
        val off = phase(RemoteSupportStatus.Phase.OFF)
        assertNull(off.session)
        assertEquals(RemoteSupportService.END_FRAME, await { t.sent.receive() })
        assertEquals("time is up", audit.ended.single().third)
        assertTrue(t.closed)
    }

    @Test
    fun `the relay ending the session turns it off`() {
        val t = readyTransport()
        await { service.start(SupportConsent(), 3_600L, owner) }.getOrThrow()
        await { t.sent.receive() }
        t.push("""{"t":"end"}""")
        phase(RemoteSupportStatus.Phase.OFF)
        assertEquals("closed by the relay", audit.ended.single().third)
    }

    @Test
    fun `no licence, no session`() {
        val noLicence = RemoteSupportService(
            relayUrl = { "wss://relay.test" }, licenseJwt = { null }, labName = { null }, appVersion = "t",
            transports = transports, verifier = { _, _ -> false }, clock = clock, scope = scope,
        )
        val r = await { noLicence.start(SupportConsent(), 3_600L, owner) }
        assertTrue("licence" in r.exceptionOrNull()!!.message!!)
        assertTrue(transports.urls.isEmpty())
    }

    @Test
    fun `two hosts may not offer the same tool`() {
        val e = runCatching { service.attachToolHost(FakeToolHost()) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && "echo.text" in e.message!!, e?.toString())
    }

    /** Spin until the clock has seen [ms] requested at least [atLeast] times. */
    private suspend fun waitForSleep(ms: Long, atLeast: Int = 1) {
        while (clock.sleeps.count { it == ms } < atLeast) kotlinx.coroutines.delay(5)
    }
}
