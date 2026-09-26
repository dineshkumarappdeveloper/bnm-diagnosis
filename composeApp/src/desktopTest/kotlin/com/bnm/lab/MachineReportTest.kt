package com.bnm.lab

import com.bnm.lab.instruments.MachineReport
import com.bnm.lab.instruments.MachineTemplate
import com.bnm.lab.instruments.MachineUnits
import com.bnm.lab.instruments.StoredInstrumentFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Machine-only mode, pinned to a REAL printout.
 *
 * Every expected value below is read off an actual BC-5130 report from Sri
 * Gokul X-Ray's (the sheet the lab handed us), and the inputs are the SI
 * values the analyzer puts on the wire for it. That makes this a test of the
 * thing that actually matters: a lab keys nothing, so if the conversion is
 * wrong nobody catches it — 8.1 printed under a 4500–11000 range reads as a
 * catastrophic leukopenia rather than a formatting slip.
 */
class MachineReportTest {

    /** The wire values for the sample sheet: SI, exactly as Mindray sends them. */
    private fun sampleFrame(): StoredInstrumentFrame = StoredInstrumentFrame(
        driver = "mindray_hl7",
        specimenId = "636/08",
        patientId = "CP-133743",
        patientName = "MRS.MURUGAMANI.",
        patientSex = "F",
        params = mapOf(
            "WBC" to "8.1",
            "NEU%" to "68", "EOS%" to "2", "BAS%" to "0", "LYM%" to "25", "MON%" to "5",
            "NEU#" to "5.508", "EOS#" to "0.162", "BAS#" to "0", "LYM#" to "2.025", "MON#" to "0.405",
            "RBC" to "5.05", "HGB" to "111", "HCT" to "35.8", "MCV" to "70.8",
            "MCH" to "22.0", "MCHC" to "310", "RDW-CV" to "20.1", "RDW-SD" to "55.4",
            "PLT" to "381", "MPV" to "8.3", "PDW" to "15.3", "PCT" to "0.315",
            "PLCC" to "60", "PLCR" to "15.9",
        ),
        units = mapOf(
            "WBC" to "10*9/L",
            "NEU%" to "%", "EOS%" to "%", "BAS%" to "%", "LYM%" to "%", "MON%" to "%",
            "NEU#" to "10*9/L", "EOS#" to "10*9/L", "BAS#" to "10*9/L",
            "LYM#" to "10*9/L", "MON#" to "10*9/L",
            "RBC" to "10*12/L", "HGB" to "g/L", "HCT" to "%", "MCV" to "fL",
            "MCH" to "pg", "MCHC" to "g/L", "RDW-CV" to "%", "RDW-SD" to "fL",
            "PLT" to "10*9/L", "MPV" to "fL", "PDW" to "", "PCT" to "%",
            "PLCC" to "10*9/L", "PLCR" to "%",
        ),
    )

    private fun printed(frame: StoredInstrumentFrame): Map<String, Pair<String, String>> {
        val built = assertNotNull(MachineReport.build(frame), "template must exist for mindray_hl7")
        return built.sections.single().rows
            .filter { !it.heading }
            .associate { it.param to (it.value to it.unit) }
    }

    @Test
    fun `every value on the real printout comes back exactly`() {
        val rows = printed(sampleFrame())
        val expected = mapOf(
            "TOTAL WBC COUNT" to ("8100" to "cells/cumm"),
            "NEUTROPHILS" to ("68" to "%"),
            "EOSINOPHILS" to ("2" to "%"),
            "BASOPHILS" to ("0" to "%"),
            "LYMPHOCYTES" to ("25" to "%"),
            "MONOCYTES" to ("5" to "%"),
            "ABSOLUTE NEUTROPHILS" to ("5508" to "cells/cumm"),
            "ABSOLUTE EOSINOPHILS" to ("162" to "cells/cumm"),
            "ABSOLUTE BASOPHILS" to ("0" to "cells/cumm"),
            "ABSOLUTE LYMPHOCYTES" to ("2025" to "cells/cumm"),
            "ABSOLUTE MONOCYTES" to ("405" to "cells/cumm"),
            "TOTAL RBC COUNT" to ("5.05" to "million cells/cu mm"),
            "HAEMOGLOBIN" to ("11.1" to "g/dl"),
            "PACKED CELL VOLUME (PCV)" to ("35.8" to "%"),
            "MEAN CELL VALUE (MCV)" to ("70.8" to "fl"),
            "MEAN CELL HAEMOGLOBIN (MCH)" to ("22.0" to "pg/cell"),
            "MCH CONCENTRATION (MCHC)" to ("31.0" to "g/dl"),
            "RED CELL DIS. WIDTH (RDW-CV)" to ("20.1" to "%"),
            "RED CELL DIS. WIDTH (RDW-SD)" to ("55.4" to "fl"),
            "PLATELETS" to ("3.81" to "Lakhs/cumm"),
            "MEAN PLATELET VOLUME (MPV)" to ("8.3" to "fl"),
            "PLATELET DISTRIBUTION WIDTH (PDW)" to ("15.3" to ""),
            "PLATELETCRIT (PCT)" to ("3.15" to "mL/L"),
            "PLATELET LARGER CELL COUNT (P-LCC)" to ("60000" to "cells/cumm"),
            "PLATELET LARGER CELL RATIO (P-LCR)" to ("15.9" to "%"),
        )
        for ((label, want) in expected) {
            assertEquals(want, rows[label], "row '$label'")
        }
        assertEquals(expected.size, rows.size, "no extra or missing rows")
    }

    @Test
    fun `exactly the six values the lab printed in red are flagged`() {
        val built = assertNotNull(MachineReport.build(sampleFrame()))
        val flagged = built.sections.single().rows
            .filter { it.flag != null }
            .associate { it.param to it.flag }
        // Read straight off the sheet: 11.1, 70.8, 22.0, 31.0, 20.1, 3.15.
        assertEquals(
            mapOf(
                "HAEMOGLOBIN" to "L",
                "MEAN CELL VALUE (MCV)" to "L",
                "MEAN CELL HAEMOGLOBIN (MCH)" to "L",
                "MCH CONCENTRATION (MCHC)" to "L",
                "RED CELL DIS. WIDTH (RDW-CV)" to "H",
                "PLATELETCRIT (PCT)" to "H",
            ),
            flagged,
        )
    }

    @Test
    fun `the printed normal ranges match the sheet`() {
        val built = assertNotNull(MachineReport.build(sampleFrame()))
        val refs = built.sections.single().rows.associate { it.param to it.ref }
        assertEquals("4500 - 11000", refs["TOTAL WBC COUNT"])
        assertEquals("35 - 80", refs["NEUTROPHILS"])
        assertEquals("1575 - 8800", refs["ABSOLUTE NEUTROPHILS"])
        assertEquals("3.8 - 5.1", refs["TOTAL RBC COUNT"])
        assertEquals("11.7 - 15.5", refs["HAEMOGLOBIN"])
        assertEquals("1.5 - 4", refs["PLATELETS"])
        assertEquals("1.08 - 2.82", refs["PLATELETCRIT (PCT)"])
        assertEquals("30000 - 90000", refs["PLATELET LARGER CELL COUNT (P-LCC)"])
    }

    @Test
    fun `sections appear in sheet order and only when a row follows`() {
        val built = assertNotNull(MachineReport.build(sampleFrame()))
        val headings = built.sections.single().rows
            .filter { it.heading }
            .map { it.param }
        assertEquals(
            listOf("DIFFERENTIAL COUNT", "ABSOLUTE DIFFERENTIAL COUNT", "RED BLOOD CELLS", "PLATELETS"),
            headings,
        )
    }

    @Test
    fun `a heading is not printed when the analyzer sent nothing under it`() {
        // A 3-part analyzer message: no platelet indices at all.
        val frame = sampleFrame().let { f ->
            f.copy(
                params = f.params.filterKeys { it !in setOf("PLT", "MPV", "PDW", "PCT", "PLCC", "PLCR") },
            )
        }
        val built = assertNotNull(MachineReport.build(frame))
        val rows = built.sections.single().rows
        assertTrue(rows.none { it.heading && it.param == "PLATELETS" },
            "an empty PLATELETS heading reads as lost data")
        assertTrue(built.missing.contains("PLATELETS"))
    }

    @Test
    fun `an unknown unit prints the analyzer's own number and is reported, never converted`() {
        val frame = sampleFrame().let { f ->
            f.copy(units = f.units + ("WBC" to "squiggles/furlong"))
        }
        val built = assertNotNull(MachineReport.build(frame))
        val wbc = built.sections.single().rows.first { it.param == "TOTAL WBC COUNT" }
        assertEquals("8.1", wbc.value, "the analyzer's own number, unmultiplied")
        assertEquals("squiggles/furlong", wbc.unit)
        assertNull(wbc.flag, "an unconverted value must not be compared to the range")
        assertEquals(1, built.unconverted.size)
        assertEquals("TOTAL WBC COUNT", built.unconverted.single().param)
    }

    @Test
    fun `a unitless analyzer is taken at face value - the documented Mispa behaviour`() {
        val frame = sampleFrame().copy(units = emptyMap())
        val built = assertNotNull(MachineReport.build(frame))
        val wbc = built.sections.single().rows.first { it.param == "TOTAL WBC COUNT" }
        assertEquals("8", wbc.value, "no unit on the wire = already in the printed unit")
        assertTrue(built.unconverted.isEmpty())
    }

    @Test
    fun `graphs come through in sheet order, curves then the scattergram`() {
        val frame = sampleFrame().copy(
            histograms = mapOf(
                "wbc" to List(128) { it.toDouble() },
                "rbc" to List(128) { (128 - it).toDouble() },
                "plt" to List(128) { 1.0 },
            ),
            // "QkE=" is valid base64; the renderer only needs bytes.
            images = mapOf("diff" to "QkE="),
        )
        val graphs = MachineReport.graphs(frame)
        assertEquals(listOf("wbc", "rbc", "plt", "diff"), graphs.map { it.kind })
        assertTrue(graphs[0].hasCurve)
        assertEquals("fL", graphs[1].xLabel)
        assertTrue(graphs[3].hasImage, "the DIFF scattergram is the analyzer's own bitmap")
    }

    @Test
    fun `a flat or absent histogram is left off rather than drawn empty`() {
        val frame = sampleFrame().copy(histograms = mapOf("wbc" to List(128) { 0.0 }))
        assertTrue(MachineReport.graphs(frame).isEmpty())
    }

    @Test
    fun `an unknown driver has no machine-only sheet`() {
        assertNull(MachineReport.build(sampleFrame().copy(driver = "mispa_count_x")))
        assertNull(MachineTemplate.forDriver("nothing_like_this"))
    }

    @Test
    fun `the unit table refuses pairs it does not know instead of guessing`() {
        assertEquals(1000.0, MachineUnits.factor("10*9/L", "cells/cumm"))
        assertEquals(1000.0, MachineUnits.factor("10^9/L", "cells/cumm"), "^ and * are the same unit")
        assertEquals(0.1, MachineUnits.factor("g/L", "g/dl"))
        assertNull(MachineUnits.factor("g/L", "cells/cumm"), "a nonsense pair must not resolve")
        assertNull(MachineUnits.convert(1.0, "parsecs", "cells/cumm"))
    }

    @Test
    fun `values are rounded, never truncated`() {
        assertEquals("8100", MachineReport.format(8099.6, 0))
        assertEquals("11.1", MachineReport.format(11.14, 1))
        assertEquals("11.2", MachineReport.format(11.15, 1))
        assertEquals("3.81", MachineReport.format(3.8149, 2))
        assertEquals("0.05", MachineReport.format(0.0500001, 2), "a leading zero survives")
    }
}

/** Background counts — the analyzer's blank, never a patient report. */
class MachineBackgroundRunTest {

    private fun frame(id: String?, name: String? = null) = StoredInstrumentFrame(
        driver = "mindray_hl7", specimenId = id, patientName = name,
        params = mapOf("WBC" to "0.08"), units = mapOf("WBC" to "10*9/L"),
    )

    @Test
    fun `the analyzer's blank is recognised by its id, whatever the case`() {
        assertTrue(MachineReport.isBackgroundRun(frame("Background")))
        assertTrue(MachineReport.isBackgroundRun(frame("BACKGROUND")))
        assertTrue(MachineReport.isBackgroundRun(frame(" background ")))
        assertTrue(MachineReport.isBackgroundRun(frame("Blank")))
        assertTrue(MachineReport.isBackgroundRun(frame(null, name = "Background")))
    }

    @Test
    fun `a real patient with near-zero counts is NOT treated as a blank`() {
        // The failure that matters: hiding a pancytopenic patient's report
        // because the numbers look like a blank would be far worse than
        // showing a blank. Detection is by id only, never by the values.
        assertFalse(MachineReport.isBackgroundRun(frame("12", name = "HASINI")))
        assertFalse(MachineReport.isBackgroundRun(frame("636/08")))
        assertFalse(MachineReport.isBackgroundRun(frame(null)))
        assertFalse(MachineReport.isBackgroundRun(frame("")))
    }
}

/** The graph panel is short for a reason the bench can act on. */
class MachineGraphNoteTest {

    private fun base() = StoredInstrumentFrame(
        driver = "mindray_hl7", specimenId = "12",
        params = mapOf("WBC" to "8.1"), units = mapOf("WBC" to "10*9/L"),
    )

    @Test
    fun `bitmap mode names the analyzer setting that can send all four`() {
        // Only WBC and DIFF have bitmap codes in the protocol, so this is the
        // most a Bitmap-mode analyzer can ever send.
        // A WBC BITMAP is the proof: it is the only histogram the protocol
        // can send as a picture.
        val frame = base().copy(images = mapOf("wbc" to "QkE=", "diff" to "QkE="))
        val built = assertNotNull(MachineReport.build(frame))
        val note = assertNotNull(built.graphNote, "a short panel must explain itself")
        assertTrue(note.contains("RBC and PLT"), note)
        assertTrue(note.contains("Data"), "it must name the setting to change: $note")
    }

    @Test
    fun `all four present says nothing at all`() {
        val frame = base().copy(
            histograms = mapOf(
                "wbc" to List(128) { 1.0 },
                "rbc" to List(128) { 1.0 },
                "plt" to List(128) { 1.0 },
            ),
            images = mapOf("diff" to "QkE="),
        )
        val built = assertNotNull(MachineReport.build(frame))
        assertNull(built.graphNote, "nothing is missing, so there is nothing to say")
    }

    @Test
    fun `a scattergram alone does not prove the histograms are bitmaps`() {
        // The DIFF scattergram is ALWAYS a bitmap. An analyzer correctly on
        // Data whose histogram we failed to read still has one — telling that
        // lab to change a setting it already changed is the worst outcome.
        val frame = base().copy(images = mapOf("diff" to "QkE="))
        val built = assertNotNull(MachineReport.build(frame))
        val note = assertNotNull(built.graphNote)
        assertFalse(note.contains("sending graphs as Bitmap"), note)
        assertTrue(note.contains("not sent by the analyzer"), note)
    }

    @Test
    fun `a silent analyzer is not blamed on the bitmap setting`() {
        // No pictures AND no curves: the analyzer sent no graphics at all
        // ("Not transmitted"), which is a different instruction to give.
        val built = assertNotNull(MachineReport.build(base()))
        val note = assertNotNull(built.graphNote)
        assertTrue(note.contains("not sent by the analyzer"), note)
        assertFalse(note.contains("Bitmap"), "do not name a setting that is not the cause: $note")
    }
}
