package com.bnm.analyzersim.ui

import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.Cli
import com.bnm.analyzersim.CliError
import com.bnm.analyzersim.Faults
import com.bnm.analyzersim.Options
import com.bnm.analyzersim.Profile

/** How the simulator is wired to the lab PC. */
enum class TransportKind { TCP, SERIAL }

/**
 * Everything on screen, as data.
 *
 * Numbers are held as the STRINGS the engineer typed, not as Int: a field
 * cleared halfway to being retyped is empty, not zero, and a box that snaps
 * back to 0 while someone is deleting a digit is the reason form UIs feel
 * hostile. [problems] turns the strings into complaints, and [toOptions] is
 * only called once there are none.
 *
 * The whole thing is a plain value object with no Compose in it, so the rules
 * that actually matter — which transport a driver can use, what the default
 * port is, what the lab should expect to see — are testable without a window.
 */
data class SimForm(
    val analyzer: Analyzer = Analyzer.MINDRAY,
    val transport: TransportKind = TransportKind.TCP,
    val host: String = "127.0.0.1",
    val port: String = Analyzer.MINDRAY.defaultPort.toString(),
    val serialPort: String = "",
    val baud: String = "115200",
    /** Self-identifying on purpose: an id that could pass for a real accession
     *  is one tail-match away from a patient's order. */
    val sampleId: String = "BNMTEST-0001",
    /**
     * Advance the trailing number PER SAMPLE, so a five-sample rehearsal is
     * five accessions rather than five results fighting over one, and the next
     * run starts after the batch this one used.
     *
     * Ignored when the id box holds a pattern or a comma list: those are an
     * enumeration the engineer wrote out, and they cycle.
     */
    val autoIncrementId: Boolean = true,
    val count: String = "1",
    val intervalSeconds: String = "0",
    val profile: Profile = Profile.NORMAL,
    val patientName: String = "Asha Menon",
    val patientId: String = "PAT-9001",
    val qc: Boolean = false,
    val histograms: Boolean = true,
    /** Mindray run mode CBC — no differential rows at all, not merely no curves. */
    val cbcOnly: Boolean = false,
    val image: Boolean = false,
    val seed: String = "1",
    val faults: FaultForm = FaultForm(),
    /**
     * Ticked by hand, for this form, to send invented results to ANOTHER
     * machine. Deliberately not saved by a preset and cleared whenever the
     * address changes: consent is per-target, and a preset that carried it
     * would be a preset that silently re-consents.
     */
    val liveLab: Boolean = false,
) {

    /** Whether this run would leave this computer. */
    val sendsToAnotherMachine: Boolean
        get() = transport == TransportKind.TCP && host.isNotBlank() && !Cli.isLoopback(host)

    /** RS-232 is a Mispa-only link — see [serialRefusal]. */
    val serialAllowed: Boolean get() = analyzer == Analyzer.MISPA

    /** Why the serial option is greyed out, or null when it is not. */
    val serialRefusal: String?
        get() = if (serialAllowed) null else
            "The Mindray driver waits for its ACK on the same socket, and a serial cable has no " +
                "way to carry one — so this analyzer is TCP only."

    /** The driver key the lab must have picked on Settings ▸ Instruments. */
    val driverKey: String get() = analyzer.driverKey

    /**
     * Switch analyzer.
     *
     * The port follows unless the engineer typed their own: replacing a
     * hand-entered 6000 with 5501 because they glanced at the other analyzer
     * would send the next run somewhere nothing is listening. A port that is
     * still one of the two defaults is not a decision, so it moves.
     */
    fun withAnalyzer(next: Analyzer): SimForm {
        val defaults = Analyzer.entries.map { it.defaultPort.toString() }
        val nextPort = if (port.isBlank() || port in defaults) next.defaultPort.toString() else port
        // A Mindray cannot be on a cable; drop back to TCP rather than leaving
        // a radio selected that the Send button would then refuse.
        val nextTransport = if (next == Analyzer.MISPA) transport else TransportKind.TCP
        // The Mispa format carries no unit field at all, so there is nothing for
        // "bad units" to spoil. Drop the tick rather than leave it set over a
        // frame it cannot change — a fault that is quietly a no-op is worse than
        // a missing one.
        val nextFaults = if (next == Analyzer.MINDRAY) faults else faults.copy(badUnits = false)
        return copy(analyzer = next, port = nextPort, transport = nextTransport, faults = nextFaults)
    }

    /** Ignored for a Mindray, which has nowhere to hear its ACK on a cable. */
    fun withTransport(next: TransportKind): SimForm =
        if (next == TransportKind.SERIAL && !serialAllowed) this else copy(transport = next)

    /** Typing in the address box withdraws consent given for the old one. */
    fun withHost(next: String): SimForm =
        copy(host = next, liveLab = if (next.trim() == host.trim()) liveLab else false)

    /** The id box as a list: one id, or the several a comma list or `{1..5}` names. */
    private fun expandedIds(): List<String> =
        Cli.expandIds(sampleId.trim()).ifEmpty { listOf(sampleId.trim()) }

    /** What the run will use, once [problems] is empty. */
    fun toOptions(): Options {
        val ids = expandedIds()
        return Options(
            analyzer = analyzer,
            host = host.trim(),
            port = port.trim().toIntOrNull() ?: analyzer.defaultPort,
            serialPort = if (transport == TransportKind.SERIAL && serialAllowed) serialPort.trim() else null,
            baud = baud.trim().toIntOrNull() ?: 115200,
            ids = ids,
            // The tick is about ONE id advancing. A pattern or a comma list already
            // names every accession it wants, so it cycles — see Options.sampleIds.
            autoIncrementIds = autoIncrementId && ids.size == 1,
            count = count.trim().toIntOrNull() ?: 1,
            intervalSeconds = intervalSeconds.trim().toDoubleOrNull() ?: 0.0,
            profile = profile,
            patientName = patientName.trim().ifBlank { null },
            patientId = patientId.trim().ifBlank { null },
            qc = qc,
            histograms = histograms,
            cbcOnly = cbcOnly && analyzer == Analyzer.MINDRAY,
            image = image,
            seed = seed.trim().toLongOrNull() ?: 1L,
            unknownCode = faults.unknownCode,
            // Mindray-only, like cbcOnly above: the Mispa frame has no unit field to
            // spoil. A preset saved on a Mindray and loaded on a Mispa would
            // otherwise carry a tick that changes not one byte.
            badUnits = faults.badUnits && analyzer == Analyzer.MINDRAY,
            noSpecimen = faults.noSpecimen,
            faults = Faults(
                truncated = faults.truncated,
                garbage = faults.garbage,
                slowChunksMs = if (faults.slowChunks) faults.slowChunksMs.trim().toLongOrNull() ?: 40L else null,
                duplicate = faults.duplicate,
                burst = if (faults.burst) faults.burstCount.trim().toIntOrNull() ?: 5 else 0,
                hang = faults.hang,
            ),
            liveLab = liveLab,
        )
    }

    /**
     * The form after a run, with the id advanced PAST THE WHOLE BATCH the run
     * just sent.
     *
     * A five-sample run consumes five accessions, so the next run has to start
     * at the sixth. Advancing by one would put run two back on top of run one's
     * second sample — the same overwrite the per-sample sequence exists to
     * avoid, only one run later and far harder to spot.
     */
    fun afterRun(): SimForm {
        // A pattern or a comma list is an enumeration the engineer wrote out;
        // rewriting 'ACC-S1-000{1..5}' as 'ACC-S1-0006' would destroy it.
        if (!autoIncrementId || expandedIds().size != 1) return this
        return copy(sampleId = nextSampleId(toOptions().sampleIds.last()))
    }

    /**
     * Everything wrong with the form, in the words the engineer needs. Empty
     * means Send is safe to press.
     */
    fun problems(): List<FormProblem> = buildList {
        if (transport == TransportKind.SERIAL && serialAllowed) {
            if (serialPort.isBlank()) add(FormProblem(FormField.SERIAL_PORT,
                "Pick the serial port the cable is plugged into. Refresh if the adapter went in just now."))
            val b = baud.trim().toIntOrNull()
            if (b == null || b !in 1..4_000_000) add(FormProblem(FormField.BAUD,
                "Baud must be a number — the Mispa Count X leaves the factory at 115200."))
        } else {
            if (host.isBlank()) add(FormProblem(FormField.HOST,
                "Type the address of the PC running BNM Lab — 127.0.0.1 if that is this machine."))
            val p = port.trim().toIntOrNull()
            if (p == null || p !in 1..65535) add(FormProblem(FormField.PORT,
                "Port must be a whole number between 1 and 65535. BNM Lab listens on " +
                    "${analyzer.defaultPort} for this driver unless the lab changed it."))
        }
        if (sampleId.isBlank() && !faults.noSpecimen) add(FormProblem(FormField.SAMPLE_ID,
            "Type the accession on the tube — or tick 'no specimen id' to rehearse the claim queue."))
        val c = count.trim().toIntOrNull()
        if (c == null || c < 1) add(FormProblem(FormField.COUNT, "Send at least one sample."))
        else if (c > 500) add(FormProblem(FormField.COUNT,
            "$c samples is a load test, not a rehearsal. Keep it under 500."))
        val interval = intervalSeconds.trim().toDoubleOrNull()
        if (interval == null || interval < 0) add(FormProblem(FormField.INTERVAL,
            "Seconds between samples — 0 to send them back to back."))
        if (seed.trim().toLongOrNull() == null) add(FormProblem(FormField.SEED,
            "The seed is a whole number. The same seed is the same patient, forever."))
        if (faults.slowChunks) {
            val ms = faults.slowChunksMs.trim().toLongOrNull()
            if (ms == null || ms < 0) add(FormProblem(FormField.SLOW_CHUNKS,
                "Milliseconds between the 64-byte pieces — 40 is a slow USB-serial adapter."))
        }
        if (faults.burst) {
            val n = faults.burstCount.trim().toIntOrNull()
            if (n == null || n !in 1..100) add(FormProblem(FormField.BURST,
                "How many connections at once, 1 to 100."))
        }
        // Then the core's own refusals, rather than a second copy of them here.
        // Cli.validate is the ONE gate — the live-lab consent, the three faults
        // that need a connection a cable has not got, CBC-only on a 3-part
        // analyzer. A rule added there reaches this window with no edit.
        if (isEmpty()) {
            try {
                Cli.validate(toOptions())
            } catch (e: CliError) {
                add(FormProblem(fieldFor(e.message.orEmpty()), e.message.orEmpty()))
            }
        }
    }

    /** The first complaint about [field], for the box's own error line. */
    fun problem(field: FormField): String? = problems().firstOrNull { it.field == field }?.message

    /** Put a core refusal under the control it is about, so it is read. */
    private fun fieldFor(message: String): FormField = when {
        "--live-lab" in message -> FormField.LIVE_LAB
        "--truncated" in message || "--burst" in message || "--hang" in message -> FormField.FAULTS
        "--cbc-only" in message -> FormField.CBC_ONLY
        "--serial" in message || "serial" in message -> FormField.SERIAL_PORT
        else -> FormField.HOST
    }
}

/** The deliberate misbehaviours, as checkboxes. */
data class FaultForm(
    val truncated: Boolean = false,
    val garbage: Boolean = false,
    val slowChunks: Boolean = false,
    val slowChunksMs: String = "40",
    val unknownCode: Boolean = false,
    val badUnits: Boolean = false,
    val noSpecimen: Boolean = false,
    val duplicate: Boolean = false,
    val burst: Boolean = false,
    val burstCount: String = "5",
    val hang: Boolean = false,
) {
    val any: Boolean
        get() = truncated || garbage || slowChunks || unknownCode || badUnits ||
            noSpecimen || duplicate || burst || hang

    val chosenCount: Int
        get() = listOf(truncated, garbage, slowChunks, unknownCode, badUnits,
            noSpecimen, duplicate, burst, hang).count { it }
}

/** Which box a complaint belongs under. */
enum class FormField {
    HOST, PORT, SERIAL_PORT, BAUD, SAMPLE_ID, COUNT, INTERVAL, SEED, SLOW_CHUNKS, BURST,
    /** The consent tick that has to be given before a run leaves this computer. */
    LIVE_LAB,
    /** A fault the chosen link cannot carry. */
    FAULTS,
    CBC_ONLY,
}

data class FormProblem(val field: FormField, val message: String)

/**
 * The next id in a sequence: SIM-0001 → SIM-0002, ACC-S1-00042 → ACC-S1-00043.
 *
 * One line, delegating to the core's [com.bnm.analyzersim.nextSpecimenId],
 * because the CLI advances ids too and two implementations would eventually
 * disagree about a leading zero — at which point the window's "+1 per run"
 * would point at an accession the command line never generates.
 */
fun nextSampleId(id: String): String = com.bnm.analyzersim.nextSpecimenId(id)
