package com.bnm.analyzersim.ui

import com.bnm.analyzersim.Analyzer
import com.bnm.analyzersim.Profile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A named form, on disk.
 *
 * An engineer commissions the same three or four setups over and over, and
 * retyping a lab's IP into nine boxes at a client's bench is where the wrong
 * port comes from. The stored shape is deliberately its OWN class of plain
 * strings rather than [SimForm] serialised directly: the form is a UI detail
 * that will gain and lose fields, and a preset file that stops loading because
 * a checkbox was renamed would be worse than no presets. Unknown keys are
 * ignored and missing ones fall back to the default, so a file written by an
 * older or a newer build still opens.
 */
@Serializable
data class PresetJson(
    val name: String,
    val analyzer: String = Analyzer.MINDRAY.cliName,
    val transport: String = "tcp",
    val host: String = "127.0.0.1",
    val port: String = "5500",
    @SerialName("serial_port") val serialPort: String = "",
    val baud: String = "115200",
    @SerialName("sample_id") val sampleId: String = "BNMTEST-0001",
    @SerialName("auto_increment_id") val autoIncrementId: Boolean = true,
    val count: String = "1",
    @SerialName("interval_seconds") val intervalSeconds: String = "0",
    val profile: String = Profile.NORMAL.cliName,
    @SerialName("patient_name") val patientName: String = "",
    @SerialName("patient_id") val patientId: String = "",
    val qc: Boolean = false,
    val histograms: Boolean = true,
    @SerialName("cbc_only") val cbcOnly: Boolean = false,
    val image: Boolean = false,
    val seed: String = "1",
    val truncated: Boolean = false,
    val garbage: Boolean = false,
    @SerialName("slow_chunks") val slowChunks: Boolean = false,
    @SerialName("slow_chunks_ms") val slowChunksMs: String = "40",
    @SerialName("unknown_code") val unknownCode: Boolean = false,
    @SerialName("bad_units") val badUnits: Boolean = false,
    @SerialName("no_specimen") val noSpecimen: Boolean = false,
    val duplicate: Boolean = false,
    val burst: Boolean = false,
    @SerialName("burst_count") val burstCount: String = "5",
    val hang: Boolean = false,
) {
    fun toForm(): SimForm {
        val a = Analyzer.of(analyzer) ?: Analyzer.MINDRAY
        return SimForm(
            analyzer = a,
            // A preset that names a serial link for a Mindray cannot be honoured;
            // the form's own rule wins rather than loading an impossible state.
            transport = if (transport.equals("serial", true) && a == Analyzer.MISPA)
                TransportKind.SERIAL else TransportKind.TCP,
            host = host,
            port = port.ifBlank { a.defaultPort.toString() },
            serialPort = serialPort,
            baud = baud,
            sampleId = sampleId,
            autoIncrementId = autoIncrementId,
            count = count,
            intervalSeconds = intervalSeconds,
            profile = Profile.of(profile) ?: Profile.NORMAL,
            patientName = patientName,
            patientId = patientId,
            qc = qc,
            histograms = histograms,
            cbcOnly = cbcOnly,
            image = image,
            // liveLab is deliberately NOT restored: consent to write invented
            // results to another machine is given for one run, by hand. A
            // preset that carried it would re-consent silently, months later.
            seed = seed,
            faults = FaultForm(
                truncated = truncated,
                garbage = garbage,
                slowChunks = slowChunks,
                slowChunksMs = slowChunksMs,
                unknownCode = unknownCode,
                badUnits = badUnits,
                noSpecimen = noSpecimen,
                duplicate = duplicate,
                burst = burst,
                burstCount = burstCount,
                hang = hang,
            ),
        )
    }

    companion object {
        fun of(name: String, f: SimForm) = PresetJson(
            name = name,
            analyzer = f.analyzer.cliName,
            transport = if (f.transport == TransportKind.SERIAL) "serial" else "tcp",
            host = f.host,
            port = f.port,
            serialPort = f.serialPort,
            baud = f.baud,
            sampleId = f.sampleId,
            autoIncrementId = f.autoIncrementId,
            count = f.count,
            intervalSeconds = f.intervalSeconds,
            profile = f.profile.cliName,
            patientName = f.patientName,
            patientId = f.patientId,
            qc = f.qc,
            histograms = f.histograms,
            cbcOnly = f.cbcOnly,
            image = f.image,
            seed = f.seed,
            truncated = f.faults.truncated,
            garbage = f.faults.garbage,
            slowChunks = f.faults.slowChunks,
            slowChunksMs = f.faults.slowChunksMs,
            unknownCode = f.faults.unknownCode,
            badUnits = f.faults.badUnits,
            noSpecimen = f.faults.noSpecimen,
            duplicate = f.faults.duplicate,
            burst = f.faults.burst,
            burstCount = f.faults.burstCount,
            hang = f.faults.hang,
        )
    }
}

/**
 * Reading and writing the preset file.
 *
 * [dir] is injected so the tests never touch the engineer's own presets — and
 * so a packaged app running with a redirected home still finds its own folder.
 */
class PresetStore(private val dir: File = appDataDir()) {

    private val file: File get() = File(dir, FILE_NAME)

    /** The built-ins first, then whatever is on disk, newest name wins. */
    fun load(): List<PresetJson> {
        val saved = runCatching {
            if (!file.exists()) emptyList() else JSON.decodeFromString(ListSerializer(PresetJson.serializer()), file.readText())
        }.getOrElse {
            // A corrupt file must not stop the tool opening — the built-ins are
            // enough to commission with, and the engineer can save over it.
            emptyList()
        }
        val savedNames = saved.map { it.name }.toSet()
        return BUILT_IN.filterNot { it.name in savedNames } + saved
    }

    /** Add or replace [name]; returns the new list. */
    fun save(name: String, form: SimForm): List<PresetJson> {
        val entry = PresetJson.of(name.trim(), form)
        val kept = load().filterNot { it.name.equals(entry.name, ignoreCase = true) }
        // Built-ins are not written out unless the engineer edited one: the file
        // stays small and a later change to a built-in reaches existing installs.
        val next = (kept.filterNot { it in BUILT_IN } + entry).sortedBy { it.name.lowercase() }
        write(next)
        return load()
    }

    fun delete(name: String): List<PresetJson> {
        write(load().filterNot { it in BUILT_IN }.filterNot { it.name.equals(name, ignoreCase = true) })
        return load()
    }

    /** Where the file is, for the "saved to …" line the UI shows. */
    val path: String get() = file.absolutePath

    private fun write(all: List<PresetJson>) {
        runCatching {
            dir.mkdirs()
            file.writeText(JSON.encodeToString(ListSerializer(PresetJson.serializer()), all))
        }
    }

    companion object {
        private const val FILE_NAME = "presets.json"

        private val JSON = Json {
            prettyPrint = true
            // A preset file from a newer build must still open in an older one.
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * The three setups that cover almost every bench. Shipped rather than
         * documented, because the engineer who most needs this tool is the one
         * who has not read the README.
         */
        val BUILT_IN: List<PresetJson> = listOf(
            PresetJson(
                name = "Mindray on this PC",
                analyzer = Analyzer.MINDRAY.cliName,
                port = Analyzer.MINDRAY.defaultPort.toString(),
                patientName = "Asha Menon",
                patientId = "PAT-9001",
            ),
            PresetJson(
                name = "Mispa on this PC",
                analyzer = Analyzer.MISPA.cliName,
                port = Analyzer.MISPA.defaultPort.toString(),
                // The Mispa frame carries no patient name; leaving one in the box
                // would suggest it travels.
                patientId = "PAT-9001",
            ),
            PresetJson(
                name = "Commissioning rehearsal",
                analyzer = Analyzer.MINDRAY.cliName,
                port = Analyzer.MINDRAY.defaultPort.toString(),
                count = "5",
                intervalSeconds = "2",
                autoIncrementId = true,
                patientName = "Asha Menon",
                patientId = "PAT-9001",
            ),
        )
    }
}

/**
 * This tool's own per-user data directory — the same OS rules BNM Lab uses,
 * under a DIFFERENT name. Sharing BNM Lab's folder would put a test tool's
 * settings next to a lab's records, and one day next to its database.
 */
fun appDataDir(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home").orEmpty()
    val base = when {
        os.contains("win") -> System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
        os.contains("mac") -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
    }
    return File(base, "BNMAnalyzerSim").apply { mkdirs() }
}
