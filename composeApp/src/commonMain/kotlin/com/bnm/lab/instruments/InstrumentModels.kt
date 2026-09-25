package com.bnm.lab.instruments

import kotlinx.serialization.Serializable

/**
 * Analyzer interfacing (I0) — domain models.
 *
 * An [InstrumentConfig] is one physical analyzer wired to this PC (or reachable
 * on the LAN). The connection config is DEVICE setup, not tenant data — it
 * survives a licence switch the way printer settings do; everything the
 * analyzer *sends* (frames, unmatched results, graphs) is tenant data and is
 * wiped with the tenant.
 */
data class InstrumentConfig(
    val id: String,
    val name: String,
    val driver: String,                 // InstrumentDriver.key
    val transport: String,              // 'serial' | 'tcp'
    val serialPort: String? = null,
    val baud: Int = 115200,
    val tcpPort: Int? = null,
    val enabled: Boolean = true,
    /** Explicit analyzer-param → catalog parameter_key overrides (JSON object). */
    val paramMapJson: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    /**
     * Set when BNM remote support changed the driver or the param map. While
     * true every frame from this analyzer is queued for manual claim instead of
     * applied — a mapping typed from the office has to be checked against one
     * known sample at the bench first. Cleared by "Verified" on the
     * Instruments screen (any signed-in staff).
     */
    val verifyPending: Boolean = false,
    /**
     * The analyzer's IP address or hostname, typed by the lab (optional). The
     * Link check pings it from this PC to separate "cable/IP wrong" from
     * "analyzer never pressed Send". The listener itself never dials out.
     */
    val analyzerHost: String? = null,
)

object InstrumentTransport {
    const val SERIAL = "serial"
    const val TCP = "tcp"
}

/**
 * The driver roster. Only drivers listed here appear in the picker; shipping a
 * new protocol engine = adding its entry + its parse/ingest branch in
 * [InstrumentEngine]. Mirrors the shipping-provider registry idea: BNM ships
 * the drivers ready, the lab just picks one and plugs the cable.
 */
data class InstrumentDriver(
    val key: String,
    val label: String,
    val detail: String,
    val defaultBaud: Int,
    /** Bidirectional drivers can answer host queries (barcode → test list). */
    val bidirectional: Boolean,
    /** The analyzer waits for the app's ACK, which only the TCP path can send:
     *  serial is not offered for it. */
    val tcpOnly: Boolean = false,
)

val INSTRUMENT_DRIVERS = listOf(
    InstrumentDriver(
        key = "mispa_count_x",
        label = "Agappe Mispa Count X",
        detail = "3-part hematology · one-way results + WBC/RBC/PLT histograms",
        defaultBaud = 115200,
        bidirectional = false,
    ),
    InstrumentDriver(
        key = "mindray_hl7",
        label = "Mindray BC-5130 / BC-5000 / BC-5150",
        detail = "5-part hematology · HL7 v2.3.1 over TCP (MLLP) · results, WBC/RBC/PLT histograms, DIFF scattergram",
        defaultBaud = 115200,
        bidirectional = false,
        tcpOnly = true,
    ),
    // I2: astm_serial (generic ASTM E1394 bidirectional, host query)
    // I3: hl7_tcp (Erba CXL Pro Plus — HL7 v2.3.1 over TCP, QRY/DSR)
)

fun driverFor(key: String): InstrumentDriver? = INSTRUMENT_DRIVERS.firstOrNull { it.key == key }

/**
 * Live listener state for one configured instrument, keyed by instrument id.
 *
 * The counters answer the "is anything arriving at all?" question a support
 * engineer asks first: bytes prove the cable, frames prove the framing, parsed
 * proves the driver, applied/unmatched prove the accession match. They live for
 * the app's lifetime (a listener restart keeps them) and reset with the app.
 */
data class InstrumentStatus(
    val state: String,                  // 'listening' | 'error' | 'off'
    val detail: String? = null,
    /** Moves only on a parsed RESULT frame. */
    val lastFrameAt: String? = null,
    val bytesIn: Long = 0L,
    /** Complete protocol frames pulled out of the byte stream. */
    val framesIn: Long = 0L,
    /** Frames the driver turned into a result. */
    val framesParsed: Long = 0L,
    val framesApplied: Long = 0L,
    val framesUnmatched: Long = 0L,
    /** Frames dropped: not understood by the driver, QC runs, non-result messages. */
    val framesIgnored: Long = 0L,
    val acksSent: Long = 0L,
    val lastErrorAt: String? = null,
    val lastError: String? = null,
    /** Address of the analyzer that last connected (TCP transport only). */
    val peerIp: String? = null,
    /** When the port/socket was actually opened (null while not listening). */
    val boundAt: String? = null,
)

/**
 * A parsed analyzer frame in storage form — what lands in
 * `instrument_results.payload_json` when a frame can't be matched to an order,
 * and what the claim flow replays later. Params keep the ANALYZER's key names
 * ("LYMP%"), mapping to catalog parameters happens at apply time.
 */
@Serializable
data class StoredInstrumentFrame(
    val driver: String,
    val specimenId: String? = null,
    val patientId: String? = null,
    val date: String? = null,
    val sequenceId: String? = null,
    val params: Map<String, String> = emptyMap(),
    val histograms: Map<String, List<Double>> = emptyMap(),
    val meta: Map<String, String> = emptyMap(),
    /** Analyzer unit per param ("10*9/L", "g/L") — converted to the catalog's
     *  unit at apply time. Empty for analyzers that send none (Mispa). */
    val units: Map<String, String> = emptyMap(),
    /** Analyzer-rendered bitmaps, base64 as sent: "diff" (scattergram), "wbc". */
    val images: Map<String, String> = emptyMap(),
)
