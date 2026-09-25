package com.bnm.analyzersim.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The window's own path — form → options → core → transcript — against a REAL
 * BNM Lab listening on this machine.
 *
 * Opt-in, like the repo's other end-to-end tests: nothing in CI has a LIMS
 * running, and a test that needs one must not fail a build that does not.
 *
 *   ./gradlew :analyzer-sim-ui:test -Dbnm.lab.live=true --tests '*LiveRunTest*'
 *
 * with BNM Lab open, an instrument row on driver mindray_hl7 / TCP 5500,
 * enabled. It prints the transcript the window would have drawn, so the run can
 * be pasted into a commissioning report.
 */
class LiveRunTest {

    private val enabled = System.getProperty("bnm.lab.live") == "true"
    private val host = System.getProperty("bnm.lab.host") ?: "127.0.0.1"
    private val port = System.getProperty("bnm.lab.port") ?: "5500"

    @Test
    fun `a result, a claim-queue row and half a frame, through the window's own path`() {
        if (!enabled) {
            println("LiveRunTest skipped — pass -Dbnm.lab.live=true with BNM Lab listening.")
            return
        }

        val link = LinkTest.check(base())
        println("Test link: ${link.message}")
        assertTrue(link.ok, "nothing is listening on $host:$port")

        val delivered = run("a result on an accession") {
            it.copy(sampleId = "ACC-S1-00042", patientName = "Asha Menon", patientId = "PAT-9001")
        }
        assertTrue(delivered.any { b -> b.lines.any { it.contains("ACK after") } },
            "BNM Lab sent no ACK — a real BC-5130 would mark the sample 'transmission failed'")

        run("nothing keyed on the analyzer") { it.copy(faults = FaultForm(noSpecimen = true)) }
        run("half a frame") { it.copy(sampleId = "ACC-S1-00043", faults = FaultForm(truncated = true)) }
        run("a critical, with the scattergram") {
            it.copy(sampleId = "ACC-S1-00044", image = true,
                profile = com.bnm.analyzersim.Profile.CRITICAL)
        }
    }

    private fun base() = SimForm().copy(host = host, port = port, autoIncrementId = false)

    /** One run, waited out, printed exactly as the transcript pane would show it. */
    private fun run(what: String, change: (SimForm) -> SimForm): List<TranscriptBlock> {
        val state = SimState(
            PresetStore(File(System.getProperty("java.io.tmpdir"), "bnm-sim-live")),
            CoroutineScope(Dispatchers.Unconfined),
        )
        state.edit { change(base()) }
        println("\n─── $what ───")
        println(labExpectation(state.form))
        state.send()
        val deadline = System.currentTimeMillis() + 30_000
        while (state.running && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(!state.running, "the run never finished")
        println(transcriptText(state.blocks))
        return state.blocks
    }
}
