package com.bnm.diagnosis.instruments

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

/** Live listener state for one configured instrument, keyed by instrument id. */
data class InstrumentStatus(
    val state: String,                  // 'listening' | 'error' | 'off'
    val detail: String? = null,
    val lastFrameAt: String? = null,
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
