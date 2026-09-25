package com.bnm.analyzersim.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two blocking probes — the link test and the serial-port scan — and what
 * they do to the window when the machine underneath them misbehaves.
 *
 * Both matter on exactly the PCs where they are hardest to reproduce: a client's
 * locked-down Windows laptop, where jSerialComm cannot unpack its native library
 * and raises an ERROR rather than an exception.
 */
class SimStateLinkTest {

    private fun state(
        probe: (SimForm) -> LinkTestResult = { LinkTestResult(true, "ok") },
        ports: () -> List<String> = { emptyList() },
    ) = SimState(
        PresetStore(File(System.getProperty("java.io.tmpdir"), "bnm-sim-probe-${System.nanoTime()}")),
        CoroutineScope(Dispatchers.Unconfined),
        probe = probe,
        ports = ports,
    )

    /** The probes hand back through a channel on their own thread; wait for it. */
    private fun await(what: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!until() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(until(), "timed out waiting for $what")
    }

    @Test
    fun `a link test that answers clears the spinner and shows the verdict`() {
        val s = state(probe = { LinkTestResult(true, "Reached BNM Lab") })
        s.testLink()
        await("the verdict") { !s.testingLink }
        assertEquals(true, s.linkResult?.ok)
        assertTrue(s.linkResult!!.message.contains("Reached BNM Lab"))
    }

    /**
     * The regression this exists for. `LinkTest.checkSerial` caught only
     * Exception, so an UnsatisfiedLinkError from jSerialComm's static
     * initialiser killed the probe thread before it answered. The receive then
     * waited forever: `testingLink` stayed true, the Test-link button stayed a
     * disabled spinner for the rest of the session, and every later press
     * returned at the guard — with no message at all, on the one machine where
     * the engineer most needs the link test.
     */
    @Test
    fun `an Error in the probe still clears the spinner and says something`() {
        val s = state(probe = { throw UnsatisfiedLinkError("jSerialComm native library not found") })
        s.testLink()
        await("the button to come back") { !s.testingLink }
        assertEquals(false, s.linkResult?.ok, "an Error must read as a failed link, not a passed one")
        assertTrue(s.linkResult!!.message.contains("jSerialComm native library not found"),
            "the engineer was told nothing useful: ${s.linkResult?.message}")
    }

    @Test
    fun `the button works again after an Error, rather than being dead for the session`() {
        var fail = true
        val s = state(probe = {
            if (fail) throw NoClassDefFoundError("com/fazecast/jSerialComm/SerialPort")
            LinkTestResult(true, "Reached BNM Lab")
        })
        s.testLink()
        await("the first verdict") { !s.testingLink }
        fail = false
        s.testLink()
        await("the second verdict") { !s.testingLink && s.linkResult?.ok == true }
        assertEquals(true, s.linkResult?.ok)
    }

    @Test
    fun `a probe that dies without answering at all still releases the button`() {
        // Belt and braces for the channel path itself: nothing is ever sent, so
        // the only thing that can clear the spinner is the close in the finally.
        val s = state(probe = { throw ThreadDeath() })
        s.testLink()
        await("the button to come back") { !s.testingLink }
        assertTrue(s.linkResult != null, "the window was left with no answer at all")
    }

    @Test
    fun `the serial scan fills the list without the caller waiting on it`() {
        val s = state(ports = { listOf("COM3", "COM4") })
        s.refreshSerialPorts()
        await("the port list") { !s.scanningPorts }
        assertEquals(listOf("COM3", "COM4"), s.serialPorts)
        assertTrue(s.status!!.contains("COM3"), s.status!!)
    }

    @Test
    fun `the first scan at launch does not talk over the status line`() {
        val s = state(ports = { listOf("COM3") })
        s.refreshSerialPorts(announce = false)
        await("the port list") { !s.scanningPorts }
        assertEquals(listOf("COM3"), s.serialPorts)
        assertEquals(null, s.status, "the launch scan announced itself: ${s.status}")
    }

    @Test
    fun `a serial scan that throws leaves an empty list and a usable Refresh`() {
        val s = state(ports = { throw UnsatisfiedLinkError("no native library") })
        s.refreshSerialPorts()
        await("the scan to give up") { !s.scanningPorts }
        assertEquals(emptyList(), s.serialPorts)
    }
}
