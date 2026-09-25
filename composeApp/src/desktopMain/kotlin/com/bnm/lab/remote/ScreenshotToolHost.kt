package com.bnm.lab.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * `app.screenshot` — a picture of the BNM Lab window, and only that window,
 * as it is on the lab's screen; the one tool that needs the SCREEN consent
 * and the one that lives in desktopMain (it needs `java.awt.Robot`, which the
 * jlink image has via java.desktop). Main.kt registers the Compose window.
 *
 * Downscaled to ≤ 1280 px wide and returned as MCP image content: PNG while
 * its base64 fits the frame budget, else JPEG 80 — a 1280-px window comes out
 * around 100–300 KB either way, comfortably under the relay's limit.
 */
class ScreenshotToolHost(private val window: () -> java.awt.Window?) : RemoteToolHost {

    override fun tools(): List<ToolSpec> = listOf(SPEC)

    override suspend fun call(call: ToolCall): ToolResult {
        // The core checks consent before dispatch; checked again here so the
        // tool is safe even if it is ever reached another way.
        if (!call.session.consent.screen) return ToolResult.Refused("Not allowed: the lab owner did not give screen view consent")
        val w = window()?.takeIf { it.isShowing && it.width > 0 && it.height > 0 }
            ?: return ToolResult.Failed("The app window is not on screen")
        val captured = runCatching {
            withContext(Dispatchers.IO) {
                val b = w.bounds
                Robot().createScreenCapture(Rectangle(b.x, b.y, b.width, b.height))
            }
        }.getOrElse { return ToolResult.Failed("Screen capture is not available on this computer (${it::class.simpleName})") }
        val (bytes, mime) = encode(captured)
        // A 1280-px JPEG at quality 80 is far under this; a wildly detailed one is refused rather than dropped by the relay.
        if (bytes.size > MAX_BYTES) return ToolResult.Failed("The screenshot is too large to send (${bytes.size / 1024} KB)")
        return ToolResult.Ok(
            contentJson = Json.encodeToString(JsonArray.serializer(), buildJsonArray {
                add(buildJsonObject {
                    put("type", "image")
                    put("data", Base64.getEncoder().encodeToString(bytes))
                    put("mimeType", mime)
                })
            }),
            summaryForAudit = "screenshot ${captured.width}x${captured.height} → ${mime.substringAfter('/')} ${bytes.size / 1024} KB",
        )
    }

    companion object {
        const val NAME = "app.screenshot"
        const val MAX_WIDTH = 1_280
        /** Raw PNG bytes over this go JPEG 80 — 640 KB raw is ~850 KB as base64, under the 900 KB frame cap. */
        const val PNG_BUDGET_BYTES = 640 * 1024
        /** Absolute cap on what is sent, whichever encoding (base64 grows it by a third). */
        const val MAX_BYTES = 640 * 1024
        const val JPEG_QUALITY = 0.80f

        val SPEC = ToolSpec(
            name = NAME,
            description = "A picture of the BNM Lab window as it is on the lab's screen right now, " +
                "downscaled to at most 1280 px wide. Shows whatever the person at the PC has open.",
            inputSchemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
            readOnly = true,
            destructive = false,
            requires = ConsentKind.SCREEN,
        )

        /** Downscale + encode. Pure — a test can feed a synthetic image. Returns bytes + MIME type. */
        internal fun encode(src: BufferedImage, forceJpeg: Boolean = false): Pair<ByteArray, String> {
            val img = downscale(src)
            if (!forceJpeg) {
                val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
                if (png.size <= PNG_BUDGET_BYTES) return png to "image/png"
            }
            val out = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            try {
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = JPEG_QUALITY
                }
                ImageIO.createImageOutputStream(out).use { ios ->
                    writer.output = ios
                    writer.write(null, IIOImage(img, null, null), params)
                }
            } finally {
                writer.dispose()
            }
            return out.toByteArray() to "image/jpeg"
        }

        /** ≤ [MAX_WIDTH] wide, RGB (JPEG cannot take alpha), smooth interpolation. */
        internal fun downscale(src: BufferedImage): BufferedImage {
            val scale = if (src.width > MAX_WIDTH) MAX_WIDTH.toDouble() / src.width else 1.0
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(src, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }
            return out
        }
    }
}
