package com.bnm.lab.remote

import kotlinx.coroutines.flow.StateFlow

/**
 * Remote support ("Maintenance mode"): an owner-started, time-boxed session in
 * which BNM's engineer reaches THIS copy of BNM Lab as an MCP server — reads
 * its diagnostics, changes analyzer settings, restarts analyzer links — over a
 * connection the app opens outward to BNM's relay. Nothing is reachable while
 * no session runs. Every call is signed by the engineer, gated by the consent
 * the owner gave, counted on a banner and written to Support history.
 *
 * This file is the whole surface the screens see. The engine (desktopMain
 * `RemoteSupportService`) is the only implementation; Android and iOS answer
 * null. Tool hosts (`RemoteToolHost`) live in commonMain and are attached once
 * the database and the analyzer engine exist.
 */
interface RemoteSupportController {
    /** Live state for the banner, the dialog and the Settings row. */
    val status: StateFlow<RemoteSupportStatus>

    /** Start a session; the code in the result is what the owner reads to the engineer. */
    suspend fun start(consent: SupportConsent, durationS: Long, startedBy: SupportStarter): Result<SupportSession>

    /** End the running session now (owner pressed End, owner signed out, app closing). */
    suspend fun end(reason: String)

    /** Newest first. */
    suspend fun history(limit: Int): List<SupportAuditRow>

    /** Register a source of tools; may be called more than once, names must not clash. */
    fun attachToolHost(host: RemoteToolHost)

    /** Where audit rows go; attached once the database exists. */
    fun attachAuditStore(store: SupportAuditStore)
}

/** The engine on this platform, or null where there is none (Android, iOS). */
expect fun platformRemoteSupportController(): RemoteSupportController?

/** What the owner allowed for this session. Everything not listed is always allowed (diagnostics) or never (see contract). */
data class SupportConsent(
    /** Raw analyzer frames — sample ids and, on some analyzers, patient names. */
    val analyzerData: Boolean = false,
    /** Looking up patient records and results. */
    val records: Boolean = false,
    /** Pictures of the app window. */
    val screen: Boolean = false,
) {
    fun grants(kind: ConsentKind): Boolean = when (kind) {
        ConsentKind.ANALYZER_DATA -> analyzerData
        ConsentKind.RECORDS -> records
        ConsentKind.SCREEN -> screen
    }
}

enum class ConsentKind { ANALYZER_DATA, RECORDS, SCREEN }

/** Who started the session (the owner, or a staff member who typed the owner's PIN). */
data class SupportStarter(val staffId: String, val staffName: String)

data class SupportSession(
    val id: String,
    /** Eight Crockford characters, displayed as XXXX-XXXX. Never logged. */
    val code: String,
    /** Wall-clock start, for display only; expiry is tracked on a monotonic clock. */
    val startedAtMs: Long,
    val durationS: Long,
    val consent: SupportConsent,
    val startedBy: SupportStarter,
) {
    val displayCode: String get() = code.chunked(4).joinToString("-")
}

data class RemoteSupportStatus(
    val phase: Phase = Phase.OFF,
    val session: SupportSession? = null,
    /** Seconds left, 0 when no session. */
    val remainingS: Long = 0L,
    /** The engineer's bridge is connected through the relay. */
    val peerConnected: Boolean = false,
    /** Tool calls so far in this session (including refused ones). */
    val actions: Int = 0,
    /** Short, non-PHI, already worded for staff. */
    val lastError: String? = null,
    /** Clock difference to the relay in ms (client − server), once known. */
    val clockSkewMs: Long? = null,
) {
    enum class Phase { OFF, CONNECTING, WAITING_FOR_ENGINEER, ENGINEER_CONNECTED, RECONNECTING, ENDED_ERROR }

    val isActive: Boolean get() = phase != Phase.OFF && phase != Phase.ENDED_ERROR
}

// ── tools ──

/** One tool as the engineer's MCP client sees it. */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema (object) for the arguments, as a JSON string. */
    val inputSchemaJson: String,
    val readOnly: Boolean,
    val destructive: Boolean,
    /** Consent the owner must have given, or null when diagnostics-level. */
    val requires: ConsentKind? = null,
)

data class ToolCall(
    val name: String,
    /** The `arguments` object, as JSON text (may be "{}"). */
    val argsJson: String,
    val session: SupportSession,
    /** JSON-RPC id of the request, for logs. */
    val requestId: String,
)

sealed class ToolResult {
    /** MCP content array as JSON text, e.g. `[{"type":"text","text":"…"}]`. */
    data class Ok(val contentJson: String, val summaryForAudit: String) : ToolResult()
    /** Refused by policy (consent, busy analyzer, PHI table…): the reason is shown to the engineer. */
    data class Refused(val reason: String) : ToolResult()
    /**
     * The tool ran and failed; [message] is non-PHI and goes to the engineer.
     * A message that quotes a driver's own words can still carry what the
     * engineer typed (`db.query` and a patient name in a WHERE clause), so a
     * tool may give the audit row — which outlives the session — a fixed
     * [summaryForAudit] instead.
     */
    data class Failed(val message: String, val summaryForAudit: String = message) : ToolResult()
}

/** A source of tools. Implementations are pure with respect to the transport. */
interface RemoteToolHost {
    fun tools(): List<ToolSpec>
    suspend fun call(call: ToolCall): ToolResult
}

// ── audit ──

data class SupportAuditRow(
    val id: String,
    val sessionId: String,
    val atMs: Long,
    val tool: String,
    /** ≤ 200 chars, never PHI. */
    val summary: String,
    val outcome: Outcome,
    val ms: Long,
    val startedBy: String,
) {
    enum class Outcome { OK, REFUSED, FAILED }
}

interface SupportAuditStore {
    suspend fun sessionStarted(session: SupportSession)
    suspend fun sessionEnded(sessionId: String, endedAtMs: Long, reason: String)
    suspend fun append(row: SupportAuditRow)
    suspend fun recent(limit: Int): List<SupportAuditRow>
}
