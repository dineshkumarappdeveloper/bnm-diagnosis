package com.bnm.lab.instruments

/**
 * Pure parsers for the two `netsh advfirewall` reads the Link check makes on
 * Windows (the desktop [LinkEnvironment] runs the commands, this turns their
 * text into [FirewallFacts]). Kept in commonMain with no I/O so fixture
 * outputs can be tested anywhere.
 *
 * netsh prints LOCALISED headings on a non-English Windows ("Status" becomes
 * "Estado", "Rule Name:" becomes "Regelname:"). Nothing here guesses at those:
 * when the English markers are missing the answer is "unknown", and the row
 * says the firewall could not be read rather than "off".
 */
object NetshParser {

    /** One rule block of `netsh advfirewall firewall show rule name=all dir=in verbose`. */
    data class Rule(
        val name: String,
        val enabled: Boolean,
        val direction: String,
        val action: String,
        val protocol: String,
        val localPort: String?,
        val program: String?,
    ) {
        val isInboundAllow: Boolean
            get() = enabled && direction.equals("In", ignoreCase = true) && action.equals("Allow", ignoreCase = true)

        /** TCP (or Any) whose LocalPort is Any, equals [port], lists it, or spans it. */
        fun allowsTcpPort(port: Int): Boolean {
            if (!isInboundAllow) return false
            if (!(protocol.equals("TCP", ignoreCase = true) || protocol.equals("Any", ignoreCase = true))) return false
            val lp = localPort?.trim() ?: return protocol.equals("Any", ignoreCase = true)
            if (lp.equals("Any", ignoreCase = true)) return true
            return lp.split(',').any { part ->
                val p = part.trim()
                val range = p.split('-')
                when (range.size) {
                    1 -> p.toIntOrNull() == port
                    2 -> {
                        val lo = range[0].trim().toIntOrNull(); val hi = range[1].trim().toIntOrNull()
                        lo != null && hi != null && port in lo..hi
                    }
                    else -> false
                }
            }
        }

        /** Same executable as [exePath] — exact path, or the same file name (installers move, names don't). */
        fun allowsProgram(exePath: String?): Boolean {
            if (!isInboundAllow) return false
            val prog = program?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Any", ignoreCase = true) } ?: return false
            val exe = exePath?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            if (prog.equals(exe, ignoreCase = true)) return true
            val progName = prog.substringAfterLast('\\').substringAfterLast('/')
            val exeName = exe.substringAfterLast('\\').substringAfterLast('/')
            return progName.isNotEmpty() && progName.equals(exeName, ignoreCase = true)
        }
    }

    /** Output of `netsh advfirewall show currentprofile`. */
    data class ProfileState(val enabled: Boolean, val profile: String?)

    /**
     * `State ON|OFF` per "<Name> Profile Settings:" heading. Several active
     * networks print several profiles; ON in any of them counts as on (the
     * analyzer's network may be the one that is). Null when no `State` line
     * was found — a localised Windows, or the command failed.
     */
    fun parseCurrentProfile(output: String): ProfileState? {
        var profile: String? = null
        val states = ArrayList<Pair<String?, Boolean>>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            PROFILE_HEADING.find(line)?.let { profile = it.groupValues[1].trim(); return@let }
            val m = STATE_LINE.find(line) ?: continue
            states += profile to m.groupValues[1].equals("ON", ignoreCase = true)
        }
        if (states.isEmpty()) return null
        val on = states.filter { it.second }
        return if (on.isNotEmpty()) ProfileState(true, on.mapNotNull { it.first }.distinct().joinToString(", ").ifEmpty { null })
            else ProfileState(false, states.mapNotNull { it.first }.distinct().joinToString(", ").ifEmpty { null })
    }

    /**
     * Blocks starting at `Rule Name:`; each `Key:  value` line is split at its
     * FIRST colon (program paths carry `C:`). Non-English output has no
     * `Rule Name:` lines → empty list; the caller must not read that as "no
     * rules" when the output was non-empty ([looksParsable]).
     */
    fun parseRules(output: String): List<Rule> {
        val rules = ArrayList<Rule>()
        var fields: MutableMap<String, String>? = null
        fun flush() {
            val f = fields ?: return
            val name = f["rule name"] ?: return
            rules += Rule(
                name = name,
                enabled = f["enabled"]?.equals("Yes", ignoreCase = true) == true,
                direction = f["direction"] ?: "",
                action = f["action"] ?: "",
                protocol = f["protocol"] ?: "",
                localPort = f["localport"],
                program = f["program"],
            )
        }
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.startsWith("---")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (key == "rule name") {
                flush()
                fields = mutableMapOf("rule name" to value)
            } else {
                fields?.put(key, value)
            }
        }
        flush()
        return rules
    }

    /** True when the rule output carries at least one English `Rule Name:` block. */
    fun looksParsable(output: String): Boolean = output.lineSequence().any { RULE_NAME.containsMatchIn(it) }

    /**
     * The whole answer for one port: the current profile plus the two rule
     * lookups. [profileOutput]/[rulesOutput] null = the command could not be
     * run (timeout, not found).
     */
    fun facts(port: Int, exePath: String?, profileOutput: String?, rulesOutput: String?): FirewallFacts {
        if (profileOutput == null) return FirewallFacts.unknown("netsh did not answer")
        val profile = parseCurrentProfile(profileOutput)
            ?: return FirewallFacts.unknown("firewall status not understood (non-English Windows?)")
        if (!profile.enabled) return FirewallFacts(applicable = true, known = true, enabled = false, profile = profile.profile)
        if (rulesOutput == null) return FirewallFacts(applicable = true, known = false, enabled = true, profile = profile.profile,
            note = "rules could not be read")
        if (rulesOutput.isNotBlank() && !looksParsable(rulesOutput)) return FirewallFacts(applicable = true, known = false,
            enabled = true, profile = profile.profile, note = "rule list not understood (non-English Windows?)")
        val rules = parseRules(rulesOutput)
        return FirewallFacts(
            applicable = true, known = true, enabled = true, profile = profile.profile,
            ruleByPort = rules.firstOrNull { it.allowsTcpPort(port) }?.name,
            ruleByProgram = rules.firstOrNull { it.allowsProgram(exePath) }?.name,
        )
    }

    /** The exact command a person can run in an Administrator Command Prompt. */
    fun addRuleCommand(port: Int): String =
        "netsh advfirewall firewall add rule name=\"${ruleName(port)}\" dir=in action=allow protocol=TCP localport=$port"

    /**
     * The same rule, raised through UAC: `powershell -Command <this>`.
     *
     * 🔴 The rule name has spaces, so netsh needs it in double quotes — but a
     * Java `ProcessBuilder` argument that CONTAINS a double quote is passed to
     * Windows unescaped (it only wraps the argument in quotes of its own), and
     * the interior quote then ends the wrapper and mangles the command line.
     * So the string handed to PowerShell carries no double quote at all:
     * PowerShell builds it from `[char]34` at run time. Single-quoted literals
     * are safe — [ruleName] never contains an apostrophe and [port] is an Int.
     */
    fun elevateRuleCommand(port: Int): String {
        val args = "'advfirewall firewall add rule name=' + [char]34 + '${ruleName(port)}' + [char]34 + " +
            "' dir=in action=allow protocol=TCP localport=$port'"
        return "Start-Process netsh -Verb RunAs -ArgumentList ($args)"
    }

    fun ruleName(port: Int): String = "BNM Lab analyzer port $port"

    private val PROFILE_HEADING = Regex("""^(.+?)\s+Profile Settings:""", RegexOption.IGNORE_CASE)
    private val STATE_LINE = Regex("""^State\s+(ON|OFF)\s*$""", RegexOption.IGNORE_CASE)
    private val RULE_NAME = Regex("""^\s*Rule Name:""", RegexOption.IGNORE_CASE)
}
