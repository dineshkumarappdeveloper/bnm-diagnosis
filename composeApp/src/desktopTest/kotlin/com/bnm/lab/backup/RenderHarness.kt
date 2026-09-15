package com.bnm.lab.backup

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.bnm.lab.ui.theme.AppTheme
import com.bnm.lab.ui.theme.ThemeChoice
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Draws [content] the way RevenueScreenRenderTest does — one ImageComposeScene
 * per test (a second scene in one test never received its data there), a few
 * frames so effects and flows settle, and a PNG under build/backup-render for
 * a human to look at. A layout exception surfaces from render() and fails the
 * test; that is the whole point.
 */
internal fun renderScene(
    name: String,
    width: Int = 1200,
    height: Int = 900,
    frames: Int = 6,
    content: @Composable () -> Unit,
) {
    val out = File("build/backup-render").apply { mkdirs() }
    ImageComposeScene(width, height, Density(1f)) {
        AppTheme(themeChoice = ThemeChoice.LIGHT) { content() }
    }.use { scene ->
        var img = scene.render(0)
        repeat(frames) { i -> Thread.sleep(20); img = scene.render((i + 1) * 50_000_000L) }
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
