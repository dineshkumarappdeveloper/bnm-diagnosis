package com.bnm.lab.instruments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The PC facts every analyzer on the Instruments screen shares — read ONCE
 * per refresh (every 10 s, or "Check again") and sliced per instrument with
 * [forInstrument]. Firewall answers are keyed by TCP port, pings by the
 * analyzer host, so two analyzers never cost two netsh reads.
 */
data class LinkFactsSnapshot(
    val os: String,
    val isWindows: Boolean,
    val environmentKnown: Boolean,
    val localIpv4: List<LocalAddress>,
    val serialPorts: List<String>,
    val firewallByPort: Map<Int, FirewallFacts>,
    val pingByHost: Map<String, PingResult>,
) {
    fun forInstrument(cfg: InstrumentConfig): LinkFacts = LinkFacts(
        os = os,
        isWindows = isWindows,
        environmentKnown = environmentKnown,
        factsRead = true,
        localIpv4 = localIpv4,
        serialPorts = serialPorts,
        firewall = cfg.tcpPort?.let { firewallByPort[it] },
        ping = cfg.analyzerHost?.trim()?.takeIf { it.isNotEmpty() }?.let { pingByHost[it] },
    )

    companion object {
        /**
         * What the evaluator gets before the first read finished: OS known,
         * everything else "checking". `factsRead = false` is what keeps the
         * empty address and serial-port lists from reading as "this PC has
         * none" during the second or so the first gather takes.
         */
        fun pending(env: LinkEnvironment): LinkFacts = LinkFacts(
            os = env.osName, isWindows = env.isWindows, environmentKnown = env.environmentKnown,
            factsRead = false,
        )
    }
}

/** Blocking OS reads (netsh, ping, ports) off the UI thread. */
suspend fun gatherLinkFacts(env: LinkEnvironment, instruments: List<InstrumentConfig>): LinkFactsSnapshot =
    withContext(Dispatchers.Default) {
        val ports = instruments.filter { it.transport == InstrumentTransport.TCP }.mapNotNull { it.tcpPort }.distinct()
        val hosts = instruments.mapNotNull { it.analyzerHost?.trim()?.takeIf { h -> h.isNotEmpty() } }.distinct()
        LinkFactsSnapshot(
            os = env.osName,
            isWindows = env.isWindows,
            environmentKnown = env.environmentKnown,
            localIpv4 = runCatching { env.localIpv4() }.getOrDefault(emptyList()),
            serialPorts = runCatching { env.serialPorts() }.getOrDefault(emptyList()),
            firewallByPort = runCatching { env.firewallFor(ports) }
                .getOrElse { e -> ports.associateWith { FirewallFacts.unknown(e.message ?: "read failed") } },
            // A ping that threw says nothing about the analyzer — inconclusive, not unreachable.
            pingByHost = hosts.associateWith { h ->
                runCatching { env.ping(h) }.getOrElse { PingResult.noAnswer("ping failed") }
            },
        )
    }
