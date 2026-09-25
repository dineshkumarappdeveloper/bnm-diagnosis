package com.bnm.lab.remote

import com.bnm.lab.BuildInfo
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.db.Instrument_log
import com.bnm.lab.diagnostics.DiagnosticsContext
import com.bnm.lab.instruments.INSTRUMENT_DRIVERS
import com.bnm.lab.instruments.InstrumentConfig
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.InstrumentStatus
import com.bnm.lab.instruments.InstrumentTransport
import com.bnm.lab.instruments.StoredInstrumentFrame
import com.bnm.lab.instruments.driverFor
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.update.ReleaseInfo
import com.bnm.lab.update.UpdateCheck
import com.bnm.lab.update.UpdateChecker
import com.bnm.lab.update.UpdateInstall
import com.bnm.lab.update.currentUpdatePlatform
import com.bnm.lab.update.downloadAndLaunchInstaller
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

/**
 * The tool vocabulary (v1) BNM's engineer can call on this copy of BNM Lab
 * during a support session — every tool except `app.screenshot`, which needs
 * the window and lives with the desktop session engine.
 *
 * Every tool declares its consent requirement, its read-only/destructive
 * annotation and writes a short PHI-free audit summary. Consent is checked
 * HERE as well as in the session engine (the engine refuses before calling
 * in; this refuses again so the host is safe on its own), and the PHI rules
 * hold whatever the consent: ids are masked unless "analyzer data" was
 * allowed, raw frames go through [FrameScrubber], patient tables need
 * "records", and audit summaries never carry SQL text, frame contents or
 * map contents.
 *
 * NEVER in v1, on purpose — do not add a tool for any of these: result entry,
 * claiming or discarding unmatched results, staff or PIN changes, licence
 * changes, SQL that writes, the file system, a shell, printing. Support fixes
 * the LINK; the bench owns the results.
 */
class RemoteTools(
    private val db: AppDatabase,
    private val engine: InstrumentEngine,
    private val labRepo: LabRepository,
    private val licenseManager: LicenseManager,
    private val controller: RemoteSupportController?,
    private val platform: RemoteToolPlatform,
    private val readOnlySql: ReadOnlySql?,
    private val updates: UpdateFlow,
    /** "dev" | "prod" from RemoteSupportKeys once the session engine ships it; null = unknown. */
    private val supportKeyLabel: () -> String?,
    private val json: Json,
    private val appVersion: String = BuildInfo.VERSION,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : RemoteToolHost {

    private class Tool(val spec: ToolSpec, val run: suspend (ToolCall, JsonObject) -> ToolResult)

    private val tools: List<Tool> = listOf(
        // ── read ──
        Tool(ToolSpec("lab.overview",
            "Lab name, edition, licence standing, app version, machine, uptime, database size, disk, record counts, " +
                "local IPv4 addresses, time zone and clock skew. The first call of every session.",
            NO_ARGS, readOnly = true, destructive = false), ::overview),
        Tool(ToolSpec("instruments.list",
            "Every configured analyzer with its connection settings, param map, verify_pending flag and live listener " +
                "status including counters (bytes, frames, parsed, applied, unmatched, ignored, ACKs, last error, peer IP).",
            NO_ARGS, readOnly = true, destructive = false), ::instrumentsList),
        Tool(ToolSpec("instruments.log",
            "Recent analyzer traffic log rows (newest first). Sample ids in summaries are masked to shape + last 4 " +
                "without analyzer-data consent. Raw frames come only with analyzer-data consent AND include_raw, " +
                "scrubbed of patient names/demographics; drivers without a scrubber give summaries only.",
            SCHEMA_LOG, readOnly = true, destructive = false), ::instrumentsLog),
        Tool(ToolSpec("instruments.unmatched",
            "Results waiting in the claim queue: which analyzer, when, the (masked) specimen id, why, and which " +
                "parameter keys arrived. Never the values.",
            NO_ARGS, readOnly = true, destructive = false), ::instrumentsUnmatched),
        Tool(ToolSpec("instruments.ports",
            "Serial ports on the lab PC (with which analyzer holds each) and the TCP ports the app listens on, " +
                "plus the PC's local IPs.",
            NO_ARGS, readOnly = true, destructive = false), ::instrumentsPorts),
        Tool(ToolSpec("instruments.probe",
            "Check a link without touching data: {serial_port} opens and closes the port (zero reads; refused while " +
                "an enabled analyzer owns it) or {tcp_host, tcp_port} connects from the lab PC with a 3 s timeout.",
            SCHEMA_PROBE, readOnly = true, destructive = false), ::instrumentsProbe),
        Tool(ToolSpec("instruments.dry_run",
            "Parse a pasted frame with the analyzer's driver and show the mapping it WOULD get — parsed keys and " +
                "units, whether an open order matches (lookup only, masked accession), mapped/unmapped keys and unit " +
                "mismatches. Writes nothing, never touches the claim queue. Specimen ids starting BNMTEST- never match.",
            SCHEMA_DRY_RUN, readOnly = true, destructive = false), ::instrumentsDryRun),
        Tool(ToolSpec("catalog.tests",
            "The lab's test catalog: id, code, name, active flag and each parameter's key and unit. No prices.",
            NO_ARGS, readOnly = true, destructive = false), ::catalogTests),
        Tool(ToolSpec("db.query",
            "Run ONE read-only SQL statement (SELECT / WITH / EXPLAIN / read PRAGMA) on a read-only connection, " +
                "max 200 rows, strings cut at 2000 chars. Tables holding patient records are refused unless the " +
                "owner allowed looking up records for this session.",
            SCHEMA_QUERY, readOnly = true, destructive = false), ::dbQuery),
        Tool(ToolSpec("logs.tail",
            "The last N lines (max 500) of the activity log on the lab PC (already redacted), optionally for one " +
                "day (yyyy-MM-dd). Without `lines` you also get the diagnostics summary (licence, counts, sync, analyzers).",
            SCHEMA_LOGS, readOnly = true, destructive = false), ::logsTail),
        Tool(ToolSpec("session.info",
            "This session: what the owner consented to, seconds remaining, actions so far, whether the engineer is connected.",
            NO_ARGS, readOnly = true, destructive = false), ::sessionInfo),
        // ── write ──
        Tool(ToolSpec("instruments.restart",
            "Restart one analyzer's listener ({instrument_id}) or all of them. Refused when a frame arrived on it in " +
                "the last 10 seconds unless force=true (a Mispa does not retransmit).",
            SCHEMA_RESTART, readOnly = false, destructive = true), ::instrumentsRestart),
        Tool(ToolSpec("instruments.set_config",
            "Create or update an analyzer's connection settings and param map. Validated, then saved and its listener " +
                "restarted. Changing the driver or the param map sets verify_pending: results from that analyzer go to " +
                "the claim queue until someone at the bench checks one known sample and presses Verified.",
            SCHEMA_SET_CONFIG, readOnly = false, destructive = true), ::instrumentsSetConfig),
        Tool(ToolSpec("instruments.delete",
            "Remove an analyzer's configuration and stop its listener. Its logged traffic and queued results stay.",
            SCHEMA_DELETE, readOnly = false, destructive = true), ::instrumentsDelete),
        Tool(ToolSpec("app.update.prefetch",
            "Check for a newer BNM Lab release, download and verify its installer on the lab PC and open it, so the " +
                "OS install prompt appears there. Someone at the PC must complete it; the app keeps running until " +
                "they close it. Allowed on the offline edition because the owner started this session.",
            SCHEMA_UPDATE, readOnly = false, destructive = true), ::appUpdatePrefetch),
        Tool(ToolSpec("session.end",
            "End this support session now, on both sides. The audit trail stays on the lab PC.",
            NO_ARGS, readOnly = false, destructive = true), ::sessionEnd),
    )
    private val byName = tools.associateBy { it.spec.name }

    override fun tools(): List<ToolSpec> = tools.map { it.spec }

    override suspend fun call(call: ToolCall): ToolResult {
        val tool = byName[call.name] ?: return ToolResult.Failed("Unknown tool '${call.name}'")
        tool.spec.requires?.let { kind ->
            if (!call.session.consent.grants(kind)) return ToolResult.Refused(consentMissing(kind))
        }
        val args = runCatching { parseArgs(call.argsJson) }
            .getOrElse { return ToolResult.Failed("Arguments must be a JSON object") }
        return try {
            tool.run(call, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolResult.Failed("${call.name} failed: ${e::class.simpleName}: ${e.message?.take(160) ?: "no detail"}")
        }
    }

    // ── read tools ──

    private suspend fun overview(call: ToolCall, @Suppress("UNUSED_PARAMETER") args: JsonObject): ToolResult {
        val st = licenseManager.state.value
        val standing = licenseManager.standing()
        val counts = runCatching { labRepo.tenantRowCounts() }.getOrNull()
        val status = controller?.status?.value
        val analyzers = engine.status.value.values
        val payload = buildJsonObject {
            put("lab", st.labName)
            put("edition", st.edition)
            putJsonObject("licence") {
                put("standing", standing.name.lowercase())
                put("mode", st.mode)
                put("expires_at", st.expiresAt)
                put("blocked", st.blocked)
                put("seats", st.seats)
                put("business_id", st.businessId)
            }
            put("app_version", appVersion)
            put("support_key", supportKeyLabel() ?: "unknown")
            put("environment", platform.environmentLine())
            put("uptime_s", platform.uptimeSeconds())
            put("db_size_bytes", platform.dbSizeBytes())
            put("disk_free_bytes", platform.diskFreeBytes())
            putJsonObject("records") {
                put("patients", counts?.patients)
                put("orders", counts?.orders)
                put("results", counts?.results)
                put("staff", counts?.staff)
                put("tests", counts?.tests)
            }
            putJsonArray("local_ipv4") { platform.localIpv4().forEach { add(it) } }
            put("timezone", platform.timezoneId())
            put("local_time", platform.localTimeIso())
            put("clock_skew_ms", status?.clockSkewMs)
            putJsonObject("analyzers") {
                put("configured", analyzers.size)
                put("listening", analyzers.count { it.state == "listening" })
                put("error", analyzers.count { it.state == "error" })
                put("off", analyzers.count { it.state == "off" })
            }
            // The backup controller is on another branch; say so rather than guess.
            put("backup", "not available in this build")
        }
        return ok(payload, "overview")
    }

    private suspend fun instrumentsList(call: ToolCall, @Suppress("UNUSED_PARAMETER") args: JsonObject): ToolResult {
        val rows = engine.listInstruments()
        val statuses = engine.status.value
        val payload = buildJsonArray {
            for (cfg in rows) addJsonObject {
                putConfig(cfg)
                putJsonObject("status") { putStatus(statuses[cfg.id]) }
            }
        }
        return ok(payload, "list ${rows.size} analyzer(s)")
    }

    private suspend fun instrumentsLog(call: ToolCall, args: JsonObject): ToolResult {
        val instrumentId = args.str("instrument_id")
        val sinceId = args.str("since_id")
        val limit = (args.int("limit") ?: 50).coerceIn(1, MAX_LOG_ROWS)
        val includeRaw = args.bool("include_raw") ?: false
        val analyzerData = call.session.consent.grants(ConsentKind.ANALYZER_DATA)
        val driverById = engine.listInstruments().associate { it.id to it.driver }

        var rows = recentLogRows()
        if (instrumentId != null) rows = rows.filter { it.instrument_id == instrumentId }
        if (sinceId != null) {
            val since = rows.firstOrNull { it.id == sinceId }
            if (since != null) rows = rows.filter { it.created_at > since.created_at || (it.created_at == since.created_at && it.id > since.id) }
        }
        val page = rows.take(limit)
        val rawShared = analyzerData && includeRaw
        val payload = buildJsonObject {
            put("count", page.size)
            put("newest_id", page.firstOrNull()?.id)
            put("ids_masked", !analyzerData)
            put("raw_included", rawShared)
            putJsonArray("rows") {
                for (r in page) addJsonObject {
                    put("id", r.id)
                    put("instrument_id", r.instrument_id)
                    put("instrument_name", r.instrument_name)
                    put("direction", r.direction)
                    put("summary", if (analyzerData) r.summary else FrameScrubber.maskIds(r.summary))
                    put("created_at", r.created_at)
                    if (rawShared && r.raw != null) {
                        val driver = r.instrument_id?.let { driverById[it] }
                        val scrubbed = driver?.let { FrameScrubber.scrubRaw(it, r.raw) }
                        put("raw", scrubbed ?: "(raw not shared: no scrubber for driver '${driver ?: "unknown"}')")
                    }
                }
            }
        }
        return ok(payload, "log ${page.size} row(s)" + (if (rawShared) " with raw" else " (summaries)"))
    }

    private suspend fun instrumentsUnmatched(call: ToolCall, @Suppress("UNUSED_PARAMETER") args: JsonObject): ToolResult {
        val analyzerData = call.session.consent.grants(ConsentKind.ANALYZER_DATA)
        val rows = db.instrumentsQueries.listUnmatched().executeAsList()
        val log = recentLogRows()
        val payload = buildJsonArray {
            for (r in rows) addJsonObject {
                put("id", r.id)
                put("instrument_id", r.instrument_id)
                put("received_at", r.received_at)
                put("specimen_id", r.specimen_id?.let { if (analyzerData) it else FrameScrubber.maskId(it) })
                val reason = queuedReason(log, r.instrument_id, r.received_at)
                put("reason", reason?.let { if (analyzerData) it else FrameScrubber.maskIds(it) })
                val stored = runCatching { json.decodeFromString(StoredInstrumentFrame.serializer(), r.payload_json) }.getOrNull()
                putJsonArray("param_keys") { stored?.params?.keys?.forEach { add(it) } }
                put("histograms", stored?.histograms?.size)
            }
        }
        return ok(payload, "unmatched ${rows.size} row(s)")
    }

    private suspend fun instrumentsPorts(call: ToolCall, @Suppress("UNUSED_PARAMETER") args: JsonObject): ToolResult {
        val cfgs = engine.listInstruments()
        val statuses = engine.status.value
        val payload = buildJsonObject {
            putJsonArray("serial") {
                for (p in platform.serialPorts()) addJsonObject {
                    put("name", p.name)
                    put("description", p.description)
                    put("held_by", cfgs.firstOrNull { it.enabled && it.transport == InstrumentTransport.SERIAL && it.serialPort == p.name }?.id)
                }
            }
            putJsonArray("tcp") {
                for (c in cfgs.filter { it.transport == InstrumentTransport.TCP }) addJsonObject {
                    put("instrument_id", c.id)
                    put("port", c.tcpPort)
                    put("enabled", c.enabled)
                    put("bound", statuses[c.id]?.state == "listening" && statuses[c.id]?.boundAt != null)
                }
            }
            putJsonArray("local_ipv4") { platform.localIpv4().forEach { add(it) } }
        }
        return ok(payload, "ports")
    }

    private suspend fun instrumentsProbe(call: ToolCall, args: JsonObject): ToolResult {
        val serial = args.str("serial_port")
        val host = args.str("tcp_host")
        val port = args.int("tcp_port")
        return when {
            serial != null -> {
                val owner = engine.listInstruments().firstOrNull {
                    it.enabled && it.transport == InstrumentTransport.SERIAL && it.serialPort == serial
                }
                if (owner != null) {
                    ToolResult.Refused("Port $serial is in use by the analyzer '${owner.name}' — probing it would break that link. Disable the analyzer first, or probe another port.")
                } else {
                    val problem = platform.probeSerial(serial)
                    ok(buildJsonObject {
                        put("serial_port", serial)
                        put("opened", problem == null)
                        put("problem", problem)
                    }, "probe serial $serial → ${if (problem == null) "opened" else "failed"}")
                }
            }
            host != null && port != null -> {
                if (port !in 1..65535) return ToolResult.Failed("tcp_port must be 1–65535")
                val (reachable, detail) = probeTcp(host, port)
                ok(buildJsonObject {
                    put("tcp_host", host)
                    put("tcp_port", port)
                    put("reachable", reachable)
                    put("detail", detail)
                }, "probe tcp $host:$port → ${if (reachable) "reachable" else "unreachable"}")
            }
            else -> ToolResult.Failed("Give either serial_port, or tcp_host and tcp_port")
        }
    }

    private suspend fun instrumentsDryRun(call: ToolCall, args: JsonObject): ToolResult {
        val id = args.str("instrument_id") ?: return ToolResult.Failed("instrument_id is required")
        val frame = args.str("frame_text")?.takeIf { it.isNotBlank() } ?: return ToolResult.Failed("frame_text is required")
        val cfg = engine.instrumentById(id) ?: return ToolResult.Failed("No analyzer with id $id")
        val r = engine.dryRun(cfg, frame)
        val payload = buildJsonObject {
            put("driver", r.driver)
            put("parsed", r.parsed)
            put("note", r.note)
            put("specimen_id", r.specimenIdMasked)
            putJsonArray("param_keys") { r.paramKeys.forEach { add(it) } }
            putJsonObject("units") { r.units.forEach { (k, v) -> put(k, v) } }
            putJsonArray("histograms") { r.histograms.forEach { add(it) } }
            put("would_match", r.wouldMatch)
            put("accession", r.accessionMasked)
            put("order_status", r.orderStatus)
            put("mapping_basis", r.mappingBasis)
            put("test", r.testName)
            putJsonObject("mapped") { r.mapped.forEach { (k, v) -> put(k, v) } }
            putJsonArray("unmapped") { r.unmapped.forEach { add(it) } }
            putJsonArray("unit_mismatches") { r.unitMismatches.forEach { add(it) } }
        }
        return ok(payload, "dry-run on ${cfg.name} · parsed=${r.parsed} would_match=${r.wouldMatch} · " +
            "${r.mapped.size} mapped ${r.unmapped.size} unmapped")
    }

    private suspend fun catalogTests(call: ToolCall, @Suppress("UNUSED_PARAMETER") args: JsonObject): ToolResult {
        val tests = labRepo.listTests(includeInactive = true)
        val payload = buildJsonArray {
            for (t in tests) addJsonObject {
                put("id", t.id)
                put("code", t.code)
                put("name", t.name)
                put("active", t.active)
                putJsonArray("parameters") {
                    for (p in t.parameters) addJsonObject { put("key", p.key); put("name", p.name); put("unit", p.unit) }
                }
            }
        }
        return ok(payload, "catalog ${tests.size} test(s)")
    }

    private suspend fun dbQuery(call: ToolCall, args: JsonObject): ToolResult {
        val sql = args.str("sql")?.takeIf { it.isNotBlank() } ?: return ToolResult.Failed("sql is required")
        val maxRows = DbQueryGuard.clampRows(args.int("max_rows"))
        val records = call.session.consent.grants(ConsentKind.RECORDS)
        when (val v = DbQueryGuard.check(sql, records)) {
            is DbQueryGuard.Verdict.Refused -> return ToolResult.Refused(v.reason)
            DbQueryGuard.Verdict.Allowed -> Unit
        }
        val ro = readOnlySql ?: return ToolResult.Failed("Database queries are not available on this device")
        val rs = ro.query(sql, maxRows)
        val payload = buildJsonObject {
            putJsonArray("columns") { rs.columns.forEach { add(it) } }
            putJsonArray("rows") {
                for (row in rs.rows) add(JsonArray(row.map { cell -> cell?.let { JsonPrimitive(DbQueryGuard.cut(it)) } ?: JsonNull }))
            }
            put("row_count", rs.rows.size)
            put("truncated", rs.truncated)
        }
        // Table names only — the SQL text itself may name a patient in a WHERE clause.
        val tables = DbQueryGuard.tablesMentioned(sql, KNOWN_TABLES)
        return ok(payload, "query ${rs.rows.size} row(s)" + (if (tables.isNotEmpty()) " · ${tables.joinToString(", ")}" else ""))
    }

    private suspend fun logsTail(call: ToolCall, args: JsonObject): ToolResult {
        val requested = args.int("lines")
        val lines = (requested ?: 200).coerceIn(1, MAX_LOG_LINES)
        val day = args.str("day")?.takeIf { DAY.matches(it) }
        val tail = platform.logTail(lines, day)
        val payload = buildJsonObject {
            put("day", day ?: "latest")
            put("lines", lines)
            put("log", tail ?: "(no activity log on this device)")
            if (requested == null) put("diagnostics", DiagnosticsContext.render())
        }
        return ok(payload, "logs tail $lines line(s)" + (day?.let { " for $it" } ?: ""))
    }

    private suspend fun sessionInfo(call: ToolCall, @Suppress("UNUSED_PARAMETER") args: JsonObject): ToolResult {
        val st = controller?.status?.value
        val s = call.session
        val payload = buildJsonObject {
            put("session_id", s.id)
            putJsonObject("consent") {
                put("analyzer_data", s.consent.analyzerData)
                put("records", s.consent.records)
                put("screen", s.consent.screen)
            }
            put("duration_s", s.durationS)
            put("remaining_s", st?.remainingS)
            put("actions", st?.actions)
            put("phase", st?.phase?.name?.lowercase())
            put("peer_connected", st?.peerConnected)
            put("started_by_name", s.startedBy.staffName)
        }
        return ok(payload, "session info")
    }

    // ── write tools ──

    private suspend fun instrumentsRestart(call: ToolCall, args: JsonObject): ToolResult {
        val id = args.str("instrument_id")
        val force = args.bool("force") ?: false
        val statuses = engine.status.value
        val cfgs = engine.listInstruments()
        if (id != null) {
            val cfg = cfgs.firstOrNull { it.id == id } ?: return ToolResult.Failed("No analyzer with id $id")
            val recent = recentFrameAge(statuses[id])
            if (recent != null && !force) {
                return ToolResult.Refused("A result arrived on '${cfg.name}' $recent s ago — restarting now could lose the next one (a Mispa does not retransmit). Wait, or pass force=true.")
            }
            engine.restart(id)
            return ok(buildJsonObject {
                put("instrument_id", id)
                putJsonObject("status") { putStatus(engine.status.value[id]) }
            }, "restart ${cfg.name}" + (if (force) " (forced)" else ""))
        }
        val busy = cfgs.filter { it.enabled }.mapNotNull { c -> recentFrameAge(statuses[c.id])?.let { c.name to it } }
        if (busy.isNotEmpty() && !force) {
            return ToolResult.Refused("Results arrived seconds ago on: " +
                busy.joinToString(", ") { "'${it.first}' (${it.second} s)" } + " — wait, or pass force=true.")
        }
        engine.restartAll()
        return ok(buildJsonObject {
            putJsonArray("statuses") {
                for ((iid, st) in engine.status.value) addJsonObject { put("instrument_id", iid); putStatus(st) }
            }
        }, "restart all (${cfgs.size})" + (if (force) " (forced)" else ""))
    }

    private suspend fun instrumentsSetConfig(call: ToolCall, args: JsonObject): ToolResult {
        val id = args.str("id")
        val existing = id?.let { engine.instrumentById(it) }
        if (id != null && existing == null) return ToolResult.Failed("No analyzer with id $id — omit id to create one")

        val name = args.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.name
            ?: return ToolResult.Failed("name is required")
        val driverKey = args.str("driver_key") ?: existing?.driver ?: return ToolResult.Failed("driver_key is required")
        val driver = driverFor(driverKey)
            ?: return ToolResult.Failed("Unknown driver '$driverKey' — known: ${INSTRUMENT_DRIVERS.joinToString { it.key }}")
        val transport = args.str("transport") ?: existing?.transport ?: return ToolResult.Failed("transport is required")
        if (transport != InstrumentTransport.SERIAL && transport != InstrumentTransport.TCP) {
            return ToolResult.Failed("transport must be 'serial' or 'tcp'")
        }
        if (driver.tcpOnly && transport != InstrumentTransport.TCP) {
            return ToolResult.Failed("${driver.label} needs the app's ACK, which only TCP can send — use transport 'tcp'")
        }
        val enabled = args.bool("enabled") ?: existing?.enabled ?: true
        val serialPort = (if (args.containsKey("serial_port")) args.str("serial_port") else existing?.serialPort)?.trim()?.ifEmpty { null }
        val baud = args.int("baud") ?: existing?.baud ?: driver.defaultBaud
        val tcpPort = if (args.containsKey("tcp_port")) args.int("tcp_port") else existing?.tcpPort
        val paramMapJson = if (args.containsKey("param_map_json")) args.str("param_map_json")?.trim()?.ifEmpty { null } else existing?.paramMapJson

        if (transport == InstrumentTransport.SERIAL) {
            if (serialPort == null) return ToolResult.Failed("serial_port is required for the serial transport")
            if (baud !in 300..4_000_000) return ToolResult.Failed("baud $baud is not a sensible serial rate")
        } else {
            if (tcpPort == null || tcpPort !in 1..65535) return ToolResult.Failed("tcp_port must be 1–65535 for the tcp transport")
        }
        if (paramMapJson != null) {
            val obj = runCatching { json.parseToJsonElement(paramMapJson).jsonObject }.getOrNull()
                ?: return ToolResult.Failed("param_map_json must be a JSON object of analyzer key → catalog parameter key")
            val bad = obj.entries.firstOrNull { (k, v) -> k.isBlank() || (v as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrBlank() }
            if (bad != null) return ToolResult.Failed("param_map_json values must be non-empty strings (offending key '${bad.key}')")
        }
        if (enabled) {
            val others = engine.listInstruments().filter { it.id != id && it.enabled }
            if (transport == InstrumentTransport.SERIAL) {
                others.firstOrNull { it.transport == InstrumentTransport.SERIAL && it.serialPort == serialPort }?.let {
                    return ToolResult.Failed("Serial port $serialPort is already used by the enabled analyzer '${it.name}'")
                }
            } else {
                others.firstOrNull { it.transport == InstrumentTransport.TCP && it.tcpPort == tcpPort }?.let {
                    return ToolResult.Failed("TCP port $tcpPort is already used by the enabled analyzer '${it.name}'")
                }
            }
        }

        val changed = buildList {
            if (existing == null) add("created") else {
                if (existing.name != name) add("name")
                if (existing.driver != driverKey) add("driver_key")
                if (existing.transport != transport) add("transport")
                if (existing.serialPort != serialPort) add("serial_port")
                if (existing.baud != baud) add("baud")
                if (existing.tcpPort != tcpPort) add("tcp_port")
                if (existing.enabled != enabled) add("enabled")
                if (existing.paramMapJson != paramMapJson) add("param_map_json")
            }
        }
        val needsVerify = existing == null || "driver_key" in changed || "param_map_json" in changed
        val cfg = InstrumentConfig(
            id = id ?: "", name = name, driver = driverKey, transport = transport, serialPort = serialPort,
            baud = baud, tcpPort = tcpPort, enabled = enabled, paramMapJson = paramMapJson,
            createdAt = existing?.createdAt ?: "", updatedAt = existing?.updatedAt ?: "",
            verifyPending = (existing?.verifyPending ?: false) || needsVerify,
        )
        val savedId = engine.saveInstrument(cfg)
        val after = engine.instrumentById(savedId)
        return ok(buildJsonObject {
            put("id", savedId)
            putJsonArray("changed") { changed.forEach { add(it) } }
            put("verify_pending", after?.verifyPending ?: cfg.verifyPending)
            putJsonObject("status") { putStatus(engine.status.value[savedId]) }
        }, "set_config ${cfg.name}: " + changed.ifEmpty { listOf("no change") }.joinToString(", ") +
            (if (needsVerify) " · verify_pending" else ""))
    }

    private suspend fun instrumentsDelete(call: ToolCall, args: JsonObject): ToolResult {
        val id = args.str("id") ?: return ToolResult.Failed("id is required")
        val cfg = engine.instrumentById(id) ?: return ToolResult.Failed("No analyzer with id $id")
        engine.deleteInstrument(id)
        return ok(buildJsonObject { put("id", id); put("deleted", true) }, "delete ${cfg.name}")
    }

    private suspend fun appUpdatePrefetch(call: ToolCall, args: JsonObject): ToolResult {
        val wanted = args.str("version")?.trim()?.removePrefix("v")?.ifEmpty { null }
        return when (val check = updates.check()) {
            is UpdateCheck.Failed -> ToolResult.Failed(check.message)
            is UpdateCheck.UpToDate -> ok(buildJsonObject {
                put("status", "up_to_date")
                put("version", check.version)
            }, "update prefetch: up to date (${check.version})")
            is UpdateCheck.Available -> {
                val release = check.release
                if (wanted != null && wanted != release.version) {
                    return ToolResult.Refused("Only the newest release can be installed: ${release.version} is published, $wanted was asked for")
                }
                val url = release.downloadUrl ?: return ToolResult.Failed("Release ${release.version} has no installer for this platform")
                val fileName = url.substringAfterLast('/')
                val sha = updates.checksum(release, fileName)
                val result = updates.download(url, fileName, sha)
                val (status, detail) = when (result) {
                    is UpdateInstall.LaunchedQuitNow -> "installer_opened" to result.path
                    is UpdateInstall.DownloadedOnly -> "downloaded_only" to "${result.reason} ${result.path}"
                    is UpdateInstall.Failed -> "failed" to result.message
                    UpdateInstall.NotSupported -> "not_supported" to "Updates come from the app store on this device"
                }
                ok(buildJsonObject {
                    put("status", status)
                    put("version", release.version)
                    put("verified", sha != null)
                    put("detail", detail)
                    put("next", "Someone at the lab PC must close BNM Lab and follow the installer; the app keeps running until they do.")
                }, "update prefetch ${release.version} → $status")
            }
        }
    }

    private suspend fun sessionEnd(call: ToolCall, @Suppress("UNUSED_PARAMETER") args: JsonObject): ToolResult {
        val c = controller ?: return ToolResult.Failed("No session engine on this device")
        c.end("Ended by the BNM engineer")
        return ok(buildJsonObject { put("ended", true) }, "session ended by engineer")
    }

    // ── helpers ──

    private fun ok(payload: JsonElement, summary: String): ToolResult.Ok {
        val content = buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", json.encodeToString(JsonElement.serializer(), payload))
            }
        }
        return ToolResult.Ok(json.encodeToString(JsonElement.serializer(), content), summary.take(200))
    }

    private fun parseArgs(argsJson: String): JsonObject =
        if (argsJson.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(argsJson).jsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    private fun recentLogRows(): List<Instrument_log> = db.instrumentsQueries.recentLog(LOG_WINDOW).executeAsList()

    /** The "Queued for manual claim — <reason>" row written right after this result was stored. */
    private fun queuedReason(log: List<Instrument_log>, instrumentId: String?, receivedAt: String): String? =
        log.filter { it.instrument_id == instrumentId && it.summary.startsWith(QUEUED_PREFIX) && it.created_at >= receivedAt }
            .minByOrNull { it.created_at }
            ?.takeIf { runCatching { Instant.parse(it.created_at).toEpochMilliseconds() - Instant.parse(receivedAt).toEpochMilliseconds() }.getOrDefault(Long.MAX_VALUE) < 10_000 }
            ?.summary?.removePrefix(QUEUED_PREFIX)

    /** Seconds since the last parsed frame when that is under [BUSY_WINDOW_MS]; else null. */
    private fun recentFrameAge(status: InstrumentStatus?): Long? {
        val at = status?.lastFrameAt ?: return null
        val ms = runCatching { Instant.parse(at).toEpochMilliseconds() }.getOrNull() ?: return null
        val age = nowMs() - ms
        return if (age in 0 until BUSY_WINDOW_MS) age / 1000 else null
    }

    private suspend fun probeTcp(host: String, port: Int): Pair<Boolean, String> {
        val selector = SelectorManager(Dispatchers.Default)
        val t0 = nowMs()
        return try {
            withTimeout(TCP_PROBE_TIMEOUT_MS) { aSocket(selector).tcp().connect(host, port).close() }
            true to "connected in ${nowMs() - t0} ms"
        } catch (e: TimeoutCancellationException) {
            false to "no answer within ${TCP_PROBE_TIMEOUT_MS / 1000} s"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false to (e.message?.take(120) ?: e::class.simpleName ?: "connect failed")
        } finally {
            runCatching { selector.close() }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putConfig(cfg: InstrumentConfig) {
        put("id", cfg.id)
        put("name", cfg.name)
        put("driver_key", cfg.driver)
        put("transport", cfg.transport)
        put("serial_port", cfg.serialPort)
        put("baud", cfg.baud)
        put("tcp_port", cfg.tcpPort)
        put("enabled", cfg.enabled)
        put("param_map_json", cfg.paramMapJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
        put("verify_pending", cfg.verifyPending)
        put("updated_at", cfg.updatedAt)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStatus(st: InstrumentStatus?) {
        if (st == null) { put("state", "unknown"); return }
        put("state", st.state)
        put("detail", st.detail)
        put("last_frame_at", st.lastFrameAt)
        put("bound_at", st.boundAt)
        put("peer_ip", st.peerIp)
        put("bytes_in", st.bytesIn)
        put("frames_in", st.framesIn)
        put("frames_parsed", st.framesParsed)
        put("frames_applied", st.framesApplied)
        put("frames_unmatched", st.framesUnmatched)
        put("frames_ignored", st.framesIgnored)
        put("acks_sent", st.acksSent)
        put("last_error", st.lastError)
        put("last_error_at", st.lastErrorAt)
    }

    private fun consentMissing(kind: ConsentKind): String = when (kind) {
        ConsentKind.ANALYZER_DATA -> "The owner has not shared analyzer data in this session."
        ConsentKind.RECORDS -> "The owner has not allowed looking up records in this session."
        ConsentKind.SCREEN -> "The owner has not allowed screen view in this session."
    }

    companion object {
        const val MAX_LOG_ROWS = 200
        const val MAX_LOG_LINES = 500
        /** instrument_log is trimmed to this many rows by the engine, so one fetch is the whole table. */
        private const val LOG_WINDOW = 500L
        private const val BUSY_WINDOW_MS = 10_000L
        private const val TCP_PROBE_TIMEOUT_MS = 3_000L
        private const val QUEUED_PREFIX = "Queued for manual claim — "
        private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

        /** For the audit summary of db.query: which of these the SQL named. */
        private val KNOWN_TABLES = listOf(
            "patients", "referrers", "lab_tests", "lab_panels", "lab_orders", "lab_order_tests", "lab_results",
            "accession_series", "referrer_rates", "emr_inbox", "staff", "referrer_commission_rates",
            "referrer_payouts", "lab_settings", "lab_reports", "instruments", "instrument_log",
            "instrument_results", "lab_result_graphs", "ecom_entity", "counter_series", "billing_outbox",
            "sync_state", "support_sessions", "support_audit", "sqlite_master", "sqlite_schema",
        )

        private const val NO_ARGS = """{"type":"object","properties":{},"additionalProperties":false}"""
        private const val SCHEMA_LOG = """{"type":"object","properties":{
            "instrument_id":{"type":"string","description":"Only this analyzer"},
            "since_id":{"type":"string","description":"Only rows newer than this row id (from a previous call's newest_id)"},
            "limit":{"type":"integer","minimum":1,"maximum":200,"default":50},
            "include_raw":{"type":"boolean","default":false,"description":"Scrubbed raw frames — needs analyzer-data consent"}},
            "additionalProperties":false}"""
        private const val SCHEMA_PROBE = """{"type":"object","properties":{
            "serial_port":{"type":"string","description":"e.g. COM3 or /dev/tty.usbserial-110"},
            "tcp_host":{"type":"string","description":"The analyzer's IP or host name"},
            "tcp_port":{"type":"integer","minimum":1,"maximum":65535}},
            "additionalProperties":false}"""
        private const val SCHEMA_DRY_RUN = """{"type":"object","required":["instrument_id","frame_text"],"properties":{
            "instrument_id":{"type":"string"},
            "frame_text":{"type":"string","description":"One frame as the analyzer sends it (MLLP/HL7 or Mispa $$$…###); use a BNMTEST- specimen id to be sure it never matches a real order"}},
            "additionalProperties":false}"""
        private const val SCHEMA_QUERY = """{"type":"object","required":["sql"],"properties":{
            "sql":{"type":"string","description":"One SELECT / WITH / EXPLAIN / read-only PRAGMA"},
            "max_rows":{"type":"integer","minimum":1,"maximum":200,"default":50}},
            "additionalProperties":false}"""
        private const val SCHEMA_LOGS = """{"type":"object","properties":{
            "lines":{"type":"integer","minimum":1,"maximum":500,"description":"Omit to also get the diagnostics summary"},
            "day":{"type":"string","pattern":"^\\d{4}-\\d{2}-\\d{2}$","description":"yyyy-MM-dd; default = the newest log file"}},
            "additionalProperties":false}"""
        private const val SCHEMA_RESTART = """{"type":"object","properties":{
            "instrument_id":{"type":"string","description":"Omit to restart every analyzer"},
            "force":{"type":"boolean","default":false,"description":"Restart even if a result arrived in the last 10 s"}},
            "additionalProperties":false}"""
        private const val SCHEMA_SET_CONFIG = """{"type":"object","required":["name","driver_key","transport"],"properties":{
            "id":{"type":"string","description":"Existing analyzer to update; omit to create"},
            "name":{"type":"string"},
            "driver_key":{"type":"string","enum":["mispa_count_x","mindray_hl7"]},
            "transport":{"type":"string","enum":["serial","tcp"]},
            "serial_port":{"type":"string"},
            "baud":{"type":"integer"},
            "tcp_port":{"type":"integer","minimum":1,"maximum":65535},
            "enabled":{"type":"boolean","default":true},
            "param_map_json":{"type":"string","description":"JSON object: analyzer key → catalog parameter key, e.g. {\"GRAN%\":\"neut\"}"}},
            "additionalProperties":false}"""
        private const val SCHEMA_DELETE = """{"type":"object","required":["id"],"properties":{"id":{"type":"string"}},"additionalProperties":false}"""
        private const val SCHEMA_UPDATE = """{"type":"object","properties":{
            "version":{"type":"string","description":"Expected version, e.g. 1.13.0 — refused if it is not the newest release"}},
            "additionalProperties":false}"""
    }
}

/**
 * The update path behind `app.update.prefetch`, split out so a test can run
 * the tool without GitHub. [Real] is the same three steps Settings ▸ App uses.
 */
interface UpdateFlow {
    suspend fun check(): UpdateCheck
    suspend fun checksum(release: ReleaseInfo, assetName: String): String?
    suspend fun download(url: String, fileName: String, sha256: String?): UpdateInstall

    object Real : UpdateFlow {
        override suspend fun check(): UpdateCheck {
            val c = ApiClient.create()
            return try { UpdateChecker.check(c, currentUpdatePlatform()) } finally { c.close() }
        }
        override suspend fun checksum(release: ReleaseInfo, assetName: String): String? {
            val c = ApiClient.create()
            return try { UpdateChecker.fetchChecksum(c, release.checksumsUrl, assetName) } finally { c.close() }
        }
        override suspend fun download(url: String, fileName: String, sha256: String?): UpdateInstall =
            downloadAndLaunchInstaller(url, fileName, sha256, onProgress = { _, _ -> })
    }
}

/**
 * The tool host App.kt attaches to the session engine once the database and
 * the analyzer engine exist. Everything platform-bound is injectable so the
 * same host runs in a test against a temp database.
 */
fun remoteToolHost(
    database: AppDatabase,
    instrumentEngine: InstrumentEngine,
    labRepo: LabRepository,
    licenseManager: LicenseManager,
    controller: RemoteSupportController? = null,
    platform: RemoteToolPlatform = platformRemoteToolPlatform(),
    readOnlySql: ReadOnlySql? = platformReadOnlySql(),
    updates: UpdateFlow = UpdateFlow.Real,
    supportKeyLabel: () -> String? = { null },
    json: Json = ApiClient.json,
): RemoteToolHost = RemoteTools(
    db = database, engine = instrumentEngine, labRepo = labRepo, licenseManager = licenseManager,
    controller = controller, platform = platform, readOnlySql = readOnlySql, updates = updates,
    supportKeyLabel = supportKeyLabel, json = json,
)
