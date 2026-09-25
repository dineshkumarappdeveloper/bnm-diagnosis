package com.bnm.analyzersim.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.bnm.analyzersim.AckOutcome
import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.BytesSent
import com.bnm.analyzersim.Profile
import com.bnm.analyzersim.RunFinished
import com.bnm.analyzersim.RunKind
import com.bnm.analyzersim.RunStart
import com.bnm.analyzersim.SampleStarted
import com.bnm.analyzersim.SimMllp
import com.bnm.analyzersim.cbcFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Draws the whole window, one [ImageComposeScene] per test — the repo's
 * convention, and for the reason RevenueScreenRenderTest gives: a layout-time
 * crash passes every unit test and then closes the app the moment a human opens
 * it. Nothing else in this module lays Compose out.
 *
 * The frames land in `analyzer-sim-ui/build/screen-render/` for a human look;
 * the assertions are the structural ones a screenshot cannot make.
 */
class SimScreenRenderTest {

    private val out = File("build/screen-render").apply { mkdirs() }

    private fun state(): SimState = SimState(
        // A directory that does not exist: the built-ins load, and the test can
        // never read or write the engineer's own presets.
        PresetStore(File(System.getProperty("java.io.tmpdir"), "bnm-sim-render-${System.nanoTime()}")),
        CoroutineScope(Dispatchers.Unconfined),
    )

    /** Renders [state] at window size and returns the frame as pixels. */
    private fun render(name: String, state: SimState): BufferedImage {
        val png = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) { SimTheme { SimScreen(state) } }.use { scene ->
            var image = scene.render(0)
            // ~800ms of frames: enough for the transcript's auto-scroll and for
            // the faults panel's scroll-into-view animation to land.
            repeat(16) { i -> image = scene.render((i + 1) * 50_000_000L) }
            image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        File(out, "$name.png").writeBytes(png)
        return ImageIO.read(ByteArrayInputStream(png))
    }

    @Test
    fun `the window as it opens`() {
        val state = state()
        // What the engineer sees before touching anything has to be sendable.
        assertTrue(state.form.problems().isEmpty(), "${state.form.problems()}")

        val image = render("01-default", state)
        assertEquals(WIDTH, image.width)
        assertEquals(HEIGHT, image.height)
        assertTrue(image.distinctColours() > 200, "the window rendered almost blank")
        // The expectation strip sits at the foot of the transcript column, above
        // the Send bar. If it has slid off, the teaching is gone.
        assertTrue(image.expectationStripVisible(), "the \"what should happen\" line is not on screen")
        // …and only there. A band of it in the form column would mean the two
        // columns had collapsed into one; a few dozen pixels is just the green
        // switch and button anti-aliasing through the tolerance.
        val tintInForm = image.getSubimage(0, 0, WIDTH - TRANSCRIPT_WIDTH, HEIGHT).count(EXPECTATION_TINT)
        assertTrue(tintInForm < 200, "the columns have collapsed: $tintInForm tinted pixels in the form")
        // And the Send button is drawn in the brand green.
        assertTrue(image.count(PRIMARY) > 400, "no Send button: ${image.count(PRIMARY)} accent pixels")
    }

    @Test
    fun `the faults panel expanded`() {
        val state = state()
        state.faultsExpanded = true
        state.edit { it.copy(faults = FaultForm(truncated = true, slowChunks = true, slowChunksMs = "40")) }

        assertTrue(labExpectation(state.form).contains("unframed bytes"),
            "the hint must follow the ticked fault")

        val image = render("02-faults-expanded", state)
        // Nine faults, each a checkbox and two lines of text: the card cannot
        // fit above the transcript, so the expanded panel MUST be scrollable
        // rather than clipped. Rendering proves it lays out; this proves the
        // window still has its fixed furniture underneath it.
        assertTrue(image.expectationStripVisible(),
            "the expanded panel pushed the expectation line off the window")
        // Expanding brings the panel into view; nine ticked-or-unticked boxes
        // are drawn, two of them filled with the accent.
        val formColumn = image.getSubimage(0, 90, WIDTH - TRANSCRIPT_WIDTH, HEIGHT - 150)
        assertTrue(formColumn.count(PRIMARY) > 300,
            "the faults panel did not scroll itself into view: ${formColumn.count(PRIMARY)} accent pixels")
    }

    @Test
    fun `a transcript with one sample delivered and one that got no ACK`() {
        val state = state()
        state.edit { it.copy(sampleId = "ACC-S1-00042", count = "2") }
        state.replay(
            SimEvent.Started(RunStart(Analyzer.MINDRAY,
                "TCP 192.168.1.50:5500 (the simulator dials out, as the analyzer does)",
                2, Profile.NORMAL, 7L, faults = "", specimenIds = "ACC-S1-00042, ACC-S1-00043")),
            SimEvent.Sample(sample(0, "ACC-S1-00042")),
            SimEvent.Bytes(BytesSent(0, 3519, "TCP 192.168.1.50:5500", false)),
            SimEvent.Ack(AckOutcome.Received(0, 12,
                SimMllp.Ack("AA", "SIM20260101090000001", "MSA|AA|SIM20260101090000001"))),
            SimEvent.Sample(sample(1, "ACC-S1-00043")),
            SimEvent.Bytes(BytesSent(1, 3519, "TCP 192.168.1.50:5500", false)),
            SimEvent.Ack(AckOutcome.Missing(1, 5.0)),
            SimEvent.Finished(RunFinished(RunKind.SEQUENTIAL, 2, 1, 1)),
        )
        assertEquals(
            listOf(BlockTone.NEUTRAL, BlockTone.OK, BlockTone.FAILED, BlockTone.FAILED),
            state.blocks.map { it.tone },
        )

        val image = render("03-transcript-ok-and-failed", state)
        // Both verdicts have to be visible at once — an engineer scanning a run
        // is looking for the one red bar among the green ones.
        val pane = image.getSubimage(0, HEIGHT - 230, WIDTH, 230)
        assertTrue(pane.count(PRIMARY) > 40, "no green bar in the transcript: ${pane.count(PRIMARY)}")
        assertTrue(pane.count(DANGER) > 40, "no red bar in the transcript: ${pane.count(DANGER)}")
    }

    @Test
    fun `the serial link, with no adapter plugged in`() {
        val state = state()
        state.edit { it.withAnalyzer(Analyzer.MISPA).withTransport(TransportKind.SERIAL) }
        // The state an engineer actually hits: the Mispa is a cable analyzer, and
        // the adapter is still in the bag. The picker has to say so and Send has
        // to refuse, rather than opening a port named "".
        assertTrue(state.serialPorts.isEmpty())
        assertEquals(FormField.SERIAL_PORT, state.form.problems().single().field)
        // And from the other side: the same link is not offered for a Mindray.
        assertEquals(TransportKind.TCP, state.form.withAnalyzer(Analyzer.MINDRAY).transport)

        val image = render("04-serial-no-adapter", state)
        assertTrue(image.distinctColours() > 200)
        // The refusal is drawn in the error colour, under the picker.
        val card = image.getSubimage(0, 90, WIDTH - TRANSCRIPT_WIDTH, 360)
        assertTrue(card.count(DANGER) > 60, "no error line under the port picker: ${card.count(DANGER)}")
    }

    @Test
    fun `pointed at another machine, with the consent not yet given`() {
        val state = state()
        state.edit { it.withHost("192.168.1.50") }
        // The one state where a screenshot is worth more than an assertion: this
        // block is all that stands between a re-run and invented results on a
        // real patient's order, so it has to be impossible to miss.
        assertTrue(state.form.sendsToAnotherMachine)
        assertTrue(state.form.problem(FormField.LIVE_LAB) != null, "Send must be refused")

        val image = render("05-live-lab-consent", state)
        val card = image.getSubimage(0, 90, WIDTH - TRANSCRIPT_WIDTH, 420)
        // Drawn on the error container, which is a broad wash of red — not a
        // thin line of it the way an error message under a box would be.
        assertTrue(card.count(DANGER_SOFT) > 6_000,
            "the consent block is not drawn as a warning: ${card.count(DANGER_SOFT)} tinted pixels")
    }

    private fun sample(index: Int, id: String) = SampleStarted(
        index = index, total = 2, specimenId = id, qc = false,
        cbc = cbcFor(Profile.NORMAL, 7L + index), frame = "MSH|…",
    )

    // ── pixels ──

    private fun BufferedImage.count(rgb: Int): Int {
        var n = 0
        for (y in 0 until height) for (x in 0 until width) if (near(getRGB(x, y), rgb)) n++
        return n
    }

    private fun BufferedImage.distinctColours(): Int {
        val seen = HashSet<Int>()
        for (y in 0 until height step 3) for (x in 0 until width step 3) seen += getRGB(x, y)
        return seen.size
    }

    /** The tinted band across the foot of the transcript column. */
    private fun BufferedImage.expectationStripVisible(): Boolean =
        (0 until height).any { y ->
            (width - TRANSCRIPT_WIDTH + 10 until width - 10).count { near(getRGB(it, y), EXPECTATION_TINT) } >
                TRANSCRIPT_WIDTH / 2
        }

    /** Anti-aliasing means an exact match finds almost nothing. */
    private fun near(a: Int, b: Int, tolerance: Int = 10): Boolean =
        listOf(16, 8, 0).all { shift ->
            kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= tolerance
        }

    companion object {
        private const val WIDTH = 900
        private const val HEIGHT = 700
        private const val PRIMARY = 0x16A34A        // emerald, the Send button and an OK bar
        private const val DANGER = 0xEF4444         // a failed bar
        private const val DANGER_SOFT = 0xFEE2E2    // the live-lab consent block
        private const val EXPECTATION_TINT = 0xDCFCE7
        private const val TRANSCRIPT_WIDTH = 350
    }
}
