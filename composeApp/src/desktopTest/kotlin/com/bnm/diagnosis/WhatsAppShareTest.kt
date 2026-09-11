package com.bnm.diagnosis

import com.bnm.diagnosis.report.WaShareMode
import com.bnm.diagnosis.report.hasWaPhone
import com.bnm.diagnosis.report.waDeepLink
import com.bnm.diagnosis.report.waPhone
import com.bnm.diagnosis.report.waReportCaption
import com.bnm.diagnosis.report.waReportFilename
import com.bnm.diagnosis.report.waReportMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sending a report on WhatsApp: the number, the words, and the link. */
class WhatsAppShareTest {

    @Test
    fun `a number typed the way an Indian desk types it reaches WhatsApp`() {
        assertEquals("919876543210", waPhone("9876543210"))
        assertEquals("919876543210", waPhone("98765 43210"))
        assertEquals("919876543210", waPhone("098765 43210"), "the STD zero is dropped")
        assertEquals("919876543210", waPhone("+91 98765 43210"))
        assertEquals("919876543210", waPhone("0091-98765-43210"), "the 00 international prefix")
        assertEquals("919876543210", waPhone("919876543210"), "already complete")
        assertEquals("919876543210", waPhone("9876543210", defaultCc = ""), "a blank country code falls back to 91")
        assertEquals("449876543210", waPhone("9876543210", defaultCc = "44"), "a lab outside India")
        assertEquals("14155550123", waPhone("+1 415 555 0123"))
        assertNull(waPhone("12345"), "too short to dial")
        assertNull(waPhone(""))
        assertNull(waPhone(null))
        assertNull(waPhone("no digits here"))
        assertTrue(hasWaPhone(" 98765-43210 "))
        assertFalse(hasWaPhone("123"))
    }

    @Test
    fun `the message names the patient and the link, and never a result`() {
        val url = "https://x.supabase.co/functions/v1/admin-lab/reports/r/abc123"
        val m = waReportMessage("Kavitha Subramanian", "SRT Diagnostics", "ACC-S1-00042", url)
        assertTrue(m.startsWith("Dear Kavitha,"), m)
        assertTrue("SRT Diagnostics" in m)
        assertTrue("Accession: ACC-S1-00042" in m)
        assertTrue(url in m)
        assertTrue("do not forward" in m)
        // A doctor gets the same link, addressed differently.
        val d = waReportMessage("Kavitha Subramanian", "SRT Diagnostics", "ACC-S1-00042", url, toDoctor = true)
        assertTrue(d.startsWith("Lab report for Kavitha Subramanian is ready."), d)
        // Offline edition: no link, no promise of one.
        val offline = waReportMessage("Kavitha", "SRT Diagnostics", "ACC-S1-00042", null)
        assertTrue("Download" !in offline && "http" !in offline, offline)
        assertTrue("Accession: ACC-S1-00042" in offline)
        // The API caption is the same words without a link (the PDF is attached).
        assertTrue("http" !in waReportCaption("Kavitha", "SRT Diagnostics", "ACC-S1-00042"))
        assertEquals("ACC-S1-00042 report.pdf", waReportFilename("ACC-S1-00042"))
    }

    @Test
    fun `the deep link is a wa dot me url with the message percent-encoded`() {
        val link = waDeepLink("919876543210", "Dear Kavitha, your report is ready.\nAccession: ACC-1")
        assertTrue(link.startsWith("https://wa.me/919876543210?text="), link)
        assertTrue("%20" in link && "%0A" in link, "spaces and the newline are encoded")
        assertTrue(" " !in link && "\n" !in link)
        // The em dash and the rupee sign survive as UTF-8 percent triplets.
        val utf = waDeepLink("911234567890", "₹ — ok")
        assertTrue("%E2%82%B9" in utf && "%E2%80%94" in utf, utf)
        // Unreserved characters are left alone.
        assertTrue("ACC-1" in link, link)
    }

    @Test
    fun `the mode is read from its stored slug and defaults to off`() {
        assertEquals(WaShareMode.LINK, WaShareMode.fromSlug("link"))
        assertEquals(WaShareMode.API, WaShareMode.fromSlug("API"))
        assertEquals(WaShareMode.OFF, WaShareMode.fromSlug("off"))
        assertEquals(WaShareMode.OFF, WaShareMode.fromSlug(null), "unset = no WhatsApp button")
        assertEquals(WaShareMode.OFF, WaShareMode.fromSlug("nonsense"))
        assertTrue(WaShareMode.entries.all { it.label.isNotBlank() && it.blurb.isNotBlank() })
    }
}
