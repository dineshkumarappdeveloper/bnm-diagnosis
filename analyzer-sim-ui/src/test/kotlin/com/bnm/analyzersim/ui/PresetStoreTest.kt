package com.bnm.analyzersim.ui

import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.Profile
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Presets are the thing an engineer trusts without re-reading — load "Mispa on
 * this PC" and press Send. A field that fails to round-trip therefore sends
 * something other than what the preset's name promises.
 */
class PresetStoreTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "bnm-sim-presets-${System.nanoTime()}")

    @AfterTest fun cleanUp() { dir.deleteRecursively() }

    @Test
    fun `every field survives a trip through the file`() {
        val form = SimForm()
            .withAnalyzer(Analyzer.MISPA)
            .withTransport(TransportKind.SERIAL)
            .copy(
                host = "192.168.1.50", port = "5599", serialPort = "COM7", baud = "9600",
                sampleId = "ACC-S1-00042", autoIncrementId = false, count = "4",
                intervalSeconds = "1.5", profile = Profile.THROMBOCYTOPENIA,
                patientName = "Asha Menon", patientId = "PAT-9001",
                qc = true, histograms = false, image = true, seed = "77",
                faults = FaultForm(truncated = true, garbage = true, slowChunks = true, slowChunksMs = "25",
                    unknownCode = true, badUnits = true, noSpecimen = true, duplicate = true,
                    burst = true, burstCount = "9", hang = true),
            )
        val store = PresetStore(dir)
        store.save("Bench 7", form)

        val back = PresetStore(dir).load().first { it.name == "Bench 7" }.toForm()
        assertEquals(form, back, "a preset that does not round-trip sends something else than its name says")
    }

    @Test
    fun `the three built-ins are there before anything has ever been saved`() {
        val names = PresetStore(dir).load().map { it.name }
        assertEquals(listOf("Mindray on this PC", "Mispa on this PC", "Commissioning rehearsal"), names)
    }

    @Test
    fun `the commissioning rehearsal is five samples, two seconds apart, with advancing ids`() {
        val rehearsal = PresetStore(dir).load().first { it.name == "Commissioning rehearsal" }.toForm()
        assertEquals("5", rehearsal.count)
        assertEquals("2", rehearsal.intervalSeconds)
        assertTrue(rehearsal.autoIncrementId)
        assertTrue(rehearsal.problems().isEmpty(), "${rehearsal.problems()}")
    }

    @Test
    fun `the built-in analyzers carry the port their driver listens on`() {
        val all = PresetStore(dir).load().associateBy { it.name }
        assertEquals("5500", all.getValue("Mindray on this PC").toForm().port)
        assertEquals("5501", all.getValue("Mispa on this PC").toForm().port)
        assertEquals(Analyzer.MISPA, all.getValue("Mispa on this PC").toForm().analyzer)
    }

    @Test
    fun `saving over a built-in name replaces it rather than listing it twice`() {
        val store = PresetStore(dir)
        val after = store.save("Mindray on this PC", SimForm().copy(host = "10.1.1.9"))
        assertEquals(1, after.count { it.name == "Mindray on this PC" })
        assertEquals("10.1.1.9", after.first { it.name == "Mindray on this PC" }.toForm().host)
        // Deleting the override brings the shipped one back, rather than
        // leaving the engineer with no Mindray preset at all.
        val restored = store.delete("Mindray on this PC")
        assertEquals("127.0.0.1", restored.first { it.name == "Mindray on this PC" }.toForm().host)
    }

    @Test
    fun `a corrupt file loses the saved presets but still opens the tool`() {
        dir.mkdirs()
        File(dir, "presets.json").writeText("{ this is not json")
        assertEquals(PresetStore.BUILT_IN.map { it.name }, PresetStore(dir).load().map { it.name })
    }

    @Test
    fun `a preset written by a newer build still loads`() {
        dir.mkdirs()
        File(dir, "presets.json").writeText(
            """[{"name":"From the future","analyzer":"mispa","host":"10.0.0.4","what_is_this":true}]"""
        )
        val loaded = PresetStore(dir).load().first { it.name == "From the future" }.toForm()
        assertEquals("10.0.0.4", loaded.host)
        assertEquals(Analyzer.MISPA, loaded.analyzer)
        // Missing keys fall back to the form's own defaults, not to nulls.
        assertEquals("SIM-0001", loaded.sampleId)
    }

    @Test
    fun `a preset that names an impossible link is loaded as the possible one`() {
        dir.mkdirs()
        File(dir, "presets.json").writeText(
            """[{"name":"Hand-edited","analyzer":"mindray","transport":"serial","serial_port":"COM3"}]"""
        )
        val loaded = PresetStore(dir).load().first { it.name == "Hand-edited" }.toForm()
        assertEquals(TransportKind.TCP, loaded.transport, "a Mindray on a cable can never be sent")
    }

    @Test
    fun `the data directory is this tool's own, never BNM Lab's`() {
        val path = PresetStore().path
        assertTrue(path.endsWith("presets.json"), path)
        assertTrue(path.contains("BNMAnalyzerSim"), path)
        assertTrue(!path.contains("BNMLab"), "a test tool must not write into a lab's data directory: $path")
    }
}
