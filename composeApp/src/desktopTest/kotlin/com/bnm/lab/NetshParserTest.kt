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
                     localPort: String? = "5500", action: String = "Allow", program: String = "Any") = """
Rule Name:                            $name
----------------------------------------------------------------------
Enabled:                              $enabled
Direction:                            $dir
Profiles:                             Domain,Private,Public
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
    fun `rule present by port - exact, list, range, Any, not by UDP, disabled, outbound or block rules`() {
        val out = rule("Vendor UDP", protocol = "UDP") + rule("Old disabled", enabled = "No") +
            rule("Outbound only", dir = "Out") + rule("Blocker", action = "Block") +
            rule("BNM Lab analyzer port 5500")
        val f = NetshParser.facts(5500, exe, profileOn, out)
        assertTrue(f.known); assertEquals(true, f.enabled); assertEquals("Private", f.profile)
        assertEquals("BNM Lab analyzer port 5500", f.ruleByPort)
        assertNull(f.ruleByProgram)
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
