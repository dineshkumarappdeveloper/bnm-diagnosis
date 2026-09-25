package com.bnm.analyzersim.ui

import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every hint is a claim about what BNM Lab does. An engineer decides "pass" or
 * "call the office" from this one line, so a hint that has quietly gone stale
 * is worse than no hint — it teaches the wrong thing, confidently.
 */
class HintsTest {

    private fun hint(form: SimForm) = labExpectation(form)

    @Test
    fun `the default form expects the claim queue, naming the id it will not match`() {
        // BNMTEST-0001 is this tool's own placeholder — no accession series
        // produces it, and the app tail-matches a bare number against a real one.
        assertEquals(
            "Expect: Queued for manual claim — no order matches 'BNMTEST-0001'.",
            hint(SimForm()),
        )
    }

    @Test
    fun `a CBC-only run says the report must show no differential, not zeros`() {
        val h = hint(SimForm().copy(sampleId = "ACC-S1-00042", cbcOnly = true))
        assertTrue(h.contains("no differential at all"), h)
        // A 3-part analyzer has no such run mode to imitate, so claiming one
        // would send an engineer looking for a report that cannot exist.
        val mispa = hint(SimForm().withAnalyzer(Analyzer.MISPA).copy(sampleId = "ACC-S1-00042", cbcOnly = true))
        assertTrue(!mispa.contains("differential"), mispa)
    }

    @Test
    fun `a real-looking accession expects the order, and admits the other outcome`() {
        val h = hint(SimForm().copy(sampleId = "ACC-S1-00042"))
        assertTrue(h.contains("the result on the order with accession 'ACC-S1-00042'"), h)
        assertTrue(h.contains("Queued for manual claim if BNM Lab has no order under it"), h)
    }

    @Test
    fun `each fault that stops a result existing owns the whole line`() {
        fun withFault(f: FaultForm) = hint(SimForm().copy(faults = f))
        assertTrue(withFault(FaultForm(hang = true)).contains("bytes 0, frames 0"))
        assertTrue(withFault(FaultForm(garbage = true)).contains("frames 0"))
        assertTrue(withFault(FaultForm(truncated = true)).contains("unframed bytes"))
        assertTrue(withFault(FaultForm(duplicate = true)).contains("ONE result"))
        assertTrue(withFault(FaultForm(burst = true, burstCount = "7")).contains("7 results"))
        assertTrue(withFault(FaultForm(noSpecimen = true)).contains("no specimen id keyed on the analyzer"))
        // A hang tells you nothing about a profile, so it must not mention one.
        val hangCritical = hint(SimForm().copy(profile = Profile.CRITICAL, faults = FaultForm(hang = true)))
        assertTrue(!hangCritical.contains("critical call-out"), hangCritical)
    }

    @Test
    fun `a critical result adds the call-out list, because that is the thing to check`() {
        val h = hint(SimForm().copy(sampleId = "ACC-S1-00042", profile = Profile.CRITICAL))
        assertTrue(h.contains("critical call-out list"), h)
    }

    @Test
    fun `QC reads differently on the two analyzers, because the formats differ`() {
        val mindray = hint(SimForm().copy(qc = true))
        assertTrue(mindray.contains("MSH-11 = Q"), mindray)
        assertTrue(mindray.contains("never be filed as a patient"), mindray)

        val mispa = hint(SimForm().withAnalyzer(Analyzer.MISPA).copy(qc = true))
        assertTrue(mispa.contains("carries no QC field"), mispa)
        assertTrue(!mispa.contains("MSH-11"), "the Mispa format has no MSH: $mispa")
    }

    @Test
    fun `an unknown code is expected to be kept on a Mindray and dropped on a Mispa`() {
        val mindray = hint(SimForm().copy(faults = FaultForm(unknownCode = true)))
        assertTrue(mindray.contains("kept under the analyzer's own label"), mindray)
        val mispa = hint(SimForm().withAnalyzer(Analyzer.MISPA).copy(faults = FaultForm(unknownCode = true)))
        assertTrue(mispa.contains("21st field should be dropped"), mispa)
    }

    @Test
    fun `bad units and the scattergram are Mindray-only claims`() {
        val mindray = hint(SimForm().copy(image = true, faults = FaultForm(badUnits = true)))
        assertTrue(mindray.contains("Unit not converted"), mindray)
        assertTrue(mindray.contains("40 KB"), mindray)

        // The Mispa format carries no unit field and no image, so promising a
        // warning row or a scattergram would send an engineer hunting nothing.
        val mispa = hint(SimForm().withAnalyzer(Analyzer.MISPA).copy(image = true,
            faults = FaultForm(badUnits = true)))
        assertTrue(!mispa.contains("Unit not converted"), mispa)
        assertTrue(!mispa.contains("40 KB"), mispa)
    }

    @Test
    fun `slow chunks add the reassembly claim to whatever else is expected`() {
        val h = hint(SimForm().copy(sampleId = "ACC-S1-9", faults = FaultForm(slowChunks = true)))
        assertTrue(h.startsWith("Expect: the result on the order"), h)
        assertTrue(h.contains("ONE result, not several"), h)
    }

    @Test
    fun `every combination produces one non-empty sentence that starts with Expect`() {
        // Cheap guard against a branch that returns "" or a dangling join.
        val forms = buildList {
            for (analyzer in Analyzer.entries) for (profile in Profile.entries) {
                val base = SimForm().withAnalyzer(analyzer).copy(profile = profile)
                add(base)
                add(base.copy(qc = true))
                add(base.copy(image = true))
                add(base.copy(sampleId = "ACC-S1-00042"))
                add(base.copy(sampleId = ""))
                for (f in listOf(
                    FaultForm(truncated = true), FaultForm(garbage = true), FaultForm(slowChunks = true),
                    FaultForm(unknownCode = true), FaultForm(badUnits = true), FaultForm(noSpecimen = true),
                    FaultForm(duplicate = true), FaultForm(burst = true), FaultForm(hang = true),
                )) add(base.copy(faults = f))
            }
        }
        for (form in forms) {
            val h = hint(form)
            assertTrue(h.startsWith("Expect: "), "not an expectation: '$h'")
            assertTrue(h.trim().endsWith("."), "unfinished sentence: '$h'")
            assertTrue(!h.contains("  "), "a dropped clause left a double space: '$h'")
        }
        assertEquals(forms.size, forms.size)
    }

    @Test
    fun `the driver reminder names the row the lab has to have created`() {
        assertTrue(driverReminder(SimForm()).contains("\"mindray_hl7\""))
        assertTrue(driverReminder(SimForm()).contains("port 5500"))
        val cable = SimForm().withAnalyzer(Analyzer.MISPA).withTransport(TransportKind.SERIAL)
        assertTrue(driverReminder(cable).contains("transport SERIAL"), driverReminder(cable))
        val bench = SimForm().withAnalyzer(Analyzer.MISPA)
        assertTrue(driverReminder(bench).contains("bench rehearsal, not the install"), driverReminder(bench))
    }
}
