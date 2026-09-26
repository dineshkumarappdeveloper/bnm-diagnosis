package com.bnm.lab

import com.bnm.lab.instruments.MachineReportDoc
import com.bnm.lab.instruments.StoredInstrumentFrame
import com.bnm.lab.report.writeLabReportPdf
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Machine-only mode → a real PDF on disk.
 *
 * Opt-in (`-Dbnm.machinePdf=<dir>`): it writes a sheet a human can hold beside
 * the lab's own printout. The assertions still run without the flag, against a
 * temporary path, so the renderer is exercised on every build — a report that
 * throws while drawing is the failure a machine-only lab cannot work around,
 * because there is no "edit the order and try again" in this mode.
 */
class MachineReportPdfTest {

    private fun frame(): StoredInstrumentFrame = StoredInstrumentFrame(
        driver = "mindray_hl7",
        specimenId = "636/08",
        patientId = "CP-133743",
        patientName = "MRS.MURUGAMANI.",
        patientSex = "F",
        date = "2026-09-26 10:42",
        meta = mapOf("age" to "42"),
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
            "MCH" to "pg", "MCHC" to "g/L", "RDW-CV" to "%", "RDW-SD" to "%",
            "PLT" to "10*9/L", "MPV" to "fL", "PDW" to "", "PCT" to "%",
            "PLCC" to "10*9/L", "PLCR" to "%",
        ),
        histograms = mapOf(
            // Shapes a haematologist would recognise, so the panel can be judged
            // by eye: WBC trimodal, RBC skewed around the MCV, PLT log-normal.
            "wbc" to List(128) { i ->
                val x = i.toDouble()
                600 * gauss(x, 22.0, 5.0) + 180 * gauss(x, 55.0, 8.0) + 320 * gauss(x, 95.0, 10.0)
            },
            "rbc" to List(128) { i -> 900 * gauss(i.toDouble(), 46.0, 9.0) },
            "plt" to List(128) { i -> 700 * gauss(i.toDouble(), 18.0, 6.0) + 300 * gauss(i.toDouble(), 96.0, 14.0) },
        ),
        // The DIFF scattergram, as the analyzer sends it: a 24-bit BMP, base64.
        images = mapOf("diff" to java.util.Base64.getEncoder().encodeToString(diffBmp())),
    )

    /**
     * A 24-bit BMP that looks like what a BC-5x actually sends: four GAUSSIAN
     * populations on the LAS (light absorption) / MAS (medium-angle scatter)
     * plane, a smear of debris up the left edge, drawn axes and the LAS/MAS/DIFF
     * legends — bottom-up rows, 4-byte aligned, exactly the analyzer's layout.
     *
     * It is deliberately realistic. The app draws whatever bitmap arrives and
     * generates nothing, so the ONLY thing this test can prove is that the
     * decode-and-place path is byte-faithful — and it can only prove that
     * convincingly if the input resembles a real scattergram. A square blob
     * tells you nothing about whether a real one would survive the trip.
     */
    private fun diffBmp(w: Int = 240, h: Int = 200): ByteArray {
        val rowBytes = (w * 3 + 3) / 4 * 4
        val px = ByteArray(rowBytes * h)
        java.util.Arrays.fill(px, 0xFF.toByte())
        var seed = 0x5DEECE66DL
        fun u(): Double { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 11).toULong().toDouble() / (1UL shl 53).toDouble()) }
        // Box-Muller: real clusters are gaussian, not uniform-in-a-box.
        fun n(): Double = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u().coerceAtLeast(1e-12))) * kotlin.math.cos(2 * kotlin.math.PI * u())
        fun dot(x: Int, y: Int, r: Int, g: Int, b: Int) {
            if (x !in 0 until w || y !in 0 until h) return
            val o = y * rowBytes + x * 3
            px[o] = b.toByte(); px[o + 1] = g.toByte(); px[o + 2] = r.toByte()
        }
        fun cluster(cx: Double, cy: Double, sx: Double, sy: Double, count: Int, r: Int, g: Int, b: Int) {
            repeat(count) { dot((cx + n() * sx).toInt(), (cy + n() * sy).toInt(), r, g, b) }
        }
        // Axes, drawn in the bitmap the way the analyzer draws them.
        for (x in 30 until w - 8) dot(x, 24, 0, 0, 0)
        for (y in 24 until h - 26) dot(30, y, 0, 0, 0)
        cluster(70.0, 78.0, 9.0, 11.0, 2200, 0, 176, 80)      // LYM   green
        cluster(88.0, 118.0, 7.0, 8.0, 900, 200, 0, 200)      // MON   magenta
        cluster(126.0, 128.0, 17.0, 16.0, 5200, 0, 190, 205)  // NEU   cyan
        cluster(176.0, 106.0, 21.0, 15.0, 700, 210, 0, 0)     // EOS   red
        cluster(44.0, 40.0, 5.0, 6.0, 400, 0, 0, 190)         // debris blue
        repeat(260) { dot((34 + u() * (w - 60)).toInt(), (28 + u() * (h - 60)).toInt(), 20, 20, 20) }
        val size = 54 + px.size
        val out = java.io.ByteArrayOutputStream()
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
        out.write('B'.code); out.write('M'.code)
        le32(size); le32(0); le32(54)
        le32(40); le32(w); le32(h); le16(1); le16(24); le32(0); le32(px.size)
        le32(2835); le32(2835); le32(0); le32(0)
        out.write(px)
        return out.toByteArray()
    }

    /** A small opaque PNG standing in for a lab's emblem/wordmark. */
    private fun logoPng(r: Int, g: Int, b: Int, glyph: String): ByteArray {
        val img = java.awt.image.BufferedImage(220, 120, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val gg = img.createGraphics()
        gg.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        gg.color = java.awt.Color(r, g, b)
        gg.fillRoundRect(4, 4, 212, 112, 28, 28)
        gg.color = java.awt.Color.WHITE
        gg.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 64)
        val fm = gg.fontMetrics
        gg.drawString(glyph, (220 - fm.stringWidth(glyph)) / 2, 60 + fm.ascent / 2 - 6)
        gg.dispose()
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun gauss(x: Double, mu: Double, sd: Double): Double {
        val z = (x - mu) / sd
        return exp(-0.5 * z * z)
    }

    @Test
    fun `a machine-only sheet renders to a PDF without an order or a patient record`() {
        val doc = assertNotNull(
            MachineReportDoc.build(
                frame = frame(),
                labName = "SRI GOKUL X-RAY'S",
                header = MachineReportDoc.Header(referrer = "Dr.J.Prabhakaran. M.B.B.S,P.G,H.Sc"),
                letterheadLines = listOf(
                    "Clinical Laboratory & E.C.G Centre",
                    "Ramalinga Complex, Kadayampatti Main Road, Ellampillai, Salem - 637 502",
                    "Ph : 0427 2493603 · srigokullabelp@gmail.com",
                ),
                logoLeftPng = logoPng(0x1B, 0x6E, 0xC4, "+"),
                logoRightPng = logoPng(0x2E, 0x9E, 0x54, "SG"),
                reported = "2026-09-26 10:44",
                generatedAt = "2026-09-26 10:44",
            ),
            "the mindray_hl7 template must produce a document",
        )

        // The header block is what a machine-only sheet lives or dies by: the
        // lab keys nothing, so anything missing here is missing on the paper.
        require(doc.patientName == "MRS.MURUGAMANI.") { "name came from the analyzer" }
        require(doc.ageSex == "42 / F") { "age from the analyzer's OBX, sex from PID-8" }
        require(doc.accession == "636/08") { "the analyzer's specimen id stands in for an accession" }
        require(doc.verifiedBy == null && doc.approvedBy == null) {
            "nothing was verified or approved — this mode raises no order"
        }
        require(doc.qr == null) { "no QR: there is no published report to download" }

        val path = writeLabReportPdf(doc)
        assertTrue(path.isNotBlank(), "the renderer returned a path")
        println("MACHINE-ONLY SHEET: $path")
    }
}
