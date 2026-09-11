package com.bnm.lab

import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.print.buildSampleStickers
import com.bnm.lab.print.sampleTypeLabel
import com.bnm.lab.print.stickerStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What goes on the tubes for an order — one label per sample type. */
class SampleStickerBuildTest {

    private val order = LabOrder(id = "o1", accessionNo = "ACC-S1-00042", patientId = "p1",
        createdAt = "2026-09-07T03:42:00Z")
    private val patient = Patient(id = "p1", name = "Kavitha Subramanian", sex = "F", ageYears = 42)
    private fun test(id: String, sample: String) = LabTest(id = id, code = id.uppercase(), name = id, sampleType = sample)

    @Test
    fun `one sticker per distinct sample type, in first-appearance order, all with the same accession`() {
        val s = buildSampleStickers(order, patient,
            listOf(test("cbc", "blood"), test("lft", "serum"), test("hb", "blood"), test("urine", "urine")), "Sunrise Lab")
        assertEquals(listOf("BLOOD", "SERUM", "URINE"), s.map { it.sampleType })
        assertTrue(s.all { it.accession == "ACC-S1-00042" })
        assertTrue(s.all { it.patientName == "Kavitha Subramanian" && it.ageSex == "42 y / F" })
        assertTrue(s.all { it.registeredAt.matches(Regex("""\d{2}/\d{2}/\d{2} \d{2}:\d{2}""")) }, s.first().registeredAt)
    }

    @Test
    fun `a blank or unknown sample type still gets a tube label`() {
        val s = buildSampleStickers(order, patient, listOf(test("x", ""), test("y", "  ")), "Lab")
        assertEquals(listOf("SAMPLE"), s.map { it.sampleType })
        assertEquals(listOf("SAMPLE"), buildSampleStickers(order, patient, emptyList(), "Lab").map { it.sampleType })
        assertEquals("CSF", sampleTypeLabel("csf"))
    }

    @Test
    fun `the collection time goes on the tube once it is known`() {
        val collected = order.copy(createdAt = "2026-09-01T03:00:00Z", collectedAt = "2026-09-07T03:42:00Z")
        val stamp = buildSampleStickers(collected, patient, listOf(test("cbc", "blood")), "Lab").single().registeredAt
        assertTrue(stamp.startsWith("07/09/26"), "collection date must win over registration: $stamp")
        val uncollected = order.copy(createdAt = "2026-09-01T03:00:00Z", collectedAt = null)
        assertTrue(buildSampleStickers(uncollected, patient, listOf(test("cbc", "blood")), "Lab").single().registeredAt.startsWith("01/09/26"))
    }

    @Test
    fun `infant age prints in months`() {
        val baby = Patient(id = "p2", name = "Baby", sex = "M", ageYears = 0, dob = null)
        // Same rule as the report: under a year prints in months.
        assertEquals("0 mo / M", buildSampleStickers(order, baby, listOf(test("cbc", "blood")), "Lab").single().ageSex)
    }

    @Test
    fun `an unparseable registration stamp falls back to the raw date`() {
        assertEquals("2026-09-07", stickerStamp("2026-09-07"))
        assertTrue(stickerStamp("2026-09-07T03:42:00Z").matches(Regex("""\d{2}/\d{2}/26 \d{2}:\d{2}""")))
    }
}
