package com.bnm.lab

import com.bnm.lab.instruments.NetshParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixture outputs of `netsh advfirewall` as an English Windows 10/11 prints them, plus what a localised one prints. */
class NetshParserTest {

    private val profileOn = """

Private Profile Settings:
----------------------------------------------------------------------
State                                 ON
Firewall Policy                       BlockInbound,AllowOutbound
LocalFirewallRules                    N/A (GPO-store only)
LocalConSecRules                      N/A (GPO-store only)
InboundUserNotification               Enable
RemoteManagement                      Disable
UnicastResponseToMulticast            Enable

Logging:
LogAllowedConnections                 Disable
LogDroppedConnections                 Disable
FileName                              %systemroot%\system32\LogFiles\Firewall\pfirewall.log
MaxFileSize                           4096

Ok.

"""
    private val profileOff = profileOn.replace("State                                 ON", "State                                 OFF")
    private val twoProfiles = """
Domain Profile Settings:
----------------------------------------------------------------------
State                                 ON
Firewall Policy                       BlockInbound,AllowOutbound

Private Profile Settings:
----------------------------------------------------------------------
State                                 OFF
Firewall Policy                       BlockInbound,AllowOutbound

Ok.
"""
    private val profileGerman = """

Einstellungen für Privates Profil:
----------------------------------------------------------------------
Status                                EIN
Firewallrichtlinie                    BlockInbound,AllowOutbound

OK.
"""

    private fun rule(name: String, enabled: String = "Yes", dir: String = "In", protocol: String = "TCP",
                     localPort: String? = "5500", action: String = "Allow", program: String = "Any",
                     profiles: String = "Domain,Private,Public") = """
Rule Name:                            $name
----------------------------------------------------------------------
Enabled:                              $enabled
Direction:                            $dir
Profiles:                             $profiles
Grouping:
LocalIP:                              Any
RemoteIP:                             Any
Protocol:                             $protocol
${if (localPort != null) "LocalPort:                            $localPort\nRemotePort:                           Any\n" else ""}Edge traversal:                       No
Program:                              $program
Service:                              Any
InterfaceTypes:                       Any
Security:                             NotRequired
Rule source:                          Local Setting
Action:                               $action
"""

    private val exe = "C:\\Program Files\\BNM Lab\\BNM Lab.exe"

    @Test
    fun `current profile - on, off, several profiles, localised`() {
        assertEquals(NetshParser.ProfileState(true, "Private"), NetshParser.parseCurrentProfile(profileOn))
        assertEquals(NetshParser.ProfileState(false, "Private"), NetshParser.parseCurrentProfile(profileOff))
        assertEquals(NetshParser.ProfileState(true, "Domain"), NetshParser.parseCurrentProfile(twoProfiles))
        assertNull(NetshParser.parseCurrentProfile(profileGerman))
        assertNull(NetshParser.parseCurrentProfile(""))
    }

    @Test
    fun `rule present by port - exact, list, range, Any, not by UDP, disabled or outbound rules`() {
        val out = rule("Vendor UDP", protocol = "UDP") + rule("Old disabled", enabled = "No") +
            rule("Outbound only", dir = "Out") + rule("BNM Lab analyzer port 5500")
        val f = NetshParser.facts(5500, exe, profileOn, out)
        assertTrue(f.known); assertEquals(true, f.enabled); assertEquals("Private", f.profile)
        assertEquals("BNM Lab analyzer port 5500", f.ruleByPort)
        assertNull(f.ruleByProgram)
        assertNull(f.blockedByRule)
        assertTrue(f.hasRule)

        assertEquals("List", NetshParser.facts(5501, exe, profileOn, rule("List", localPort = "5500,5501")).ruleByPort)
        assertEquals("Range", NetshParser.facts(5300, exe, profileOn, rule("Range", localPort = "5000-5600")).ruleByPort)
        assertNull(NetshParser.facts(6000, exe, profileOn, rule("Range", localPort = "5000-5600")).ruleByPort)
        assertEquals("AnyPort", NetshParser.facts(5500, exe, profileOn, rule("AnyPort", localPort = "Any")).ruleByPort)
        assertEquals("AnyProto", NetshParser.facts(5500, exe, profileOn, rule("AnyProto", protocol = "Any", localPort = null)).ruleByPort)
        assertNull(NetshParser.facts(5500, exe, profileOn, rule("Other", localPort = "5501")).ruleByPort)
    }

    @Test
    fun `rule present by program - full path or same exe name, never a disabled or Any-program rule`() {
        val byPath = NetshParser.facts(5500, exe, profileOn, rule("BNM Lab", protocol = "Any", localPort = null, program = exe))
        assertEquals("BNM Lab", byPath.ruleByProgram)
        assertEquals("BNM Lab", byPath.ruleByPort, "an Any-protocol Any-port allow also covers the port")

        val moved = NetshParser.facts(5500, exe, profileOn,
            rule("BNM Lab (old install)", protocol = "TCP", localPort = "9", program = "D:\\Apps\\BNM Lab\\bnm lab.EXE"))
        assertEquals("BNM Lab (old install)", moved.ruleByProgram)
        assertNull(moved.ruleByPort)

        assertNull(NetshParser.facts(5500, exe, profileOn, rule("Disabled", enabled = "No", program = exe)).ruleByProgram)
        assertNull(NetshParser.facts(5500, exe, profileOn, rule("Something else", localPort = "80", program = "C:\\x\\other.exe")).ruleByProgram)
        assertNull(NetshParser.facts(5500, null, profileOn, rule("BNM Lab", program = exe, localPort = "1")).ruleByProgram, "no exe path known")
    }

    @Test
    fun `an inbound Block rule beats every allow rule and is reported as such`() {
        // Windows enforces an explicit inbound block over any allow, so an
        // added allow rule cannot clear it. Parsing the block and then ignoring
        // it let "Add firewall rule" turn the row green while the SYN was still
        // being dropped — the exact dead end this check exists to prevent.
        val f = NetshParser.facts(5500, exe, profileOn, rule("BNM Lab analyzer port 5500") + rule("Blocker", action = "Block"))
        assertEquals("BNM Lab analyzer port 5500", f.ruleByPort)
        assertEquals("Blocker", f.blockedByRule)
        assertFalse(f.hasRule, "a block rule cancels every allow")

        // A Cancel on the Windows Security Alert: a block scoped to the program.
        val byProgram = NetshParser.facts(5500, exe, profileOn,
            rule("BNM Lab", action = "Block", protocol = "Any", localPort = null, program = exe))
        assertEquals("BNM Lab", byProgram.blockedByRule)

        // Outbound and disabled blocks are not enforced on the analyzer's inbound connection.
        assertNull(NetshParser.facts(5500, exe, profileOn, rule("Out block", action = "Block", dir = "Out")).blockedByRule)
        assertNull(NetshParser.facts(5500, exe, profileOn, rule("Old block", action = "Block", enabled = "No")).blockedByRule)
        // Nor is a block on a different port.
        assertNull(NetshParser.facts(5500, exe, profileOn, rule("RDP block", action = "Block", localPort = "3389")).blockedByRule)

        assertEquals("netsh advfirewall firewall delete rule name=\"BNM Lab\" dir=in",
            NetshParser.deleteRuleCommand("BNM Lab"))
    }

    @Test
    fun `a rule scoped to another profile is not enforced and must not count`() {
        // Windows scopes the rule it writes to the profile that was active when
        // the user clicked Allow. Swap the router, get classified Public, and
        // the Private-only rule stops applying — while netsh still lists it.
        val privateOnly = rule("BNM Lab analyzer port 5500", profiles = "Private")
        val domainOnly = rule("BNM Lab analyzer port 5500", profiles = "Domain")
        assertEquals("Private", NetshParser.parseCurrentProfile(profileOn)?.profile)
        assertEquals("BNM Lab analyzer port 5500", NetshParser.facts(5500, exe, profileOn, privateOnly).ruleByPort)
        assertNull(NetshParser.facts(5500, exe, profileOn, domainOnly).ruleByPort)
        assertEquals("BNM Lab analyzer port 5500",
            NetshParser.facts(5500, exe, profileOn, rule("BNM Lab analyzer port 5500", profiles = "Any")).ruleByPort)
        // Same for a program allow and for a block rule.
        assertNull(NetshParser.facts(5500, exe, profileOn,
            rule("BNM Lab", profiles = "Domain", protocol = "Any", localPort = null, program = exe)).ruleByProgram)
        assertNull(NetshParser.facts(5500, exe, profileOn,
            rule("Blocker", action = "Block", profiles = "Domain")).blockedByRule)
        // Two profiles on, the rule covers one of them → it counts.
        assertEquals("BNM Lab analyzer port 5500", NetshParser.facts(5500, exe, twoProfiles, domainOnly).ruleByPort)
        // When netsh named no profile at all the rule still counts, and the note says why that is a guess.
        val unnamed = NetshParser.facts(5500, exe, "State                                 ON", domainOnly)
        assertEquals("BNM Lab analyzer port 5500", unnamed.ruleByPort)
        assertTrue(unnamed.note!!.contains("could still block"), unnamed.note)
    }

    @Test
    fun `absent - firewall on and no matching rule`() {
        val f = NetshParser.facts(5500, exe, profileOn, rule("Remote Desktop", localPort = "3389") + rule("Core Networking", protocol = "ICMPv4", localPort = null))
        assertTrue(f.known); assertEquals(true, f.enabled)
        assertFalse(f.hasRule)
        assertNull(f.ruleByPort); assertNull(f.ruleByProgram)
    }

    @Test
    fun `firewall off - rules are not even consulted`() {
        val f = NetshParser.facts(5500, exe, profileOff, null)
        assertTrue(f.known); assertEquals(false, f.enabled); assertEquals("Private", f.profile)
        assertFalse(f.hasRule)
    }

    @Test
    fun `non-English headings never crash - the answer is unknown`() {
        val german = """
Regelname:                            BNM Lab analyzer port 5500
----------------------------------------------------------------------
Aktiviert:                            Ja
Richtung:                             Eingehend
Profile:                              Domäne,Privat,Öffentlich
Protokoll:                            TCP
Lokaler Port:                         5500
Aktion:                               Zulassen
"""
        val profileUnknown = NetshParser.facts(5500, exe, profileGerman, german)
        assertFalse(profileUnknown.known)
        assertTrue(profileUnknown.note!!.contains("non-English"), profileUnknown.note)

        val rulesUnknown = NetshParser.facts(5500, exe, profileOn, german)
        assertFalse(rulesUnknown.known)
        assertEquals(true, rulesUnknown.enabled)
        assertTrue(rulesUnknown.note!!.contains("rule list not understood"), rulesUnknown.note)

        assertTrue(NetshParser.parseRules(german).isEmpty())
        assertFalse(NetshParser.looksParsable(german))
        assertNotNull(NetshParser.parseRules(""))
    }

    @Test
    fun `commands that could not be run are unknown, with the reason`() {
        assertEquals("netsh did not answer", NetshParser.facts(5500, exe, null, null).note)
        assertFalse(NetshParser.facts(5500, exe, null, null).known)
        val noRules = NetshParser.facts(5500, exe, profileOn, null)
        assertFalse(noRules.known); assertEquals("rules could not be read", noRules.note)
        // An empty rule list on an English Windows is a real "no rules", not "unknown".
        assertTrue(NetshParser.facts(5500, exe, profileOn, "").known)
    }

    @Test
    fun `values keep their colons and the add-rule command is the documented one`() {
        val r = NetshParser.parseRules(rule("Path rule", program = "C:\\Program Files\\BNM Lab\\BNM Lab.exe")).single()
        assertEquals("C:\\Program Files\\BNM Lab\\BNM Lab.exe", r.program)
        assertEquals("Path rule", r.name)
        assertEquals("netsh advfirewall firewall add rule name=\"BNM Lab analyzer port 5500\" dir=in action=allow protocol=TCP localport=5500",
            NetshParser.addRuleCommand(5500))
    }

    @Test
    fun `the elevated command carries no double quote of its own`() {
        val cmd = NetshParser.elevateRuleCommand(5500)
        // A ProcessBuilder argument containing '"' reaches Windows mangled, so the
        // quotes netsh needs must be built by PowerShell, not written here.
        assertFalse(cmd.contains('"'), cmd)
        assertTrue(cmd.startsWith("Start-Process netsh -Verb RunAs -ArgumentList ("), cmd)
        assertTrue(cmd.contains("[char]34 + 'BNM Lab analyzer port 5500' + [char]34"), cmd)
        assertTrue(cmd.contains("localport=5500'"), cmd)
        // Both routes must create the SAME rule, or pressing the button and then
        // pasting the command would leave two differently-named rules behind.
        assertTrue(NetshParser.addRuleCommand(5500).contains(NetshParser.ruleName(5500)))
        assertTrue(cmd.contains(NetshParser.ruleName(5500)))
        // Single-quoted PowerShell literals stay safe only while the name has no apostrophe.
        assertFalse(NetshParser.ruleName(5500).contains('\''), NetshParser.ruleName(5500))
    }
}
