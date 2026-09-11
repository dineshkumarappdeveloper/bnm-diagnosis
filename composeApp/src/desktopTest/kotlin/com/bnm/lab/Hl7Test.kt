package com.bnm.lab

import com.bnm.lab.instruments.Hl7Ack
import com.bnm.lab.instruments.Hl7Delimiters
import com.bnm.lab.instruments.Hl7Escape
import com.bnm.lab.instruments.Hl7Message
import com.bnm.lab.instruments.Mllp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * HL7/MLLP proven against the Mindray BC-5130 sample from its protocol document.
 * The analyzer will not send the next sample until it gets a byte-exact ACK, so the
 * ACK is asserted as a literal string, not just "parses back".
 */
class Hl7Test {

    private val sampleSegments = listOf(
        "MSH|^~\\&|||||20101206164344||ORU^R01|1|P|2.3.1||||||UNICODE",
        "PID|1||ChartNo^^^^MR||LastName^FirstName||20040506070809|Male",
        "PV1|1||Neike|Hema^^BN1|||||||||||||ChargeType",
        "OBR|1||TestSampleID|00001^Automated Count^99MRC||20001020304050|20010203040506|||Sender|||Cold|20020304050607||||||||20030405060708||HM||||Auditer||||Tester",
        "OBX|1|IS|08001^Take Mode^99MRC||O||||||F",
        "OBX|2|IS|08002^Blood Mode^99MRC||W||||||F",
        "OBX|3|IS|08003^Test Mode^99MRC||CBC||||||F",
        "OBX|5|NM|30525-0^Age^LN||42|yr|||||F",
        "OBX|6|ST|01001^Remark^99MRC||Remark \\F\\ with \\S\\ escapes||||||F",
        "OBX|7|NM|6690-2^WBC^LN||9.55|10*9/L|4.00-10.00|N|||F",
        "OBX|8|NM|731-0^LYM#^LN||2.10|10*9/L|0.80-4.00|N|||F",
        "OBX|9|NM|736-9^LYM%^LN||22.0|%|20.0-40.0|N|||F",
        "OBX|10|NM|789-8^RBC^LN||4.51|10*12/L|3.50-5.50|N|||F",
        "OBX|11|NM|718-7^HGB^LN||135|g/L|110-150|N|||F",
        "OBX|18|NM|777-3^PLT^LN||381|10*9/L|100-300|H|||F",
        "OBX|27|IS|12045^Multiple alerts^99MRC||T||||||F",
        "OBX|35|NM|15004^WBC Histogram. Meta Length^99MRC||1||||||F",
        "OBX|36|NM|15010^WBC Lym left line.^99MRC||1||||||F",
        "OBX|40|ED|15000^WBC Histogram. Binary^99MRC||^Application^Octer-stream^Base64^AAAAAAAAAAAAAAAAAAAAAA==||||||F",
        "OBX|41|NM|15051^RBC Histogram. Left Line^99MRC||5||||||F",
        "OBX|42|NM|15052^RBC Histogram. Right Line^99MRC||6||||||F",
        "OBX|44|ED|15050^RBC Histogram. Binary^99MRC||^Application^Octer-stream^Base64^AAAAAAAAAAAAAAAAAAAAAA==||||||F",
    )
    private val sample = sampleSegments.joinToString("\r", postfix = "\r")

    private fun obxByRow(msg: Hl7Message, row: String) =
        msg.segments("OBX").first { it.field(1) == row }

    // ---- parsing ------------------------------------------------------------

    @Test
    fun mshFieldsFollowSpecNumbering() {
        val msg = Hl7Message.parse(sample)
        val msh = assertNotNull(msg.msh)
        assertEquals("|", msh.field(1))
        assertEquals("^~\\&", msh.field(2))
        assertEquals("ORU^R01", msh.field(9))
        assertEquals("1", msh.field(10))
        assertEquals("P", msh.field(11))
        assertEquals("2.3.1", msh.field(12))
        assertEquals("UNICODE", msh.field(18))
        assertEquals("", msh.field(99))
        assertEquals("ORU^R01", msg.messageType)
        assertEquals("1", msg.controlId)
        assertEquals("P", msg.processingId)
        assertEquals("", msg.sendingApplication)
        assertEquals("", msg.sendingFacility)
        assertEquals(Hl7Delimiters(), msg.delimiters)
    }

    @Test
    fun segmentsAndComponents() {
        val msg = Hl7Message.parse(sample)
        assertEquals(22, msg.segments.size)
        assertEquals(18, msg.segments("OBX").size)
        assertEquals("TestSampleID", assertNotNull(msg.segment("OBR")).field(3))
        assertEquals("ChartNo", assertNotNull(msg.segment("PID")).component(3, 1))
        assertEquals("MR", assertNotNull(msg.segment("PID")).component(3, 5))
        assertEquals("", assertNotNull(msg.segment("PID")).component(3, 9))
        assertNull(msg.segment("NTE"))
    }

    @Test
    fun numericResultsReadByRow() {
        val msg = Hl7Message.parse(sample)
        val wbc = obxByRow(msg, "7")
        assertEquals("WBC", wbc.component(3, 2))
        assertEquals("9.55", wbc.field(5))
        assertEquals("10*9/L", wbc.field(6))
        assertEquals("4.00-10.00", wbc.field(7))
        assertEquals("N", wbc.field(8))
        assertEquals("2.10", obxByRow(msg, "8").field(5))
        assertEquals("LYM#", obxByRow(msg, "8").component(3, 2))
        assertEquals("4.51", obxByRow(msg, "10").field(5))
        assertEquals("135", obxByRow(msg, "11").field(5))
        assertEquals("381", obxByRow(msg, "18").field(5))
        assertEquals("H", obxByRow(msg, "18").field(8))
    }

    @Test
    fun escapesUnescape() {
        val msg = Hl7Message.parse(sample)
        val remark = obxByRow(msg, "6")
        assertEquals("Remark \\F\\ with \\S\\ escapes", remark.field(5))   // field() is raw
        assertEquals("Remark | with ^ escapes", Hl7Escape.unescape(remark.field(5), msg.delimiters))
        assertEquals("Remark | with ^ escapes", remark.component(5, 1))
        val d = Hl7Delimiters()
        assertEquals("a~b&c\\d\re", Hl7Escape.unescape("a\\R\\b\\T\\c\\E\\d\\.br\\e", d))
        assertEquals("keep \\X41\\ me", Hl7Escape.unescape("keep \\X41\\ me", d))
        assertEquals("dangling \\F", Hl7Escape.unescape("dangling \\F", d))
        assertEquals("plain", Hl7Escape.unescape("plain", d))
    }

    @Test
    fun histogramBlobIsFifthComponent() {
        val msg = Hl7Message.parse(sample)
        val wbcHist = obxByRow(msg, "40")
        assertEquals("ED", wbcHist.field(2))
        assertEquals("Base64", wbcHist.component(5, 4))
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA==", wbcHist.component(5, 5))
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA==", obxByRow(msg, "44").component(5, 5))
    }

    @Test
    fun repetitionsSplit() {
        val msg = Hl7Message.parse("MSH|^~\\&|A|B\rPID|1||id1^^^X~id2^^^Y\r")
        val pid = assertNotNull(msg.segment("PID"))
        assertEquals(listOf("id1^^^X", "id2^^^Y"), pid.repetitions(3))
        assertEquals("id1", pid.component(3, 1))         // first repetition only
        assertEquals(emptyList(), pid.repetitions(7))
        assertEquals("A", msg.sendingApplication)
        assertEquals("B", msg.sendingFacility)
    }

    @Test
    fun lfAndCrlfEndingsParseTheSame() {
        val cr = Hl7Message.parse(sample)
        val lf = Hl7Message.parse(sampleSegments.joinToString("\n", postfix = "\n"))
        val crlf = Hl7Message.parse(sampleSegments.joinToString("\r\n", postfix = "\r\n"))
        for (other in listOf(lf, crlf)) {
            assertEquals(cr.segments.size, other.segments.size)
            assertEquals(cr.messageType, other.messageType)
            assertEquals(cr.segments("OBX").size, other.segments("OBX").size)
            assertEquals("381", obxByRow(other, "18").field(5))
            assertEquals("Remark | with ^ escapes", obxByRow(other, "6").component(5, 1))
        }
    }

    @Test
    fun customDelimitersComeFromMsh() {
        val msg = Hl7Message.parse("MSH#!*+@#SND#FAC###20260908120000##ORU!R01#42#P#2.3.1\rOBX#1#NM#6690-2!WBC!LN##9.55\r")
        assertEquals(Hl7Delimiters(field = '#', component = '!', repetition = '*', escape = '+', subcomponent = '@'), msg.delimiters)
        assertEquals("ORU!R01", msg.messageType)
        assertEquals("42", msg.controlId)
        assertEquals("SND", msg.sendingApplication)
        assertEquals("WBC", assertNotNull(msg.segment("OBX")).component(3, 2))
    }

    @Test
    fun parseNeverThrows() {
        for (garbage in listOf("", "hello", "MSH", "MSH|", "MSH|^", "|||", "\r\n\r\n", "OBX|1|NM|x||1", "MSH^")) {
            val msg = Hl7Message.parse(garbage)
            assertEquals("", msg.messageType)
            assertEquals("", msg.controlId)
            assertEquals("", msg.processingId)
            assertNotNull(msg.segments)
        }
        val noMsh = Hl7Message.parse("OBX|1|NM|6690-2^WBC^LN||9.55\r")
        assertNull(noMsh.msh)
        assertEquals(Hl7Delimiters(), noMsh.delimiters)
        assertEquals("9.55", assertNotNull(noMsh.segment("OBX")).field(5))
        assertEquals("MSH", assertNotNull(Hl7Message.parse("MSH").msh).name)
    }

    // ---- MLLP framing -------------------------------------------------------

    private val sb = Mllp.SB.toString()
    private val eb = Mllp.EB.toString()
    private val cr = Mllp.CR.toString()

    @Test
    fun extractDropsJunkBeforeSb() {
        val (msg, rest) = Mllp.extract("junk noise" + sb + "MSH|^~\\&|x\rMSA|AA|1\r" + eb + cr)
        assertEquals("MSH|^~\\&|x\rMSA|AA|1\r", msg)
        assertEquals("", rest)
    }

    @Test
    fun extractAcrossTwoChunks() {
        val whole = sb + "MSH|^~\\&|x\rOBX|1|NM|a||1\r" + eb + cr
        val chunk1 = whole.substring(0, 20)
        val chunk2 = whole.substring(20)

        val (first, kept) = Mllp.extract(chunk1)
        assertNull(first)
        assertEquals(chunk1, kept)                       // kept from its SB, nothing lost

        val (second, rest) = Mllp.extract(kept + chunk2)
        assertEquals("MSH|^~\\&|x\rOBX|1|NM|a||1\r", second)
        assertEquals("", rest)
    }

    @Test
    fun extractWaitsForCrAfterEb() {
        val body = sb + "MSH|x\r" + eb
        val (none, kept) = Mllp.extract(body)
        assertNull(none)
        assertEquals(body, kept)
        val (msg, rest) = Mllp.extract(kept + cr)
        assertEquals("MSH|x\r", msg)
        assertEquals("", rest)
    }

    @Test
    fun extractIncompleteKeepsFromLastSb() {
        assertEquals(null to "", Mllp.extract(""))
        assertEquals(null to "", Mllp.extract("no framing here"))
        assertEquals(null to sb + "second", Mllp.extract(sb + "first" + sb + "second"))
    }

    @Test
    fun extractDrainsBackToBackMessages() {
        val stream = sb + "MSH|1\r" + eb + cr + sb + "MSH|2\r" + eb + cr + sb + "MSH|3"
        val (m1, r1) = Mllp.extract(stream)
        assertEquals("MSH|1\r", m1)
        val (m2, r2) = Mllp.extract(r1)
        assertEquals("MSH|2\r", m2)
        val (m3, r3) = Mllp.extract(r2)
        assertNull(m3)
        assertEquals(sb + "MSH|3", r3)                   // trailing partial waits for more bytes
    }

    @Test
    fun wrapExtractRoundTrip() {
        val bytes = Mllp.wrap(sample)
        assertEquals(Mllp.SB.code, bytes.first().toInt())
        assertEquals(Mllp.CR.code, bytes.last().toInt())
        assertEquals(Mllp.EB.code, bytes[bytes.size - 2].toInt())
        val (msg, rest) = Mllp.extract(bytes.decodeToString())
        assertEquals(sample, msg)
        assertEquals("", rest)
        assertEquals(22, Hl7Message.parse(assertNotNull(msg)).segments.size)
    }

    // ---- ACK ----------------------------------------------------------------

    @Test
    fun ackIsByteExact() {
        val msg = Hl7Message.parse(sample)
        val bytes = Hl7Ack.forMessage(msg, "AA", ackControlId = "7", timestamp = "20260908120000")
        val (ack, rest) = Mllp.extract(bytes.decodeToString())
        assertEquals("MSH|^~\\&|BNM Lab||||20260908120000||ACK^R01|7|P|2.3.1||||||UNICODE\rMSA|AA|1\r", ack)
        assertEquals("", rest)
    }

    @Test
    fun ackErrorCodeAndSenderEcho() {
        val msg = Hl7Message.parse("MSH|^~\\&|BC-5130|LabA|||20101206164344||ORU^R01|555|Q|2.3.1\r")
        val bytes = Hl7Ack.forMessage(msg, "AE", ackControlId = "9", timestamp = "20260908120001")
        val (ack, _) = Mllp.extract(bytes.decodeToString())
        assertEquals("MSH|^~\\&|BNM Lab||BC-5130|LabA|20260908120001||ACK^R01|9|Q|2.3.1||||||UNICODE\rMSA|AE|555\r", ack)
        val parsed = Hl7Message.parse(assertNotNull(ack))
        assertEquals("ACK^R01", parsed.messageType)
        assertEquals("AE", assertNotNull(parsed.segment("MSA")).field(1))
        assertEquals("555", assertNotNull(parsed.segment("MSA")).field(2))
    }

    @Test
    fun ackEventFollowsReceivedType() {
        val qry = Hl7Message.parse("MSH|^~\\&|BC-5130||||20101206164344||QRY^Q02|12|P|2.3.1\r")
        val (ack, _) = Mllp.extract(Hl7Ack.forMessage(qry, ackControlId = "1", timestamp = "20260908120000").decodeToString())
        assertEquals("ACK^Q02", Hl7Message.parse(assertNotNull(ack)).messageType)

        val typeless = Hl7Message.parse("MSH|^~\\&|X||||20101206164344|||13|P|2.3.1\r")
        val (ack2, _) = Mllp.extract(Hl7Ack.forMessage(typeless, ackControlId = "2", timestamp = "20260908120000").decodeToString())
        assertEquals("ACK^R01", Hl7Message.parse(assertNotNull(ack2)).messageType)

        val garbage = Hl7Message.parse("hello")
        val (ack3, _) = Mllp.extract(Hl7Ack.forMessage(garbage, ackControlId = "3", timestamp = "20260908120000").decodeToString())
        assertEquals("MSH|^~\\&|BNM Lab||||20260908120000||ACK^R01|3||2.3.1||||||UNICODE\rMSA|AA|\r", ack3)
    }
}
