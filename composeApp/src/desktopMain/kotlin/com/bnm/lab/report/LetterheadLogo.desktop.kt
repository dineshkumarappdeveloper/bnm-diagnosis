package com.bnm.lab.report

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Desktop logo picker: a file chooser, then a re-encode to PNG.
 *
 * WHY RE-ENCODE rather than store the chosen file's bytes. Whatever the lab
 * picks — a 4 MB phone photo of a signboard, a CMYK JPEG, a BMP — has to end
 * up as something every renderer can draw and small enough to sit in a
 * database row that is backed up and synced. PNG at a bounded size is that
 * thing, and transparency survives it, which matters for a logo dropped onto
 * a white letterhead.
 */
actual suspend fun pickLetterheadLogoPng(): ByteArray? = withContext(Dispatchers.Default) {
    val file = chooseFile() ?: return@withContext null
    runCatching {
        val src = ImageIO.read(file) ?: return@withContext null
        val scaled = downscale(src, LetterheadLogo.MAX_EDGE_PX)
        val out = ByteArrayOutputStream()
        ImageIO.write(scaled, "png", out)
        out.toByteArray().takeIf { it.isNotEmpty() && it.size <= LetterheadLogo.MAX_BYTES }
    }.getOrNull()
}

actual fun letterheadLogoPickerSupported(): Boolean = !java.awt.GraphicsEnvironment.isHeadless()

private fun chooseFile(): File? {
    if (java.awt.GraphicsEnvironment.isHeadless()) return null
    val chooser = JFileChooser().apply {
        dialogTitle = "Choose a letterhead logo"
        isMultiSelectionEnabled = false
        fileFilter = FileNameExtensionFilter("Images (PNG, JPG, BMP, GIF)", "png", "jpg", "jpeg", "bmp", "gif")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/**
 * Fit inside [maxEdge] without distorting, and never scale UP: enlarging a
 * small logo only makes it blurry on paper, and the renderer will size it to
 * the header band anyway.
 */
internal fun downscale(src: BufferedImage, maxEdge: Int): BufferedImage {
    val longest = maxOf(src.width, src.height)
    if (longest <= maxEdge) return toArgb(src)
    val scale = maxEdge.toDouble() / longest
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.drawImage(src, 0, 0, w, h, null)
    g.dispose()
    return out
}

/** ImageIO hands back all sorts of colour models; PDFBox is happiest with ARGB. */
private fun toArgb(src: BufferedImage): BufferedImage {
    if (src.type == BufferedImage.TYPE_INT_ARGB) return src
    val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.drawImage(src, 0, 0, null)
    g.dispose()
    return out
}
