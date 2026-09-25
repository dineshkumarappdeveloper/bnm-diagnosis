package com.bnm.lab.instruments

import com.bnm.lab.remote.FrameScrubber

/**
 * Analyzer link check — the checklist that says WHICH step is blocking.
 *
 * The owner's situation that produced this: BNM Lab on a lab PC shows the TCP
 * port LISTENING in netstat, the analyzer ran a sample, nothing arrived, and
 * nobody at the site could tell where it stopped. [LinkCheck.evaluate] is a
 * PURE function of what the app already knows — the instrument config, the
 * live [InstrumentStatus] counters, the newest traffic-log rows and a snapshot
 * of the PC ([LinkFacts]: addresses, serial ports, firewall, ping) — and turns
 * it into ordered rows, each OK / BLOCKED / WAITING / UNKNOWN / SKIPPED, with a
 * plain-English hint under the row that blocks, plus one verdict line.
 *
 * Nothing here touches the OS, the database or the network: the screen gathers
 * facts through [LinkEnvironment] and hands them in, so every transition is
 * table-testable. Output is PHI-free by construction — sample ids and
 * accessions quoted from log rows go through [FrameScrubber.maskIds] — because
 * "Copy report" sends it to BNM over WhatsApp.
 */
enum class LinkState { OK, BLOCKED, WAITING, UNKNOWN, SKIPPED }

/** What the panel can offer under a row, beyond reading the hint. */
enum class LinkAction {
    ADD_FIREWALL_RULE,
    ENTER_ANALYZER_HOST,
    PRESS_VERIFIED,
    ASSIGN_WAITING_RESULTS,
    CHANGE_PORT,
    CHOOSE_SERIAL_PORT,
}

data class LinkCheckRow(
    /** Stable key: listening, address, firewall, reachable, connected, understood, matched, port_present, port_open, settings. */
    val step: String,
    val state: LinkState,
    val title: String,
    val detail: String,
    val hint: String? = null,
    val action: LinkAction? = null,
)

data class LinkCheckReport(
    val rows: List<LinkCheckRow>,
    val verdict: String,
    val verdictState: LinkState,
) {
    val blockedAt: LinkCheckRow? get() = rows.firstOrNull { it.state == LinkState.BLOCKED }
}

/** One traffic-log row as the evaluator sees it (newest first in [LinkLogSummaries.of]). */
data class LinkLogRow(val direction: String, val summary: String, val at: String)

data class LogLine(val summary: String, val at: String)

/** The newest rows of each kind for ONE instrument. */
data class LinkLogSummaries(
    val lastRx: LogLine? = null,
    val lastInfo: LogLine? = null,
    val lastError: LogLine? = null,
    /** Newest "Queued for manual claim — <reason>" row. */
    val lastQueued: LogLine? = null,
    /** Newest "Applied n/m params to <accession> · <test>" row. */
    val lastApplied: LogLine? = null,
    /** Newest "Connection closed with N unframed bytes" row — bytes that never framed. */
    val lastUnframed: LogLine? = null,
) {
    companion object {
        const val QUEUED_PREFIX = "Queued for manual claim"
        const val APPLIED_PREFIX = "Applied "
        const val UNFRAMED_MARK = "unframed bytes"

        /** [rows] newest first. */
        fun of(rows: List<LinkLogRow>): LinkLogSummaries {
            fun first(pred: (LinkLogRow) -> Boolean) = rows.firstOrNull(pred)?.let { LogLine(it.summary, it.at) }
            return LinkLogSummaries(
                lastRx = first { it.direction == "rx" },
                lastInfo = first { it.direction == "info" },
                lastError = first { it.direction == "error" },
                lastQueued = first { it.direction == "info" && it.summary.startsWith(QUEUED_PREFIX) },
                lastApplied = first { it.direction == "info" && it.summary.startsWith(APPLIED_PREFIX) },
                lastUnframed = first { it.direction == "info" && it.summary.contains(UNFRAMED_MARK) },
            )
        }
    }
}

/**
 * What `netsh advfirewall` said about THIS instrument's port. [applicable] is
 * false off Windows (row SKIPPED); [known] is false when the command failed
 * or its output was not understood — a non-English Windows prints localised
 * headings — and the row is UNKNOWN rather than a guess.
 */
data class FirewallFacts(
    val applicable: Boolean,
    val known: Boolean,
    val enabled: Boolean? = null,
    val profile: String? = null,
    /** Name of an enabled inbound allow rule for TCP port N (or any port). */
    val ruleByPort: String? = null,
    /** Name of an enabled inbound allow rule for the running BNM Lab program. */
    val ruleByProgram: String? = null,
    val note: String? = null,
) {
    val hasRule: Boolean get() = ruleByPort != null || ruleByProgram != null

    companion object {
        val NOT_APPLICABLE = FirewallFacts(applicable = false, known = true)
        fun unknown(note: String) = FirewallFacts(applicable = true, known = false, note = note)
    }
}

data class PingResult(val reachable: Boolean, val detail: String)

/**
 * A snapshot of the PC for one instrument. Gathered outside the evaluator
 * ([LinkEnvironment]); null members mean "not read (yet)".
 */
data class LinkFacts(
    val os: String,
    val isWindows: Boolean,
    /** false on Android/iOS: network, serial and firewall answers are "unknown", never "absent". */
    val environmentKnown: Boolean = true,
    val localIpv4: List<String> = emptyList(),
    val serialPorts: List<String> = emptyList(),
    /** For this instrument's TCP port. */
    val firewall: FirewallFacts? = null,
    /** For this instrument's [InstrumentConfig.analyzerHost]. */
    val ping: PingResult? = null,
)

object LinkCheck {

    fun evaluate(
        cfg: InstrumentConfig,
        status: InstrumentStatus?,
        logs: LinkLogSummaries,
        facts: LinkFacts,
    ): LinkCheckReport {
        val rows = ArrayList<LinkCheckRow>(8)
        val driverLabel = driverFor(cfg.driver)?.label ?: cfg.driver
        when (cfg.transport) {
            InstrumentTransport.SERIAL -> {
                rows += serialPortPresent(cfg, facts)
                rows += serialPortOpen(cfg, status)
                rows += serialSettings(cfg)
            }
            else -> {
                rows += listening(cfg, status)
                rows += address(cfg, facts)
                rows += firewall(cfg, status, facts)
                rows += reachable(cfg, status, facts)
            }
        }
        rows += connected(cfg, status, logs, facts)
        rows += understood(cfg, status, logs, driverLabel)
        rows += matched(cfg, status, logs)
        return LinkCheckReport(rows, verdictOf(rows, status, logs), verdictStateOf(rows, status))
    }

    // ── TCP steps ──

    private fun listening(cfg: InstrumentConfig, status: InstrumentStatus?): LinkCheckRow {
        val port = cfg.tcpPort
        val title = "BNM Lab is listening on port ${port ?: "?"}"
        if (!cfg.enabled) return LinkCheckRow("listening", LinkState.BLOCKED, title,
            "This analyzer is switched off in BNM Lab",
            "Turn the analyzer's switch on in the list above, then check again.")
        if (port == null || port !in 1..65535) return LinkCheckRow("listening", LinkState.BLOCKED, title,
            "No listen port set",
            "Edit the analyzer and enter the port the analyzer's LIS/host settings send to (5500 is the usual default).",
            LinkAction.CHANGE_PORT)
        if (status == null) return LinkCheckRow("listening", LinkState.UNKNOWN, title, "Starting…")
        return when (status.state) {
            "listening" -> if (status.boundAt != null)
                LinkCheckRow("listening", LinkState.OK, title, "Open since ${time(status.boundAt)}")
            else LinkCheckRow("listening", LinkState.WAITING, title, "Opening port $port…")
            "error" -> {
                val detail = status.detail ?: "Could not open port $port"
                val inUse = detail.contains("in use", ignoreCase = true) || detail.contains("bind", ignoreCase = true)
                LinkCheckRow("listening", LinkState.BLOCKED, title, detail,
                    if (inUse) "Another program on this PC already uses port $port (often the analyzer vendor's own software). " +
                        "Close that program, or change the port here AND in the analyzer's LIS settings to a free one such as ${port + 1}."
                    else "The port could not be opened. Check again in a few seconds — BNM Lab retries by itself; " +
                        "if it stays blocked, restart BNM Lab.",
                    LinkAction.CHANGE_PORT)
            }
            else -> LinkCheckRow("listening", LinkState.BLOCKED, title, status.detail ?: "Stopped",
                "The listener is stopped. Toggle the analyzer off and on to start it.")
        }
    }

    private fun address(cfg: InstrumentConfig, facts: LinkFacts): LinkCheckRow {
        val title = "This PC's address"
        if (!facts.environmentKnown) return LinkCheckRow("address", LinkState.UNKNOWN, title,
            "Can't read network addresses on this device — run the link check on the lab PC.")
        if (facts.localIpv4.isEmpty()) return LinkCheckRow("address", LinkState.BLOCKED, title,
            "No network address — this PC isn't on a network",
            "Connect the PC to the same network (cable or Wi-Fi) as the analyzer, then check again.")
        val ip = facts.localIpv4.first()
        val more = if (facts.localIpv4.size > 1) " (also ${facts.localIpv4.drop(1).joinToString(", ")})" else ""
        return LinkCheckRow("address", LinkState.OK, title,
            "$ip$more — on the analyzer's LIS settings use $ip, port ${cfg.tcpPort ?: "?"}, TCP client mode")
    }

    private fun firewall(cfg: InstrumentConfig, status: InstrumentStatus?, facts: LinkFacts): LinkCheckRow {
        val title = "Windows Firewall"
        val port = cfg.tcpPort ?: 0
        if (!facts.isWindows) return LinkCheckRow("firewall", LinkState.SKIPPED, title,
            "Not Windows (${facts.os}) — nothing to check here")
        val fw = facts.firewall
        if (fw != null && !fw.applicable) return LinkCheckRow("firewall", LinkState.SKIPPED, title, "Not applicable")
        val bytes = status?.bytesIn ?: 0L
        if (fw == null || !fw.known) {
            // Bytes have already crossed: netsh's silence (or a localised Windows)
            // cannot make the firewall the blocker, so this is an answer, not a gap.
            if (bytes > 0L) return LinkCheckRow("firewall", LinkState.OK, title,
                "Not read${fw?.note?.let { " ($it)" } ?: ""} — but the analyzer has already reached this PC, so it isn't blocking")
            if (fw == null) return LinkCheckRow("firewall", LinkState.UNKNOWN, title, "Checking…")
            return LinkCheckRow("firewall", LinkState.UNKNOWN, title,
                "Could not read the firewall${fw.note?.let { " — $it" } ?: ""}",
                "Check by hand: Windows Security ▸ Firewall & network protection ▸ Allow an app through firewall — " +
                    "BNM Lab must be ticked, or an inbound rule must allow TCP port $port.",
                LinkAction.ADD_FIREWALL_RULE)
        }
        val profile = fw.profile?.let { " ($it)" } ?: ""
        if (fw.enabled == false) return LinkCheckRow("firewall", LinkState.OK, title, "Off$profile — not blocking")
        return when {
            fw.ruleByPort != null -> LinkCheckRow("firewall", LinkState.OK, title,
                "On$profile · inbound rule \"${fw.ruleByPort}\" allows port $port")
            fw.ruleByProgram != null -> LinkCheckRow("firewall", LinkState.OK, title,
                "On$profile · inbound rule \"${fw.ruleByProgram}\" allows the BNM Lab program")
            bytes > 0L -> LinkCheckRow("firewall", LinkState.OK, title,
                "On$profile, no rule found for port $port — but the analyzer has already reached this PC, so it isn't blocking")
            else -> LinkCheckRow("firewall", LinkState.BLOCKED, title,
                "On$profile — no inbound rule allows port $port or the BNM Lab program",
                "Windows is dropping the analyzer's connection before BNM Lab sees it. Press \"Add firewall rule\" " +
                    "(Windows asks for permission), or in Windows Security allow BNM Lab through the firewall for Private networks.",
                LinkAction.ADD_FIREWALL_RULE)
        }
    }

    private fun reachable(cfg: InstrumentConfig, status: InstrumentStatus?, facts: LinkFacts): LinkCheckRow {
        val title = "Analyzer reachable from this PC"
        val host = cfg.analyzerHost?.trim().orEmpty()
        if (host.isEmpty()) return LinkCheckRow("reachable", LinkState.UNKNOWN, title,
            "Not checked — the analyzer's IP address isn't entered",
            "Edit the analyzer and enter its IP address (shown in the analyzer's network settings) to check it answers ping.",
            LinkAction.ENTER_ANALYZER_HOST)
        if (!facts.environmentKnown) return LinkCheckRow("reachable", LinkState.UNKNOWN, title,
            "Can't ping from this device — run the link check on the lab PC.")
        val ping = facts.ping ?: return LinkCheckRow("reachable", LinkState.UNKNOWN, title, "Pinging $host…")
        if (ping.reachable) return LinkCheckRow("reachable", LinkState.OK, title, "$host answers ping (${ping.detail})")
        val bytes = status?.bytesIn ?: 0L
        if (bytes > 0L) return LinkCheckRow("reachable", LinkState.OK, title,
            "$host doesn't answer ping, but the analyzer has connected to this PC — some analyzers ignore ping")
        val ip = facts.localIpv4.firstOrNull() ?: "this PC"
        return LinkCheckRow("reachable", LinkState.BLOCKED, title, "No answer from $host (${ping.detail})",
            "Check the analyzer's network cable and that its IP is on the same network as $ip (same first three numbers). " +
                "If the address changed, correct it here.")
    }

    // ── Serial steps ──

    private fun serialPortPresent(cfg: InstrumentConfig, facts: LinkFacts): LinkCheckRow {
        val port = cfg.serialPort?.trim().orEmpty()
        val title = "Serial port ${port.ifEmpty { "?" }} is present"
        if (!cfg.enabled) return LinkCheckRow("port_present", LinkState.BLOCKED, title,
            "This analyzer is switched off in BNM Lab", "Turn the analyzer's switch on in the list above, then check again.")
        if (port.isEmpty()) return LinkCheckRow("port_present", LinkState.BLOCKED, title, "No serial port chosen",
            "Edit the analyzer and pick the COM port the analyzer's cable is plugged into.", LinkAction.CHOOSE_SERIAL_PORT)
        if (!facts.environmentKnown) return LinkCheckRow("port_present", LinkState.UNKNOWN, title,
            "Serial ports can only be checked on the lab PC.")
        val present = facts.serialPorts.any { it.equals(port, ignoreCase = true) }
        if (present) return LinkCheckRow("port_present", LinkState.OK, title,
            "Found · ports on this PC: ${facts.serialPorts.joinToString(", ")}")
        val others = if (facts.serialPorts.isEmpty()) "no serial ports at all" else facts.serialPorts.joinToString(", ")
        return LinkCheckRow("port_present", LinkState.BLOCKED, title, "$port not found — this PC has $others",
            "Plug in the USB-to-serial cable (try another USB socket). If Windows gave it a new name, " +
                "edit the analyzer and pick the port that appears.", LinkAction.CHOOSE_SERIAL_PORT)
    }

    private fun serialPortOpen(cfg: InstrumentConfig, status: InstrumentStatus?): LinkCheckRow {
        val port = cfg.serialPort?.trim().orEmpty().ifEmpty { "the port" }
        val title = "Port opened by BNM Lab"
        if (!cfg.enabled) return LinkCheckRow("port_open", LinkState.SKIPPED, title, "Analyzer is switched off")
        if (status == null) return LinkCheckRow("port_open", LinkState.UNKNOWN, title, "Starting…")
        return when (status.state) {
            "listening" -> LinkCheckRow("port_open", LinkState.OK, title,
                "Open since ${time(status.boundAt)} · ${status.detail ?: port}")
            "error" -> LinkCheckRow("port_open", LinkState.BLOCKED, title, status.detail ?: "Could not open $port",
                "Is another program holding $port — the analyzer vendor's software, a terminal tool? Close it and check again; " +
                    "BNM Lab retries by itself every few seconds. If the cable was unplugged, plug it back in.")
            else -> LinkCheckRow("port_open", LinkState.BLOCKED, title, status.detail ?: "Stopped",
                "The listener is stopped. Toggle the analyzer off and on to start it.")
        }
    }

    private fun serialSettings(cfg: InstrumentConfig): LinkCheckRow = LinkCheckRow("settings", LinkState.OK,
        "Port settings",
        "${cfg.baud} baud, 8 data bits, no parity, 1 stop bit (8-N-1), no flow control — " +
            "the analyzer's serial/LIS settings must say the same, or only garbage arrives")

    // ── shared steps ──

    private fun connected(cfg: InstrumentConfig, status: InstrumentStatus?, logs: LinkLogSummaries, facts: LinkFacts): LinkCheckRow {
        val title = "Analyzer has connected"
        val bytes = status?.bytesIn ?: 0L
        if (bytes > 0L) {
            val peer = status?.peerIp?.takeIf { it.isNotBlank() && !isLoopback(it) }
            val last = logs.lastRx?.at ?: status?.lastFrameAt
            return LinkCheckRow("connected", LinkState.OK, title,
                "${bytes.formatBytes()} received" + (peer?.let { " from $it" } ?: "") + (last?.let { " · last ${time(it)}" } ?: ""))
        }
        val since = status?.boundAt?.let { " since ${time(it)}" } ?: ""
        val where = if (cfg.transport == InstrumentTransport.SERIAL) cfg.serialPort ?: "the port"
            else (facts.localIpv4.firstOrNull() ?: "this PC's IP") + " port ${cfg.tcpPort ?: "?"}"
        val hint = if (cfg.transport == InstrumentTransport.SERIAL)
            "On the analyzer, press Send / Transmit (and turn on auto-transmit so every sample is sent). Then run a sample."
        else "On the analyzer's LIS / host settings, set the host to $where, TCP client mode, and enable auto-transmit. " +
            "Then press Send / Transmit for the last sample, or run one."
        return LinkCheckRow("connected", LinkState.WAITING, title, "Nothing received$since", hint)
    }

    private fun understood(cfg: InstrumentConfig, status: InstrumentStatus?, logs: LinkLogSummaries, driverLabel: String): LinkCheckRow {
        val title = "Frames understood by the driver"
        val bytes = status?.bytesIn ?: 0L
        val framesIn = status?.framesIn ?: 0L
        val parsed = status?.framesParsed ?: 0L
        val ignored = status?.framesIgnored ?: 0L
        if (bytes == 0L) return LinkCheckRow("understood", LinkState.WAITING, title, "No data yet")
        if (framesIn == 0L) {
            val unframed = logs.lastUnframed
            return LinkCheckRow("understood", LinkState.BLOCKED, title,
                if (unframed != null) "Data arrived but never formed a complete frame (${quote(unframed.summary, cfg)})"
                else "${bytes.formatBytes()} arrived but no complete frame yet",
                "The analyzer sends something the $driverLabel driver doesn't recognise as a frame. " +
                    "Check the machine chosen here matches the analyzer, and on the analyzer that the LIS protocol is the one " +
                    "this driver expects. Serial: a wrong baud rate also looks like this.")
        }
        if (parsed == 0L) {
            val err = logs.lastError?.let { " · ${quote(it.summary, cfg)}" } ?: ""
            return LinkCheckRow("understood", LinkState.BLOCKED, title,
                "$framesIn frame${if (framesIn == 1L) "" else "s"} arrived, none understood$err",
                "Frames arrive but the $driverLabel driver finds no result in them — usually the wrong machine is chosen, " +
                    "or the analyzer sends QC/settings messages only. Edit the analyzer and check the machine; " +
                    "then run a patient sample.")
        }
        val note = if (ignored > 0L) " · $ignored ignored (QC or non-result messages)" else ""
        return LinkCheckRow("understood", LinkState.OK, title, "$parsed of $framesIn frames carried a result$note")
    }

    private fun matched(cfg: InstrumentConfig, status: InstrumentStatus?, logs: LinkLogSummaries): LinkCheckRow {
        val title = "Result matched to an order"
        val parsed = status?.framesParsed ?: 0L
        val applied = status?.framesApplied ?: 0L
        val unmatched = status?.framesUnmatched ?: 0L
        // verify_pending only BLOCKS once a result actually exists to be held: naming
        // it the blocker while nothing has arrived would point the lab at the last
        // step when the analyzer has not even reached the first one.
        if (parsed == 0L) return LinkCheckRow("matched", LinkState.WAITING, title,
            if (cfg.verifyPending) "No result yet · when one arrives it waits until the bench presses Verified"
            else "No result yet")
        if (cfg.verifyPending) return LinkCheckRow("matched", LinkState.BLOCKED, title,
            "Results wait in \"Waiting for an order\" until the bench presses Verified",
            "BNM support changed this analyzer's settings. Run one known sample, check its values against the order, " +
                "then press Verified on this page.", LinkAction.PRESS_VERIFIED)
        if (applied > 0L) {
            val last = logs.lastApplied?.let { "last: ${quote(it.summary, cfg)} at ${time(it.at)}" }
                ?: "$applied applied"
            val waiting = if (unmatched > 0L) " · $unmatched waiting for an order" else ""
            return LinkCheckRow("matched", LinkState.OK, title, last + waiting,
                if (unmatched > 0L) "Some results arrived with a sample id that matches no order — assign them under " +
                    "\"Waiting for an order\", and key the accession number as the sample id on the analyzer." else null,
                if (unmatched > 0L) LinkAction.ASSIGN_WAITING_RESULTS else null)
        }
        val reason = logs.lastQueued?.summary?.removePrefix(LinkLogSummaries.QUEUED_PREFIX)?.trimStart(' ', '—', '-')
            ?.let { quote(it, cfg) }
        return LinkCheckRow("matched", LinkState.BLOCKED, title,
            "$unmatched result${if (unmatched == 1L) "" else "s"} waiting for an order" + (reason?.let { " — $it" } ?: ""),
            "The analyzer's sample id must be the order's accession number (the number on the tube's sticker) — " +
                "key it on the analyzer before running the sample. The results that already arrived can be assigned " +
                "under \"Waiting for an order\".", LinkAction.ASSIGN_WAITING_RESULTS)
    }

    // ── verdict ──

    private fun verdictOf(rows: List<LinkCheckRow>, status: InstrumentStatus?, logs: LinkLogSummaries): String {
        rows.firstOrNull { it.state == LinkState.BLOCKED }?.let { return "Blocked at: ${it.title}" }
        if ((status?.framesApplied ?: 0L) > 0L) {
            val at = logs.lastApplied?.at ?: status?.lastFrameAt
            val acc = logs.lastApplied?.let { appliedAccession(it.summary) }
            return "Linked — last result ${time(at)}" + (acc?.let { ", applied to $it" } ?: "")
        }
        // "not checked" names a read that FAILED (the firewall) — never the optional
        // analyzer address, which is unknown by design until the lab types it.
        val unknownBefore = { idx: Int ->
            rows.take(idx).filter { it.state == LinkState.UNKNOWN && it.step != "reachable" }.map { it.title }
        }
        val waitingIdx = rows.indexOfFirst { it.state == LinkState.WAITING }
        if (waitingIdx >= 0) {
            val base = when (rows[waitingIdx].step) {
                "listening" -> "Opening the port…"
                "connected" -> "Waiting for the analyzer to send"
                "understood" -> "Waiting for a complete frame"
                "matched" -> "Waiting for a result"
                else -> "Waiting"
            }
            val notChecked = unknownBefore(waitingIdx)
            return if (notChecked.isEmpty()) base else "$base · not checked: ${notChecked.joinToString(", ")}"
        }
        rows.firstOrNull { it.state == LinkState.UNKNOWN }?.let { return "Could not check: ${it.title}" }
        return "Linked"
    }

    private fun verdictStateOf(rows: List<LinkCheckRow>, status: InstrumentStatus?): LinkState = when {
        rows.any { it.state == LinkState.BLOCKED } -> LinkState.BLOCKED
        (status?.framesApplied ?: 0L) > 0L -> LinkState.OK
        rows.any { it.state == LinkState.WAITING } -> LinkState.WAITING
        rows.any { it.state == LinkState.UNKNOWN } -> LinkState.UNKNOWN
        else -> LinkState.OK
    }

    /** "Applied 18/20 params to ACC-S1-00042 · CBC" → "A***0042". */
    internal fun appliedAccession(summary: String): String? =
        APPLIED_TO.find(summary)?.groupValues?.get(1)?.let { FrameScrubber.maskId(it) }

    private val APPLIED_TO = Regex("""\bto (\S+)""")

    // ── report text (Copy report) ──

    /** PHI-free plain text for WhatsApp to BNM: rows, counters, PC facts, app version. */
    fun reportText(
        cfg: InstrumentConfig,
        status: InstrumentStatus?,
        logs: LinkLogSummaries,
        facts: LinkFacts,
        report: LinkCheckReport,
        appVersion: String,
    ): String = buildString {
        appendLine("BNM Lab $appVersion · Analyzer link check · ${facts.os}")
        val link = if (cfg.transport == InstrumentTransport.SERIAL) "serial ${cfg.serialPort ?: "?"} @ ${cfg.baud}"
            else "TCP port ${cfg.tcpPort ?: "?"}" + (cfg.analyzerHost?.takeIf { it.isNotBlank() }?.let { " · analyzer $it" } ?: "")
        appendLine("Analyzer: ${cfg.name} · ${driverFor(cfg.driver)?.label ?: cfg.driver} · $link" +
            (if (!cfg.enabled) " · DISABLED" else "") + (if (cfg.verifyPending) " · verify pending" else ""))
        appendLine("Verdict: ${report.verdict}")
        report.rows.forEachIndexed { i, r ->
            appendLine("${i + 1}. [${r.state}] ${r.title} — ${r.detail}")
            if (r.state == LinkState.BLOCKED || r.state == LinkState.UNKNOWN) r.hint?.let { appendLine("   → $it") }
        }
        appendLine("Counters: state=${status?.state ?: "none"} bound=${status?.boundAt ?: "-"} bytes=${status?.bytesIn ?: 0} " +
            "frames=${status?.framesIn ?: 0} parsed=${status?.framesParsed ?: 0} applied=${status?.framesApplied ?: 0} " +
            "unmatched=${status?.framesUnmatched ?: 0} ignored=${status?.framesIgnored ?: 0} acks=${status?.acksSent ?: 0} " +
            "lastFrame=${status?.lastFrameAt ?: "-"} peer=${status?.peerIp ?: "-"} " +
            "lastError=${status?.lastError?.let { quote(it, cfg) } ?: "-"}@${status?.lastErrorAt ?: "-"}")
        logs.lastRx?.let { appendLine("Last rx:    ${time(it.at)} ${quote(it.summary, cfg)}") }
        logs.lastInfo?.let { appendLine("Last info:  ${time(it.at)} ${quote(it.summary, cfg)}") }
        logs.lastError?.let { appendLine("Last error: ${time(it.at)} ${quote(it.summary, cfg)}") }
        appendLine("PC: ipv4=${facts.localIpv4.ifEmpty { listOf("-") }.joinToString(",")} " +
            "serial=${facts.serialPorts.ifEmpty { listOf("-") }.joinToString(",")} " +
            "firewall=${facts.firewall?.let { fw ->
                when {
                    !fw.applicable -> "n/a"
                    !fw.known -> "unknown(${fw.note ?: "?"})"
                    else -> "${if (fw.enabled == true) "on" else "off"}${fw.profile?.let { "/$it" } ?: ""} " +
                        "rulePort=${fw.ruleByPort ?: "-"} ruleProgram=${fw.ruleByProgram ?: "-"}"
                }
            } ?: "-"} ping=${facts.ping?.let { "${if (it.reachable) "ok" else "fail"} ${it.detail}" } ?: "-"}")
    }.trimEnd()

    // ── helpers ──

    /** A log summary for display: ids masked, the driver key shown as its label. */
    private fun quote(summary: String, cfg: InstrumentConfig): String {
        val label = driverFor(cfg.driver)?.label
        val readable = if (label != null) summary.replace(cfg.driver, label) else summary
        return FrameScrubber.maskIds(readable)
    }

    private fun isLoopback(ip: String) = ip == "127.0.0.1" || ip == "::1" || ip.equals("localhost", ignoreCase = true)

    /** "2026-09-25T10:42:07.123Z" → "2026-09-25 10:42"; missing → "—". */
    internal fun time(iso: String?): String = iso?.replace('T', ' ')?.take(16) ?: "—"

    private fun Long.formatBytes(): String = when {
        this >= 1_048_576L -> "${this / 1_048_576L} MB"
        this >= 1_024L -> "${this / 1_024L} KB"
        else -> "$this bytes"
    }
}
