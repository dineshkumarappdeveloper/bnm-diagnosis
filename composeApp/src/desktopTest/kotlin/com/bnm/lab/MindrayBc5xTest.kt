package com.bnm.lab

import com.bnm.lab.instruments.AnalyzerUnits
import com.bnm.lab.instruments.Hl7Message
import com.bnm.lab.instruments.MindrayBc5x
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The BC-5130 protocol sample, rebuilt with REAL binaries so the histogram
 * path is proven byte-for-byte: a 256-channel byte histogram behind a 1-byte
 * meta prefix (WBC), a 256-channel UInt16-LE histogram behind a 2-byte prefix
 * (RBC), and a token BMP scattergram.
 */
@OptIn(ExperimentalEncodingApi::class)
class MindrayBc5xTest {

    // 1 meta byte + channels 0..255 → 257 bytes; drop 1 → 256 byte-points.
    private val wbcB64: String = Base64.Default.encode(
        ByteArray(257) { i -> if (i == 0) 0x7F.toByte() else (i - 1).toByte() }
    )

    // 2 meta bytes + 256 × UInt16-LE (value = channel × 3) → 514 bytes.
    private val rbcB64: String = Base64.Default.encode(
        ByteArray(514) { i ->
            when {
                i < 2 -> 0x11.toByte()
                else -> {
                    val channel = (i - 2) / 2
                    val v = channel * 3
                    if ((i - 2) % 2 == 0) (v and 0xFF).toByte() else ((v shr 8) and 0xFF).toByte()
                }
            }
        }
    )

    private fun sample(messageType: String = "ORU^R01", processingId: String = "P"): String = listOf(
        "MSH|^~\\&|||||20101206164344||$messageType|1|$processingId|2.3.1||||||UNICODE",
        "PID|1||ChartNo^^^^MR||LastName^FirstName||20040506070809|Male",
        "PV1|1||Neike|Hema^^BN1|||||||||||||ChargeType",
        "OBR|1||TestSampleID|00001^Automated Count^99MRC||20001020304050|20010203040506|||Sender|||Cold|20020304050607||||||||20030405060708||HM||||Auditer||||Tester",
        "OBX|1|IS|08001^Take Mode^99MRC||O||||||F",
        "OBX|2|IS|08002^Blood Mode^99MRC||W||||||F",
        "OBX|3|IS|08003^Test Mode^99MRC||CBC||||||F",
        "OBX|4|IS|01002^Ref Group^99MRC||Common||||||F",
        "OBX|5|NM|30525-0^Age^LN||42|yr|||||F",
        "OBX|6|ST|01001^Remark^99MRC||Remark||||||F",
        "OBX|7|NM|6690-2^WBC^LN||9.55|10*9/L|4.00-10.00|N|||F",
        "OBX|8|NM|731-0^LYM#^LN||2.10|10*9/L|0.80-4.00|N|||F",
        "OBX|9|NM|736-9^LYM%^LN||22.0|%|20.0-40.0|N|||F",
        "OBX|10|NM|789-8^RBC^LN||4.51|10*12/L|3.50-5.50|N|||F",
        "OBX|11|NM|718-7^HGB^LN||135|g/L|110-150|N|||F",
        "OBX|12|NM|787-2^MCV^LN||88.0|fL|80.0-100.0|N|||F",
        "OBX|18|NM|777-3^PLT^LN||381|10*9/L|100-300|H|||F",
        "OBX|21|NM|10002^PCT^99MRC||3.10|mL/L|1.08-2.82|H|||F",
        "OBX|27|IS|12045^Multiple alerts^99MRC||T||||||F",
        "OBX|28|IS|12046^Lym left region alert^99MRC||F||||||F",
        "OBX|35|NM|15004^WBC Histogram. Meta Length^99MRC||1||||||F",
        "OBX|36|NM|15010^WBC Lym left line.^99MRC||1||||||F",
        "OBX|37|NM|15011^WBC Lym Mid line.^99MRC||2||||||F",
        "OBX|38|NM|15012^WBC Mid Gran line.^99MRC||3||||||F",
        "OBX|40|ED|15000^WBC Histogram. Binary^99MRC||^Application^Octer-stream^Base64^$wbcB64||||||F",
        "OBX|41|NM|15051^RBC Histogram. Left Line^99MRC||5||||||F",
        "OBX|42|NM|15052^RBC Histogram. Right Line^99MRC||6||||||F",
        "OBX|43|NM|15053^RBC Histogram. Binary Meta Length^99MRC||2||||||F",
        "OBX|44|ED|15050^RBC Histogram. Binary^99MRC||^Application^Octer-stream^Base64^$rbcB64||||||F",
        "OBX|49|ED|15200^WBC DIFF Scattergram. BMP^99MRC||^Image^BMP^Base64^Qk0=||||||F",
    ).joinToString("\r")

    private fun parseSample(): MindrayBc5x.Frame {
        val text = sample()
        val frame = MindrayBc5x.parse(Hl7Message.parse(text), text)
        assertNotNull(frame, "ORU^R01 must parse")
        return frame
    }

    @Test
    fun identity_fields_come_from_obr_pid_msh() {
        val f = parseSample()
        assertEquals("TestSampleID", f.specimenId)
        assertEquals("ChartNo", f.patientId)
        assertEquals("FirstName LastName", f.patientName)
        // PID-8, normalised to what the app stores. The protocol's own sample
        // spells it out, so both "Male" and "M" have to land on 'M'.
        assertEquals("M", f.patientSex)
        assertEquals("20010203040506", f.date)     // OBR-7 wins over MSH-7
        assertEquals("1", f.sequenceId)
        assertFalse(f.isQc)
        assertEquals(sample(), f.raw)
    }

    @Test
    fun numeric_params_keyed_by_analyzer_name_with_units_as_sent() {
        val f = parseSample()
        assertEquals("9.55", f.params["WBC"])
        assertEquals("22.0", f.params["LYM%"])
        assertEquals("135", f.params["HGB"])
        assertEquals("381", f.params["PLT"])
        assertEquals("3.10", f.params["PCT"])
        assertEquals("g/L", f.units["HGB"])
        assertEquals("10*9/L", f.units["WBC"])
        // Side data never leaks into params.
        assertNull(f.params["Age"])
        assertNull(f.params["WBC Histogram. Meta Length"])
        assertNull(f.params["WBC Lym left line."])
        assertTrue(f.params.keys.none { it.contains("Histogram") })
    }

    @Test
    fun histograms_decode_bytes_and_uint16_behind_meta_prefix() {
        val f = parseSample()
        val wbc = assertNotNull(f.histograms["wbc"])
        assertEquals(256, wbc.size)
        assertEquals(0.0, wbc.first())
        assertEquals(255.0, wbc.last())

        val rbc = assertNotNull(f.histograms["rbc"])
        assertEquals(256, rbc.size)
        assertEquals(0.0, rbc[0])
        assertEquals(3.0, rbc[1])
        assertEquals(765.0, rbc[255])

        assertNull(f.histograms["plt"])
    }

    @Test
    fun images_and_meta() {
        val f = parseSample()
        assertEquals("Qk0=", f.images["diff"])
        assertNull(f.images["wbc"])

        assertEquals("CBC", f.meta["test_mode"])
        assertEquals("W", f.meta["blood_mode"])
        assertEquals("O", f.meta["take_mode"])
        assertEquals("Common", f.meta["ref_group"])
        assertEquals("42 yr", f.meta["age"])
        assertTrue(assertNotNull(f.meta["alerts"]).contains("Multiple alerts"))
        assertFalse(f.meta["alerts"]!!.contains("Lym left region alert"))   // value F
        assertTrue(assertNotNull(f.meta["flags"]).contains("PLT:H"))
        assertTrue(f.meta["flags"]!!.contains("PCT:H"))
        assertFalse(f.meta["flags"]!!.contains("WBC"))                       // N is not a flag
        assertEquals("1,2,3", f.meta["wbc_lines"])
        assertEquals("5,6", f.meta["rbc_lines"])
        assertNull(f.meta["plt_lines"])
    }

    @Test
    fun qc_flag_follows_msh11() {
        val text = sample(processingId = "Q")
        val f = assertNotNull(MindrayBc5x.parse(Hl7Message.parse(text), text))
        assertTrue(f.isQc)
    }

    @Test
    fun non_oru_messages_are_rejected() {
        val ack = "MSH|^~\\&|||||20101206164344||ACK^R01|1|P|2.3.1\rMSA|AA|1"
        assertNull(MindrayBc5x.parse(Hl7Message.parse(ack), ack))
        val qry = sample(messageType = "QRY^Q02")
        assertNull(MindrayBc5x.parse(Hl7Message.parse(qry), qry))
    }

    @Test
    fun decodeHistogram_edge_cases() {
        assertTrue(MindrayBc5x.decodeHistogram("not base64!", 0).isEmpty())
        assertTrue(MindrayBc5x.decodeHistogram("", 0).isEmpty())
        // Meta prefix swallowing everything → nothing left.
        assertTrue(MindrayBc5x.decodeHistogram(Base64.Default.encode(byteArrayOf(1, 2)), 2).isEmpty())
        // Exactly 256 bytes, even → still byte-per-point (UInt16 needs > 256).
        val bytes = ByteArray(256) { it.toByte() }
        val pts = MindrayBc5x.decodeHistogram(Base64.Default.encode(bytes), 0)
        assertEquals(256, pts.size)
        assertEquals(200.0, pts[200])       // unsigned, not -56
        // 301 channels is no histogram anyone draws → nothing, never noise.
        assertTrue(MindrayBc5x.decodeHistogram(Base64.Default.encode(ByteArray(301) { 7 }), 0).isEmpty())
        // Trailing zeros are kept — the x axis is the channel index.
        val tail = MindrayBc5x.decodeHistogram(Base64.Default.encode(ByteArray(256).also { it[0] = 9 }), 0)
        assertEquals(256, tail.size)
        assertEquals(9.0, tail[0]); assertEquals(0.0, tail[255])
    }

    @Test
    fun unit_conversion_indian_report_conventions() {
        assertEquals("9550", AnalyzerUnits.convert("9.55", "10*9/L", "cells/cumm"))
        assertEquals("13.5", AnalyzerUnits.convert("135", "g/L", "g/dL"))
        assertEquals("3.81", AnalyzerUnits.convert("381", "10*9/L", "lakhs/cumm"))
        assertEquals("4.51", AnalyzerUnits.convert("4.51", "10*12/L", "million cells/cu mm"))
        assertEquals("4510000", AnalyzerUnits.convert("4.51", "10*12/L", "cells/cumm"))
        assertEquals("45", AnalyzerUnits.convert("0.45", "L/L", "%"))
        assertEquals("0.31", AnalyzerUnits.convert("3.10", "mL/L", "%"))
        assertEquals("2.10", AnalyzerUnits.convert("2.10", "10^9/L", "10^3/µL"))   // ×1 → text untouched
        // Reverse direction.
        assertEquals("9.55", AnalyzerUnits.convert("9550", "cells/cumm", "10*9/L"))
        assertEquals("135", AnalyzerUnits.convert("13.5", "g/dL", "g/L"))
    }

    @Test
    fun unit_conversion_passes_unknown_through() {
        assertEquals("5", AnalyzerUnits.convert("5", "mg/dL", "10*9/L"))
        assertEquals("5", AnalyzerUnits.convert("5", null, "g/dL"))
        assertEquals("5", AnalyzerUnits.convert("5", "g/L", null))
        assertEquals("5", AnalyzerUnits.convert("5", "g/L", "  "))
        assertEquals("<0.5", AnalyzerUnits.convert("<0.5", "g/L", "g/dL"))
        assertEquals("9.55", AnalyzerUnits.convert("9.55", "10*9/L", "10^9/L"))    // same unit, untouched
    }

    @Test
    fun unit_canonical_forms() {
        assertEquals("10^9/l", AnalyzerUnits.canonical("10*9/L"))
        assertEquals("10^9/l", AnalyzerUnits.canonical("10^9/L"))
        assertEquals("10^9/l", AnalyzerUnits.canonical("x10^9/L"))
        assertEquals("10^9/l", AnalyzerUnits.canonical("×10^9/L"))
        assertEquals("10^9/l", AnalyzerUnits.canonical("10E9/L"))
        assertEquals("10^3/ul", AnalyzerUnits.canonical("10^3/µL"))
        assertEquals("/cumm", AnalyzerUnits.canonical("cells/cu mm"))
        assertEquals("/cumm", AnalyzerUnits.canonical("cells/mm3"))
        assertEquals("/cumm", AnalyzerUnits.canonical("cells/mm^3"))
        assertEquals("million/cumm", AnalyzerUnits.canonical("Million cells/cumm"))
        assertEquals("g/dl", AnalyzerUnits.canonical("g/dL"))
    }
}
