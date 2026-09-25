package com.bnm.analyzersim

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Mindray message's SHAPE — segments, fields, and above all the histogram
 * byte layout, which is the one thing a frame builder can get subtly wrong and
 * still look right on screen.
 */
class MindrayFramesTest {

    private fun spec(
        id: String? = "ACC-S1-00042",
        qc: Boolean = false,
        histograms: Boolean = true,
        image: Boolean = false,
        unknownCode: Boolean = false,
        badUnits: Boolean = false,
        seed: Long = 5L,
        profile: Profile = Profile.NORMAL,
    ) = SampleSpec(
        specimenId = id, patientId = "P-7", patientName = "Asha Menon", sex = "F", ageYears = 34,
        profile = profile, seed = seed, qc = qc, histograms = histograms, image = image,
        unknownCode = unknownCode, badUnits = badUnits, timestamp = "20260101090000",
    )

    private fun segments(spec: SampleSpec): List<String> =
        MindrayFrames.build(spec).split('\r').filter { it.isNotBlank() }

    private fun obx(spec: SampleSpec, code: String): List<String>? =
        segments(spec).firstOrNull { it.startsWith("OBX") && it.split('|').getOrNull(3)?.startsWith("$code^") == true }
            ?.split('|')

    // ── framing ──

    @Test
    fun `the wire bytes are MLLP-wrapped`() {
        val bytes = MindrayFrames.frame(spec())
        val text = bytes.decodeToString()
        assertEquals(SimMllp.SB, text.first(), "must open with <VT>")
        assertEquals(SimMllp.EB, text[text.length - 2], "must close with <FS>")
        assertEquals(SimMllp.CR, text.last(), "…followed by <CR>")
    }

    @Test
    fun `segments arrive in the order the driver walks them`() {
        val names = segments(spec()).map { it.take(3) }
        assertEquals(listOf("MSH", "PID", "PV1", "OBR"), names.take(4))
        assertTrue(names.drop(4).all { it == "OBX" }, "everything after OBR is an OBX")
    }

    @Test
    fun `MSH says ORU R01 in HL7 2 point 3 point 1, and Q only for a QC run`() {
        val msh = segments(spec())[0].split('|')
        assertEquals("ORU^R01", msh[8])
        assertEquals("P", msh[10])
        assertEquals("2.3.1", msh[11])
        assertEquals("Q", segments(spec(qc = true))[0].split('|')[10], "MSH-11 marks a QC material run")
    }

    @Test
    fun `the specimen id is OBR-3 and the patient is PID-3 and PID-5`() {
        val segs = segments(spec())
        assertEquals("ACC-S1-00042", segs.first { it.startsWith("OBR") }.split('|')[3])
        val pid = segs.first { it.startsWith("PID") }.split('|')
        assertEquals("P-7^^^^MR", pid[3])
        assertEquals("Menon^Asha", pid[5], "PID-5 is Last^First")
    }

    @Test
    fun `no specimen id leaves OBR-3 empty — the claim-queue case`() {
        assertEquals("", segments(spec(id = null)).first { it.startsWith("OBR") }.split('|')[3])
    }

    // ── results ──

    @Test
    fun `every published parameter reaches the message with its value, unit and range`() {
        val spec = spec()
        for (p in MindrayFrames.parameters(spec)) {
            val fields = assertNotNull(obx(spec, p.code), "no OBX for ${p.name} (${p.code})")
            assertEquals("NM", fields[2])
            assertEquals("${p.code}^${p.name}^${p.system}", fields[3])
            assertEquals(p.value, fields[5])
            assertEquals(p.unit, fields[6])
            assertEquals(p.range, fields[7])
        }
    }

    @Test
    fun `run settings and the age row are there for the driver's meta map`() {
        val spec = spec()
        assertEquals("Open Vial", obx(spec, "08001")!![5])
        assertEquals("Whole Blood", obx(spec, "08002")!![5])
        assertEquals("CBC+5DIFF", obx(spec, "08003")!![5])
        assertEquals("General", obx(spec, "01002")!![5])
        assertEquals("34", obx(spec, "30525-0")!![5])
    }

    @Test
    fun `alerts are IS rows whose value is T, which is the only value the driver believes`() {
        val spec = spec(profile = Profile.THROMBOCYTOPENIA)
        val alerts = MindrayFrames.alerts(spec)
        assertTrue(alerts.any { it.second == "Thrombocytopenia" }, "raised $alerts")
        for ((code, _) in alerts) {
            val fields = assertNotNull(obx(spec, code))
            assertEquals("IS", fields[2])
            assertEquals("T", fields[5])
        }
    }

    // ── histograms: the layout the app's decoder actually accepts ──

    @Test
    fun `each histogram is meta-length filler plus one byte per channel`() {
        val spec = spec()
        for ((code, metaCode, kind) in listOf(
            Triple("15000", "15004", "wbc"),
            Triple("15050", "15053", "rbc"),
            Triple("15100", "15113", "plt"),
        )) {
            val metaLength = obx(spec, metaCode)!![5].toInt()
            val payload = obx(spec, code)!![5].split('^')
            assertEquals(listOf("", "Application", "Octer-stream", "Base64"), payload.take(4),
                "$kind ED row header")
            val bytes = Base64.getDecoder().decode(payload[4])
            assertEquals(metaLength + Histograms.CHANNELS, bytes.size,
                "$kind: the app reads metaLength + N bytes as N single-byte channels")
            // The filler must be filler, or the first channel would be garbage.
            assertTrue((0 until metaLength).all { bytes[it].toInt() == 0 }, "$kind meta bytes are not zero")
        }
    }

    @Test
    fun `the discriminator channels are sent alongside each curve`() {
        val spec = spec()
        for (code in listOf("15010", "15011", "15012", "15013", "15051", "15052", "15111", "15112")) {
            val value = assertNotNull(obx(spec, code), "missing discriminator row $code")[5].toInt()
            assertTrue(value in 0 until Histograms.CHANNELS, "$code was $value")
        }
    }

    @Test
    fun `no-histograms drops every 15xxx row and says CBC instead of CBC+5DIFF`() {
        val spec = spec(histograms = false)
        assertEquals("CBC", obx(spec, "08003")!![5])
        val sideData = segments(spec).filter { seg ->
            seg.split('|').getOrNull(3)?.substringBefore('^')?.toIntOrNull()?.let { it in 15000..15299 } == true
        }
        assertTrue(sideData.isEmpty(), "still sent $sideData")
    }

    // ── the bitmap ──

    @Test
    fun `the scattergram only rides along when asked, and is a real BMP`() {
        assertNull(obx(spec(), "15200"), "the bitmap must be opt-in — it is 40 KB")
        val payload = assertNotNull(obx(spec(image = true), "15200"))[5].split('^')
        assertEquals(listOf("", "Image", "BMP", "Base64"), payload.take(4))
        val bmp = Base64.getDecoder().decode(payload[4])
        assertEquals('B'.code.toByte(), bmp[0])
        assertEquals('M'.code.toByte(), bmp[1])
        assertEquals(bmp.size, littleEndianInt(bmp, 2), "the BMP header's file size must match the file")
        assertEquals(54, littleEndianInt(bmp, 10), "pixel data starts after the two headers")
        assertTrue(bmp.size > 20_000, "a 100x100 24-bit BMP is ~30 KB, got ${bmp.size}")
    }

    // ── faults ──

    @Test
    fun `unknown-code adds one row under a code no driver map contains`() {
        val spec = spec(unknownCode = true)
        val fields = assertNotNull(obx(spec, MindrayFrames.UNKNOWN_CODE))
        assertEquals("NM", fields[2])
        assertEquals("1.23", fields[5])
        // Everything else must be untouched: a firmware surprise may not cost
        // the lab the rest of the sample.
        assertEquals(MindrayFrames.parameters(spec).size + 1,
            segments(spec).count { it.startsWith("OBX") && it.split('|')[2] == "NM" } -
                countSideDataAndAge(spec))
    }

    @Test
    fun `bad-units swaps one unit for something no converter can bridge`() {
        assertEquals("g/L", obx(spec(), "718-7")!![6])
        assertEquals("bogus/L", obx(spec(badUnits = true), "718-7")!![6])
        assertEquals("10*9/L", obx(spec(badUnits = true), "6690-2")!![6], "only HGB is spoiled")
    }

    @Test
    fun `the same seed emits the same message`() {
        assertEquals(MindrayFrames.build(spec(seed = 21L)), MindrayFrames.build(spec(seed = 21L)))
        assertTrue(MindrayFrames.build(spec(seed = 21L)) != MindrayFrames.build(spec(seed = 22L)))
    }

    private fun countSideDataAndAge(spec: SampleSpec): Int =
        segments(spec).count { seg ->
            val code = seg.split('|').getOrNull(3)?.substringBefore('^') ?: return@count false
            seg.split('|').getOrNull(2) == "NM" &&
                (code == "30525-0" || code.toIntOrNull()?.let { it in 15000..15299 } == true)
        }

    private fun littleEndianInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
