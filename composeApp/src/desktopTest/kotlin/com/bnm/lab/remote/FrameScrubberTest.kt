package com.bnm.lab.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What of a raw analyzer frame may reach a support engineer. The frames here
 * carry a name, a date of birth, an address, a phone and a remark on purpose:
 * every one of them must be gone, and every value, unit and sample id must
 * still be there — that is what a mapping problem needs.
 */
class FrameScrubberTest {

    @Test
    fun `HL7 - name, demographics, next of kin and free text go, numbers and ids stay`() {
        val raw = RemoteTestFixtures.oru("SPEC-77")
        val out = FrameScrubber.scrubRaw("mindray_hl7", raw)!!

        for (phi in listOf("Patient^Mindray", "19840101000000", "Female", "Gandhi", "9876543210", "123-45-6789", "fainted", "Kin^Next")) {
            assertFalse(phi in out, "'$phi' must not survive the scrub:\n$out")
        }
        assertFalse(out.lines().any { it.startsWith("NK1") }, "NK1 dropped entirely")
        // What debugging needs.
        for (keep in listOf("9.55|10*9/L", "4.51|10*12/L", "135|g/L", "SPEC-77", "E2E-001^^^^MR", "6690-2^WBC^LN", "OBX|15|ST|01001^Remark^99MRC||||||||F")) {
            assertTrue(keep in out, "'$keep' must stay:\n$out")
        }
        // PID keeps its field count, so PID-19 etc. are still at their positions.
        val pidIn = raw.lines().first { it.startsWith("PID") }
        val pidOut = out.lines().first { it.startsWith("PID") }
        assertEquals(pidIn.count { it == '|' }, pidOut.count { it == '|' })
        assertEquals("PID|1||E2E-001^^^^MR||||||||||||||||", pidOut)
        // Separators (CR between segments) unchanged.
        assertEquals(raw.count { it == '\r' }, out.count { it == '\r' })
    }

    @Test
    fun `Mispa - the PatientID field is blanked and every value stays`() {
        val raw = RemoteTestFixtures.mispa("SPEC123", patient = "PAT456") + "extra"
        val out = FrameScrubber.scrubRaw("mispa_count_x", raw)!!
        assertFalse("PAT456" in out, out)
        assertTrue(out.startsWith("$$$20260908\$12\$SPEC123\$\$9.5\$4.5\$250\$13.5"), out)
        assertTrue(out.endsWith("###extra"))
        assertEquals(raw.length - "PAT456".length, out.length)
        // A frame with histograms: only the head section is touched.
        val withHist = "$$$20260908\$1\$S1\$P1\$9.5#1\$2\$3#4\$5\$6#7\$8\$9###"
        assertEquals("$$$20260908\$1\$S1\$\$9.5#1\$2\$3#4\$5\$6#7\$8\$9###", FrameScrubber.scrubMispa(withHist))
    }

    @Test
    fun `an unknown driver gets no raw at all`() {
        assertNull(FrameScrubber.scrubRaw("astm_serial", "anything"))
        assertNull(FrameScrubber.scrubRaw("", "anything"))
    }

    @Test
    fun `id masking keeps the shape and the last four, and leaves numbers and units alone`() {
        assertEquals("A***0042", FrameScrubber.maskId("ACC-S1-00042"))
        assertEquals("E***-001", FrameScrubber.maskId("E2E-001"))
        assertEquals("**", FrameScrubber.maskId("42"))
        assertEquals("", FrameScrubber.maskId(""))
        val summary = "Result frame · specimen ACC-S1-00042 · 20 params · WBC 9.55 10*9/L · port 5500 · no order matches 'E2E-001'"
        val masked = FrameScrubber.maskIds(summary)
        assertEquals("Result frame · specimen A***0042 · 20 params · WBC 9.55 10*9/L · port 5500 · no order matches 'E***-001'", masked)
        // Words without digits are not ids.
        assertEquals("Listening on TCP port 5500", FrameScrubber.maskIds("Listening on TCP port 5500"))
        assertEquals("connection closed: IOException", FrameScrubber.maskIds("connection closed: IOException"))
    }
}
