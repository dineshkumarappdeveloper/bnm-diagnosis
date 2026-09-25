package com.bnm.lab.report

import com.bnm.lab.staff.sha256Hex
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The report as DATA — what `admin-lab /reports/publish` stores and the
 * app.bnmapp.com report page draws when a patient scans the QR. No PDF is kept
 * on the server; the page renders one in the browser.
 *
 * Built from the same [ReportDoc] both PDF renderers draw, so the web copy
 * carries exactly what the paper does: the frozen rows, the stored flags, the
 * signatories' filtered names, the lab's letterhead and page split.
 *
 * WHAT IS NEVER IN IT (the snapshot is served to anyone holding the token):
 *  - an internal id — order, patient, staff, business, licence;
 *  - the QR block — the page does not need its own link, and the token is the
 *    capability;
 *  - anything [ReportDoc] does not print (a staff row's pinHash, above all).
 *
 * Schema v1 is a contract shared with admin-lab and BNMClient's `r/report.js`:
 * a key added here must be added there too, or it is dropped at the server.
 */
object ReportSnapshot {
    /** Schema version, stored beside the snapshot as `report_v`. */
    const val VERSION = 1

    /**
     * The ceiling this app keeps a snapshot under. The server refuses 512 KB;
     * half of that leaves room for a report with many tests. Past it, images go
     * first — analyzer bitmaps, then signatures — because the rows are the
     * report and a picture is not.
     */
    const val MAX_BYTES = 256 * 1024

    /**
     * Fingerprint of the snapshot's CONTENT: `generatedAt` is left out, so the
     * same report rebuilt a minute later hashes the same and is not re-sent.
     */
    fun contentSha256(snapshot: JsonObject): String =
        sha256Hex(JsonObject(snapshot - "generatedAt").toString())

    /** Encoded size in bytes — what the server measures. */
    fun byteSize(snapshot: JsonObject): Int = snapshot.toString().encodeToByteArray().size
}

/**
 * [doc] → snapshot v1. Pure: same doc, same [generatedAt], same JSON.
 *
 * [generatedAt] is an ISO-8601 instant (the uploader passes the drain time);
 * it defaults to the doc's own stamp so a test can build one without a clock.
 * When the result is larger than [maxBytes], graph images are dropped, then
 * signature images — see [ReportSnapshot.MAX_BYTES].
 */
fun buildReportSnapshot(
    doc: ReportDoc,
    generatedAt: String = doc.generatedAt,
    maxBytes: Int = ReportSnapshot.MAX_BYTES,
): JsonObject {
    val full = snapshotOf(doc, generatedAt, graphImages = true, signatureImages = true)
    if (ReportSnapshot.byteSize(full) <= maxBytes) return full
    val noGraphImages = snapshotOf(doc, generatedAt, graphImages = false, signatureImages = true)
    if (ReportSnapshot.byteSize(noGraphImages) <= maxBytes) return noGraphImages
    // Still too big: the signatures go too. Past this point the text itself is
    // over the line, which the server's own cap then judges.
    return snapshotOf(doc, generatedAt, graphImages = false, signatureImages = false)
}

private fun snapshotOf(
    doc: ReportDoc,
    generatedAt: String,
    graphImages: Boolean,
    signatureImages: Boolean,
): JsonObject = buildJsonObject {
    put("v", ReportSnapshot.VERSION)
    put("generatedAt", generatedAt)
    putJsonObject("letterhead") {
        put("mode", if (doc.mode == LetterheadMode.PREPRINTED) "preprinted" else "printed")
        put("accent", accentHex(doc.accentRgb))
        // admin-lab overwrites this with the licence's lab name (read-only rule).
        put("labName", doc.labName)
        putJsonArray("lines") { doc.letterheadLines.forEach { add(it) } }
    }
    put("pagination", doc.pagination.slug)
    putJsonObject("patient") {
        put("name", doc.patientName)
        put("ageSex", doc.ageSex)
        put("phone", doc.phone)
        put("referrer", doc.referrer)
        put("accession", doc.accession)
        put("registered", doc.registered)
        put("reported", doc.reported)
        put("priority", doc.priority)
    }
    putJsonArray("toFollow") { doc.toFollow.forEach { add(it) } }
    putJsonArray("sections") {
        for (section in doc.sections) addJsonObject {
            put("title", section.title)
            put("department", section.department)
            put("sampleType", section.sampleType)
            putJsonArray("rows") {
                for (row in section.rows) addJsonObject {
                    put("param", row.param)
                    put("value", row.value)
                    put("unit", row.unit)
                    put("ref", row.ref)
                    // The STORED flag, untouched: the page derives the label,
                    // colour and legend from it exactly as flagLabel/flagLegend do.
                    put("flag", row.flag?.trim()?.takeIf { it.isNotEmpty() })
                }
            }
            putJsonArray("graphs") {
                for (g in section.graphs) {
                    // Same rule as the renderers: the curve when there is one,
                    // else the image — so a curve never carries its bitmap. A
                    // graph left with neither (its bitmap dropped) would only be
                    // an empty box on the page, so it is left out.
                    val image = if (graphImages && !g.hasCurve) webImageBase64(g.image) else null
                    if (!g.hasCurve && image == null) continue
                    addJsonObject {
                        put("kind", g.kind)
                        put("title", g.title)
                        put("xLabel", g.xLabel)
                        put("points", compactSeries(g.points))
                        put("lines", compactSeries(g.lines))
                        // The v1 key says PNG; the bytes are the analyzer's own
                        // (Mindray's DIFF scattergram is a BMP) and the page
                        // reads the format from them.
                        put("imagePng", image)
                    }
                }
            }
        }
    }
    putJsonObject("signoff") {
        // Already filtered by buildReportDoc; filtered again because a doc can
        // be built by hand, and "Lab Owner" must never reach a patient's screen.
        put("verifiedBy", Signatory.printable(doc.verifiedBy))
        put("approvedBy", Signatory.printable(doc.approvedBy))
        put("approvedOn", doc.approvedOn)
        put("verifier", signerOf(doc.verifierSignature, signatureImages))
        put("approver", signerOf(doc.signature, signatureImages))
    }
}

/** A signatory's credentials + ink, or JSON null when there is nothing to show. */
private fun signerOf(sig: ReportSignature?, withImage: Boolean): JsonElement {
    if (sig == null) return JsonNull
    val ink = if (withImage) webImageBase64(sig.imagePng) else null
    val quals = sig.qualifications?.trim()?.takeIf { it.isNotEmpty() }
    val reg = sig.registrationNo?.trim()?.takeIf { it.isNotEmpty() }
    if (ink == null && quals == null && reg == null) return JsonNull
    return buildJsonObject {
        put("qualifications", quals)
        put("registrationNo", reg)
        put("signaturePng", ink)
    }
}

/** 0xRRGGBB → "#RRGGBB". */
private fun accentHex(rgb: Int): String =
    "#" + (rgb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

/**
 * The image formats the page tells apart by their first bytes (`r/report.js`
 * imageDataUrl: `iVBOR`, `/9j/`, `Qk` in base64) and every browser draws. The
 * analyzers send PNG or BMP — Mindray's DIFF scattergram is a BMP — and the
 * signature pad writes PNG.
 */
private val WEB_IMAGE_MAGIC = listOf(
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), // PNG
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),              // JPEG
    byteArrayOf(0x42, 0x4D),                                                // BMP "BM"
)

/**
 * Base64 of [bytes] as sent, when they are an image the page can show, else
 * null. Anything else would arrive labelled as a PNG and draw as a broken
 * picture, so it stays on the paper only. Nothing is re-encoded here: a BMP is
 * large, and the size cap in [buildReportSnapshot] is what sheds it.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun webImageBase64(bytes: ByteArray?): String? {
    if (bytes == null) return null
    val known = WEB_IMAGE_MAGIC.any { magic ->
        bytes.size > magic.size && magic.indices.all { bytes[it] == magic[it] }
    }
    return if (known) Base64.Default.encode(bytes) else null
}

/**
 * A curve as short JSON numbers. Analyzer channels are counts, so most values
 * are whole and print without a ".0"; the rest keep five significant digits of
 * the series' largest value — far past what a 30 mm graph box can show — so a
 * 256-channel curve stays around a kilobyte. NaN/Infinity (not valid JSON)
 * become 0.
 */
private fun compactSeries(values: List<Double>): JsonArray {
    val finite = values.map { if (it.isFinite()) it else 0.0 }
    val max = finite.maxOfOrNull { abs(it) } ?: 0.0
    val digits = if (max > 0.0) (4 - floor(log10(max))).toInt().coerceIn(0, 12) else 0
    val scale = 10.0.pow(digits)
    return buildJsonArray {
        for (v in finite) {
            val r = round(v * scale) / scale
            if (r == floor(r) && abs(r) < 1e15) add(JsonPrimitive(r.toLong())) else add(JsonPrimitive(r))
        }
    }
}
