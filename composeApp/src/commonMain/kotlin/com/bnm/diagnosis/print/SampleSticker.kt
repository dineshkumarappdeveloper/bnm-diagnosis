package com.bnm.diagnosis.print

/**
 * Sample-tube sticker printing.
 *
 * A tube label is the ONE thing that follows a specimen from the draw chair to
 * the analyzer, so its content follows CLSI AUTO12: patient name (largest),
 * age/sex + sample type, a Code 128 of the accession with the accession in
 * clear text under it, then registration time and lab name. The accession is
 * what the analyzer/worklist scans — it is never truncated; everything else is.
 *
 * Three printer languages, one layout. Everything is computed in 203-dpi dots
 * (8 dots/mm — the resolution of practically every desktop label printer sold
 * in India) so the same row math drives TSPL, ZPL and ESC/POS. commonMain has
 * no font metrics, so glyph widths are per-language estimates and text lines
 * are truncated against them so nothing ever runs off the label.
 *
 * Label printers speak ASCII/Latin-1 only — [StickerRender.sanitize] is the
 * single choke point that makes every string printer-safe (and free of the
 * TSPL/ZPL delimiter characters).
 */

/** One tube label. Every string is display-ready; renderers only sanitise (ASCII) and truncate to fit. */
data class SampleSticker(
    val accession: String,      // barcode content AND the human-readable line under it, e.g. "ACC-S1-00042"
    val patientName: String,
    val ageSex: String,         // "42 y / F"
    val sampleType: String,     // "SERUM", "EDTA BLOOD", "URINE"
    val registeredAt: String,   // "07/09/26 09:12" (already formatted)
    val labName: String,        // may be long; truncate freely
)

/** Label stock in mm. */
data class StickerSpec(
    val widthMm: Int,
    val heightMm: Int,
    val gapMm: Int = 2,
    /** 203 (8 dots/mm — nearly every desk label printer) or 300 (12 dots/mm). */
    val dpi: Int = 203,
    /** Print the label the other way up — the roll is loaded the other way round. */
    val rotate: Boolean = false,
    /** Nudge the whole print, in mm: +x = right, +y = down. The knob for a
     *  printer whose sensor-to-head offset walks the last line onto the next
     *  label; 0.5 mm steps are what a desk actually needs. */
    val shiftXmm: Float = 0f,
    val shiftYmm: Float = 0f,
) {
    val dotsPerMm: Int get() = if (dpi >= 300) 12 else 8
    val shiftXDots: Int get() = (shiftXmm * dotsPerMm).toInt()
    val shiftYDots: Int get() = (shiftYmm * dotsPerMm).toInt()

    companion object {
        /** 50 x 25 x gap 2 — the stock nearly every Indian lab already has on the shelf. */
        val DEFAULT: StickerSpec = StickerSpec(50, 25, 2)

        /** Common Indian lab stocks: 50x25 (default), 50x30, 60x40, 75x50. Every
         *  preset is wide enough for a 13-char accession at 2-dot modules — see
         *  [StickerRender.minWidthMm]; 38/40 mm stock is not, and is deliberately
         *  not offered (it would print an unscannable code). */
        val PRESETS: List<StickerSpec> = listOf(
            DEFAULT,
            StickerSpec(50, 20),
            StickerSpec(50, 30),
            StickerSpec(60, 40),
            StickerSpec(75, 50),
        )
    }
}

enum class LabelLanguage(val slug: String, val title: String, val blurb: String) {
    TSPL("tspl", "TSPL", "TSC, Xprinter, Gainscha and most label printers"),
    ZPL("zpl", "ZPL", "Zebra label printers"),
    ESCPOS("escpos", "ESC/POS", "Receipt printers that support Code 128");

    companion object {
        /** Unknown/null → [TSPL]: it is what the cheap printers labs actually buy speak. */
        fun fromSlug(slug: String?): LabelLanguage =
            entries.firstOrNull { it.slug.equals(slug?.trim(), ignoreCase = true) } ?: TSPL
    }
}

/** Dots per mm at 203 dpi — the reference the layout constants were tuned at. */
private const val DPMM = 8

/** Left/right keep-out so print heads with a dead edge and slightly skewed stock still show every glyph. */
internal const val SIDE_MARGIN = 16

/**
 * One type size per line role. TSPL fonts are fixed bitmap cells (name → cell
 * w × h); ZPL font 0 is scalable and gets a height/width pair.
 */
private class Face(val tsplFont: String, val tsplW: Int, val tsplH: Int, val zplH: Int, val zplW: Int)

/** Vertical rows for one label, in dots from the top edge. */
private class Rows(val nameY: Int, val subY: Int, val barY: Int, val barH: Int, val footY: Int)

/** Type sizes AND the breathing room between rows — a 20 mm label gets the
 *  same faces as a 25 mm one but tighter pads, so everything still fits. */
private class Faces(
    val name: Face, val sub: Face, val foot: Face, val readableH: Int,
    val top: Int, val gap: Int, val gapBar: Int,
)

object StickerRender {
    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LF = 0x0A

    private const val TOP = 8          // top keep-out (regular tiers)
    /** Shortest stock the four rows (name, type, barcode + readable, time)
     *  fit on; the desk is told rather than handed a label missing its time. */
    const val MIN_HEIGHT_MM = 20
    /** Bottom keep-out in mm. The gap sensor sits a little ahead of the print
     *  head, so an uncalibrated printer starts each label a touch late — a line
     *  drawn 1 mm from the bottom edge lands on the NEXT label. 2.5 mm absorbs
     *  that. (Field report, 2026-09-08: footer rows spilling onto the next sticker.) */
    private const val BOTTOM_MM = 2.5
    private const val GAP = 4          // between text rows
    private const val GAP_BAR = 6      // text → barcode
    private const val BAR_RATIO = 0.32 // barcode height as a share of label height
    private const val BAR_MIN_RATIO = 0.22

    private val FACE_S = Face("2", 12, 20, 20, 20)   // ~2.5 mm tall
    private val FACE_M = Face("3", 16, 24, 26, 26)   // ~3 mm
    private val FACE_L = Face("4", 24, 32, 34, 34)   // ~4 mm

    // ---- public API ------------------------------------------------------

    /** The complete print job: every sticker, [copies] of each, for a 203 dpi (8 dots/mm) printer. */
    fun render(language: LabelLanguage, stickers: List<SampleSticker>, spec: StickerSpec, copies: Int = 1): ByteArray =
        when (language) {
            LabelLanguage.TSPL -> tspl(stickers, spec, copies).encodeToByteArray()
            LabelLanguage.ZPL -> zpl(stickers, spec, copies).encodeToByteArray()
            LabelLanguage.ESCPOS -> escPos(stickers, spec, copies)
        }

    fun tspl(stickers: List<SampleSticker>, spec: StickerSpec, copies: Int = 1): String {
        val dpmm = spec.dotsPerMm
        val w = spec.widthMm * dpmm
        val h = spec.heightMm * dpmm
        val cw = w - 2 * SIDE_MARGIN
        val n = copies.coerceAtLeast(1)
        val f = faces(h)
        val r = rows(h, f, f.name.tsplH, f.sub.tsplH, f.foot.tsplH, bottom = (BOTTOM_MM * dpmm).toInt())
        val sx = spec.shiftXDots
        val sy = spec.shiftYDots
        val sb = StringBuilder()
        // ONE header per job. Re-issuing SIZE/GAP for every label makes TSC /
        // Xprinter firmware re-seek the gap mid-job — that is the skipped or
        // half-fed sticker labs report. REFERENCE / OFFSET / SHIFT are reset so a
        // value a driver utility stored earlier cannot walk the print off the label.
        sb.append("SIZE ${spec.widthMm} mm,${spec.heightMm} mm\r\n")
        sb.append("GAP ${spec.gapMm} mm,0 mm\r\n")
        sb.append("DIRECTION ${if (spec.rotate) 0 else 1}\r\n")
        sb.append("REFERENCE 0,0\r\n")
        sb.append("OFFSET 0 mm\r\n")
        sb.append("SHIFT 0\r\n")
        for (s in stickers) {
            val t = lines(s, cw / f.name.tsplW, cw / f.sub.tsplW, cw / f.foot.tsplW)
            val mod = BAR_MODULE_DOTS
            val bx = (barX(w, t.accession.length, mod) + sx).coerceAtLeast(0)
            sb.append("CLS\r\n")
            tsplText(sb, r.nameY + sy, f.name, t.name, sx)
            tsplText(sb, r.subY + sy, f.sub, t.sub, sx)
            // readable=1 prints the accession under the bars; wide == narrow (Code 128 has no wide element).
            sb.append("BARCODE $bx,${(r.barY + sy).coerceAtLeast(0)},\"128\",${r.barH},1,0,$mod,$mod,\"${t.accession}\"\r\n")
            tsplText(sb, r.footY + sy, f.foot, t.foot, sx)
            sb.append("PRINT 1,$n\r\n")
        }
        return sb.toString()
    }

    /**
     * The printer's own media calibration: it feeds a few labels and learns the
     * gap, which is the cure for a print that drifts a little further every
     * sticker. Null where the language has no such command (a receipt roll
     * has no gaps to find).
     */
    fun calibrate(language: LabelLanguage, spec: StickerSpec): ByteArray? = when (language) {
        LabelLanguage.TSPL -> (
            "SIZE ${spec.widthMm} mm,${spec.heightMm} mm\r\n" +
                "GAP ${spec.gapMm} mm,0 mm\r\n" +
                "GAPDETECT\r\n"
            ).encodeToByteArray()
        LabelLanguage.ZPL -> "~JC\n".encodeToByteArray()
        LabelLanguage.ESCPOS -> null
    }

    fun zpl(stickers: List<SampleSticker>, spec: StickerSpec, copies: Int = 1): String {
        val dpmm = spec.dotsPerMm
        val w = spec.widthMm * dpmm
        val h = spec.heightMm * dpmm
        val cw = w - 2 * SIDE_MARGIN
        val n = copies.coerceAtLeast(1)
        val f = faces(h)
        val r = rows(h, f, f.name.zplH, f.sub.zplH, f.foot.zplH, bottom = (BOTTOM_MM * dpmm).toInt())
        val sx = spec.shiftXDots
        val sy = spec.shiftYDots
        val sb = StringBuilder()
        // ZPL is one ^XA…^XZ format per label by design; ^MNY says "gap media,
        // web sensing" and ^PON/^POI set the orientation — both are restated so
        // a setting saved into the printer by another tool does not win.
        for (s in stickers) {
            val t = lines(s, cw / zplGlyphWidth(f.name.zplW), cw / zplGlyphWidth(f.sub.zplW), cw / zplGlyphWidth(f.foot.zplW))
            val mod = BAR_MODULE_DOTS
            val bx = (barX(w, t.accession.length, mod) + sx).coerceAtLeast(0)
            sb.append("^XA\n")
            sb.append("^PW$w\n^LL$h\n^LH0,0\n^MNY\n${if (spec.rotate) "^POI" else "^PON"}\n")
            zplText(sb, r.nameY + sy, f.name, t.name, sx)
            zplText(sb, r.subY + sy, f.sub, t.sub, sx)
            // ^BY module,ratio,height ; ^BC orientation,height,interpretation-below,above,check
            sb.append("^FO$bx,${(r.barY + sy).coerceAtLeast(0)}^BY$mod,2,${r.barH}^BCN,${r.barH},Y,N,N^FD${t.accession}^FS\n")
            zplText(sb, r.footY + sy, f.foot, t.foot, sx)
            sb.append("^PQ$n\n")
            sb.append("^XZ\n")
        }
        return sb.toString()
    }

    /**
     * A receipt printer pressed into label duty: centred text, bold double-height
     * name, Code 128 (Code B) with the HRI below, then feed + partial cut per copy.
     * Font A is 12 dots wide, so the column budget comes from the label width.
     */
    fun escPos(stickers: List<SampleSticker>, spec: StickerSpec, copies: Int = 1): ByteArray {
        val w = spec.widthMm * DPMM
        val h = spec.heightMm * DPMM
        val cw = w - 2 * SIDE_MARGIN
        val cols = (cw / 12).coerceAtLeast(8)
        val n = copies.coerceAtLeast(1)
        val f = faces(h)
        val barH = rows(h, f, f.name.tsplH, f.sub.tsplH, f.foot.tsplH, bottom = TOP).barH.coerceIn(1, 255)
        val out = ArrayList<Byte>(stickers.size * n * 160)
        fun b(vararg v: Int) { for (x in v) out.add(x.toByte()) }
        fun str(s: String) { for (ch in s) out.add(ch.code.toByte()) }

        for (s in stickers) {
            val t = lines(s, cols, cols, cols)
            // '{' is the function-code escape inside Code B data; a literal one is sent doubled.
            val data = ("{B" + t.accession.replace("{", "{{")).take(255)
            val mod = BAR_MODULE_DOTS
            repeat(n) {
                b(ESC, 0x40)                       // ESC @   initialise
                b(ESC, 0x61, 1)                    // ESC a 1 centre
                b(ESC, 0x45, 1); b(GS, 0x21, 0x10) // bold + double height
                str(t.name); b(LF)
                b(GS, 0x21, 0x00); b(ESC, 0x45, 0)
                str(t.sub); b(LF)
                b(GS, 0x68, barH)                  // GS h  barcode height
                b(GS, 0x77, mod)                   // GS w  module width
                b(GS, 0x48, 2)                     // GS H  HRI below
                b(GS, 0x66, 0)                     // GS f  HRI font A
                b(GS, 0x6B, 73, data.length)       // GS k 73 len  (Code 128, function B)
                str(data)
                str(t.foot); b(LF)
                b(LF, LF)
                b(GS, 0x56, 66, 0)                 // GS V 66 0  feed + partial cut
            }
        }
        return out.toByteArray()
    }

    /**
     * ASCII-only, printer-safe text: strip/replace anything outside 32..126.
     * Money and punctuation get readable stand-ins (₹ → Rs, · → -), accented
     * Latin drops to its base letter, anything else becomes '?'. Also removes
     * the TSPL ('"') and ZPL ('^', '~') delimiters so a name can never break a
     * command, and collapses whitespace so runs of NBSPs don't eat label width.
     */
    fun sanitize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val code = ch.code
            when {
                ch == '"' -> sb.append('\'')
                ch == '^' || ch == '~' -> sb.append('-')
                code in 32..126 -> sb.append(ch)
                ch == '\t' || ch == '\n' || ch == '\r' || code == 0xA0 -> sb.append(' ')
                code < 32 -> Unit
                else -> {
                    val multi = MULTI[ch]
                    if (multi != null) sb.append(multi)
                    else {
                        val i = ACCENTED.indexOf(ch)
                        sb.append(if (i >= 0) BASE[i] else '?')
                    }
                }
            }
        }
        return WS.replace(sb, " ").trim()
    }

    // ---- glyph estimates (internal so tests can check the width budget) ----

    /** TSPL bitmap font cell width in dots; unknown font → widest built-in so we truncate early, never late. */
    internal fun tsplGlyphWidth(font: String): Int = when (font) {
        "1" -> 8
        "2" -> 12
        "3" -> 16
        "4" -> 24
        "5" -> 32
        else -> 32
    }

    /** ZPL font 0 (CG Triumvirate Bold Condensed) averages ~0.55 × width; 0.6 leaves slack for wide names. */
    internal fun zplGlyphWidth(w: Int): Int = ((w * 3 + 4) / 5).coerceAtLeast(1)

    // ---- layout -----------------------------------------------------------

    /** Type tiers by label height: 25/30 mm stocks get the small set, 40 mm+ the large. */
    private fun faces(h: Int): Faces = when {
        h >= 280 -> Faces(FACE_L, FACE_M, FACE_S, readableH = 28, top = TOP, gap = GAP, gapBar = GAP_BAR)
        h >= 200 -> Faces(FACE_M, FACE_S, FACE_S, readableH = 24, top = TOP, gap = GAP, gapBar = GAP_BAR)
        // 20 mm stock (field, 2026-09-08): 160 dots — the regular pads alone
        // overrun it. Same faces, pads halved: 4+24+2+20+4+40+24+2+20+20 = 160.
        else -> Faces(FACE_M, FACE_S, FACE_S, readableH = 24, top = 4, gap = 2, gapBar = 4)
    }

    /**
     * Stack the rows top-down, barcode at [BAR_RATIO] of the label; spare height
     * is shared between the top pad, the pad above the barcode and the pad
     * above the footer so short and tall stocks both look balanced. On a stock
     * too short for the tiers the barcode shrinks first (floor [BAR_MIN_RATIO]).
     */
    private fun rows(h: Int, f: Faces, nameH: Int, subH: Int, footH: Int, bottom: Int): Rows {
        val fixed = f.top + nameH + f.gap + subH + f.gapBar + f.readableH + f.gap + footH + bottom
        var barH = (h * BAR_RATIO).toInt()
        var spare = h - fixed - barH
        if (spare < 0) {
            barH = (barH + spare).coerceAtLeast((h * BAR_MIN_RATIO).toInt())
            spare = 0
        }
        val pad = spare / 3
        val nameY = f.top + pad
        val subY = nameY + nameH + f.gap
        val barY = subY + subH + f.gapBar + pad
        val footY = barY + barH + f.readableH + f.gap + pad
        return Rows(nameY, subY, barY, barH, footY)
    }

    private class Lines(val name: String, val sub: String, val accession: String, val foot: String)

    /** Sanitise + truncate every line to its column budget. The accession is sanitised but never cut. */
    private fun lines(s: SampleSticker, nameCols: Int, subCols: Int, footCols: Int): Lines = Lines(
        name = fit(sanitize(s.patientName), nameCols),
        sub = pair(sanitize(s.ageSex), sanitize(s.sampleType), subCols),
        accession = sanitize(s.accession),
        foot = pair(sanitize(s.registeredAt), sanitize(s.labName), footCols),
    )

    private fun fit(s: String, maxChars: Int): String =
        if (s.length <= maxChars) s else s.take(maxChars.coerceAtLeast(0)).trimEnd()

    /** "left  right" — the left half is the fixed fact, the right half gives way. */
    private fun pair(left: String, right: String, maxChars: Int): String {
        val l = fit(left, maxChars)
        val room = maxChars - l.length - 2
        if (right.isEmpty() || room <= 0) return l
        return "$l  ${fit(right, room)}"
    }

    /** Code 128 width in modules: 11 per symbol + start(11) + check(11) + stop(13). Worst case — Code C packing only shrinks it. */
    private fun code128Modules(dataLen: Int): Int = 11 * dataLen + 35

    /**
     * NEVER below 2 dots (0.25 mm). A 1-dot module at 203 dpi is 0.125 mm — under
     * the X-dimension desk CCD/laser scanners are rated for — so an accession
     * that would not fit at 2 dots is refused in Settings ([minWidthMm]), not
     * shrunk into a code nobody can read.
     */
    const val BAR_MODULE_DOTS = 2

    /** Ten modules of white each side, per the Code 128 spec; the barcode line
     *  keeps THIS at the label edge, not the text margin. */
    const val QUIET_ZONE_DOTS = 10 * BAR_MODULE_DOTS

    /** Narrowest stock, in mm, that carries an accession of [accessionLength]
     *  characters at 2-dot modules with both quiet zones (12 chars → 47 mm at
     *  203 dpi; a 300 dpi head packs the same bars into 32 mm). */
    fun minWidthMm(accessionLength: Int, dpi: Int = 203): Int {
        val dpmm = if (dpi >= 300) 12 else 8
        return (BAR_MODULE_DOTS * code128Modules(accessionLength) + 2 * QUIET_ZONE_DOTS + dpmm - 1) / dpmm
    }

    private fun barX(w: Int, dataLen: Int, mod: Int): Int =
        ((w - mod * code128Modules(dataLen)) / 2).coerceAtLeast(QUIET_ZONE_DOTS)

    private fun tsplText(sb: StringBuilder, y: Int, face: Face, text: String, sx: Int = 0) {
        sb.append("TEXT ${(SIDE_MARGIN + sx).coerceAtLeast(0)},${y.coerceAtLeast(0)},\"${face.tsplFont}\",0,1,1,\"$text\"\r\n")
    }

    private fun zplText(sb: StringBuilder, y: Int, face: Face, text: String, sx: Int = 0) {
        sb.append("^FO${(SIDE_MARGIN + sx).coerceAtLeast(0)},${y.coerceAtLeast(0)}^A0N,${face.zplH},${face.zplW}^FD$text^FS\n")
    }

    // ---- sanitise tables ---------------------------------------------------

    private val WS = Regex("\\s{2,}")

    private val MULTI: Map<Char, String> = mapOf(
        '₹' to "Rs", '€' to "EUR", '£' to "GBP",
        '…' to "...", 'ß' to "ss",
        'Æ' to "AE", 'æ' to "ae", 'Œ' to "OE", 'œ' to "oe",
        '·' to "-", '•' to "-", '–' to "-", '—' to "-", '−' to "-",
        '‘' to "'", '’' to "'", '“' to "'", '”' to "'",
        '×' to "x", '°' to "o",
    )

    // Parallel strings: ACCENTED[i] → BASE[i]. Cheaper than a Map and the pairing is checked once at load.
    private const val ACCENTED =
        "ÀÁÂÃÄÅàáâãäåÈÉÊËèéêëÌÍÎÏìíîïÒÓÔÕÖØòóôõöøÙÚÛÜùúûüÝýÿÑñÇçŠšŽžĀāĪīŪūĒēŌō"
    private const val BASE =
        "AAAAAAaaaaaaEEEEeeeeIIIIiiiiOOOOOOooooooUUUUuuuuYyyNnCcSsZzAaIiUuEeOo"

    init {
        require(ACCENTED.length == BASE.length) { "accent tables out of step" }
    }
}
