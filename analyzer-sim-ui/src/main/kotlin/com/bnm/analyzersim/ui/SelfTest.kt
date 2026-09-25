package com.bnm.analyzersim.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.MindrayFrames
import com.bnm.analyzersim.MispaFrames
import com.bnm.analyzersim.Profile
import com.bnm.analyzersim.SampleSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Prove the INSTALLED app can do its job, without a display and without a lab.
 *
 * Four things, in the order they break:
 *  1. the core still builds both frames (the jar is on the classpath at all);
 *  2. presets round-trip through a real file in a real app-data directory;
 *  3. Compose lays the whole screen out and Skia rasterises it — this is the
 *     one that catches a jlink module missing from the packaged runtime, which
 *     `./gradlew run` can never catch;
 *  4. jSerialComm loads its native library (needs jdk.unsupported).
 *
 * Exit 0 and one line, so a build script can assert on it.
 */
fun selfTest(): Int {
    val failures = mutableListOf<String>()

    val mindray = runCatching {
        MindrayFrames.frame(SampleSpec(specimenId = "SELFTEST", profile = Profile.CRITICAL)).size
    }.getOrElse { failures += "mindray frame: $it"; 0 }

    val mispa = runCatching {
        MispaFrames.build(SampleSpec(specimenId = "SELFTEST")).length
    }.getOrElse { failures += "mispa frame: $it"; 0 }

    val presets = runCatching {
        val dir = File(System.getProperty("java.io.tmpdir"), "bnm-analyzer-sim-selftest").apply { mkdirs() }
        val store = PresetStore(dir)
        val saved = store.save("selftest", SimForm().withAnalyzer(Analyzer.MISPA).copy(host = "10.0.0.9"))
        val back = saved.first { it.name == "selftest" }.toForm()
        check(back.host == "10.0.0.9" && back.analyzer == Analyzer.MISPA) { "preset did not round-trip" }
        dir.deleteRecursively()
        saved.size
    }.getOrElse { failures += "presets: $it"; 0 }

    // The real screen, off-screen. Everything Compose Desktop needs at runtime —
    // Skiko's native library, AWT fonts, the whole module set — is exercised here.
    val pixels = runCatching {
        val state = SimState(
            PresetStore(File(System.getProperty("java.io.tmpdir"), "bnm-analyzer-sim-selftest-ui")),
            CoroutineScope(Dispatchers.Unconfined),
        )
        ImageComposeScene(900, 700, Density(1f)) { SimTheme { SimScreen(state) } }.use { scene ->
            scene.render(0).let { it.width * it.height }
        }
    }.getOrElse { failures += "compose render: $it"; 0 }

    val serial = runCatching { LinkTest.serialPorts().size }.getOrElse { failures += "jSerialComm: $it"; -1 }

    if (failures.isNotEmpty()) {
        failures.forEach { System.err.println("selftest FAILED — $it") }
        return 1
    }
    println(
        "selftest OK — mindray frame $mindray bytes, mispa frame $mispa chars, " +
            "$presets presets, screen rendered $pixels px, $serial serial port(s) visible."
    )
    return 0
}
