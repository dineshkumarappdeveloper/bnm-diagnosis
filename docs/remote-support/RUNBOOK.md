# BNM Lab — Remote support runbook

How a support session runs end to end, and what to do when an analyzer is
not linking. Written for the BNM engineer (a person, or Claude driving the
tools). The contract is [`CONTRACT.md`](CONTRACT.md); the bridge's own notes
are in [`tools/remote-mcp/README.md`](../../tools/remote-mcp/README.md).

Vocabulary: **the lab** = the customer's PC running BNM Lab; **the owner** =
the staff member with the OWNER role (only they can start a session);
**the relay** = BNM's Cloudflare Worker that pairs one lab with one engineer;
**the bridge** = `bnmlab-remote.mjs`, the MCP server in your Claude Code.

## 0. Before you start (once)

- Node ≥ 22 and Claude Code on your machine.
- A support key: `node tools/remote-mcp/bnmlab-remote.mjs keygen`. Its public
  half must be compiled into the build the lab runs (`RemoteSupportKeys.kt`).
  Until then use the dev key in `tools/remote-mcp/test/` — `lab.overview`
  reports `support_key: dev` on such builds.
- The relay's engineer token (`BNM_SUPPORT_TOKEN`) and URL. The URL is
  `wss://lab-relay.bnmapp.com` — the host `relay/wrangler.toml` binds to the
  Worker and the one every lab build dials. Until that Worker is deployed
  (`relay/README.md`, "Secrets and deploy"), no session can connect from any
  lab: check `curl https://lab-relay.bnmapp.com/v1/health` first.
- Registered: `claude mcp add bnmlab-remote -e BNM_RELAY_URL=… -e BNM_SUPPORT_TOKEN=… -- node /abs/path/tools/remote-mcp/bnmlab-remote.mjs`.

## 1. The lab's side — what to tell the person at the PC

Say it in plain words; they are lab staff, not IT.

1. "Open BNM Lab. In the top menu choose **Help ▸ Remote support…**" (also
   under Settings ▸ App ▸ Remote support; it works from the activation and
   sign-in screens too).
2. Only the owner can start it. If someone else is signed in the dialog asks
   for the owner's PIN — the owner types it, nobody reads it out.
3. "Pick how long: 1, 4 or 24 hours." Default 4 h is right for a linking
   problem. The session ends on its own; they can end it earlier any time.
4. The consent text explains what you can see and change. Three boxes are
   off by default — ask for **only what the ticket needs**:
   - **Share analyzer data** — you need raw frames (`instruments.log` with
     `include_raw`). Frames carry sample ids and, on some analyzers, patient
     names. Needed for "results come in but land on the wrong test/order".
   - **Allow looking up records** — `db.query` on patient/order/result
     tables. Rarely needed for a linking problem.
   - **Allow screen view** — `app.screenshot`. Handy when the staff cannot
     describe what they see.
5. "Press **Start session** and read me the code on the screen" — eight
   characters as `XXXX-XXXX`. It never contains I, L, O or U; if they read
   "oh" it is zero, "eye"/"ell" is one.
6. While the session runs they see an amber banner on every screen with the
   code, the end time, a count of your actions and an **End** button. A green
   dot appears when you connect. Ask them to leave the app open and the PC
   awake; the app reconnects by itself if the network blips.

## 2. Your side — the order of work

```
lab_connect  { "code": "…" }        → lab name, app version, consent, time left
lab_tools                            → read every tool's annotations once
lab_call { "tool": "lab.overview" }  → edition, licence standing, version, uptime, IPs, disk, clock skew, support_key
```

Then the read-only picture before touching anything:

| Step | Tool | Look for |
| --- | --- | --- |
| Analyzer rows + live state | `instruments.list` | `state` listening / error / off, `lastFrameAt`, the counters (`bytesIn`, `framesIn`, `framesParsed`, `framesApplied`, `framesUnmatched`, `framesIgnored`, `acksSent`, `lastError`, `peerIp`, `boundAt`), `verify_pending` |
| What the PC can see | `instruments.ports` | serial port names/descriptions and who holds them; TCP ports configured vs bound; local IPv4s |
| What happened | `instruments.log` (`instrument_id`, `limit`, `since_id`) | error rows, "Frame not understood", ACK rows, timestamps |
| Frames that did not land | `instruments.unmatched` | reason, masked specimen id, which parameter keys arrived |
| App-level trouble | `logs.tail` (`lines`, `day`) | the redacted rolling log; with no `lines` you get the diagnostics summary |
| The catalog side | `catalog.tests` | test codes and parameter keys + units the mapping must hit |

Only then the writes, each one announced to the engineer and confirmed
before it is sent (`destructiveHint: true` in `lab_tools`):

| Tool | Use when | Notes |
| --- | --- | --- |
| `instruments.restart` `{instrument_id?, force}` | port/bind stuck, after a cable re-plug, after a config change | Refused if a frame arrived in the last 10 s unless `force` — a run may be in progress and Mispa does not retransmit. Restart one instrument, not all. |
| `instruments.set_config` | wrong port/baud/driver/tcp port/mapping | Changing `driver_key` or `param_map_json` sets `verify_pending` — see §3.7. Validate first with `instruments.dry_run`. |
| `instruments.delete` | duplicate/ghost rows only | Audited; the lab loses that row's settings. |
| `app.update.prefetch` `{version?}` | the fix is in a newer build | Downloads and verifies the installer and pops the OS prompt on the lab PC — someone there must click. |
| `session.end` | done | Same as the owner pressing End. Prefer telling them so they see it close. |

`lab_status` any time for time left and whether the lab is still on the
relay. `lab_disconnect` when you are done; the owner's session keeps running
until they end it — remind them to press **End**.

## 3. Analyzer not linking — the checklist

Work top to bottom; most tickets stop at 2, 3 or 4. "Frame" = one result
message from the analyzer.

### 3.1 Is the link even up?

`instruments.list` → the row's `enabled`, `state`, `lastError`, `boundAt`.

- `off` — the row is disabled. `instruments.set_config` with `enabled: true`
  (confirm first).
- `error` — read `lastError`. Since this branch the engine retries every 10 s
  on its own (self-heal), so a transient error clears by itself; a persistent
  one is a real port/permission problem — go to 3.2 (TCP) or 3.3 (serial).
- `listening` with `lastFrameAt` empty — the PC is ready, the analyzer never
  sent anything (or sent to the wrong place). Go to 3.2 / 3.3.
- `listening`, `bytesIn` rising, `framesIn` 0 — bytes arrive that do not
  frame: wrong baud (3.3) or wrong driver (3.5).
- `framesIn` rising, `framesParsed` 0 — framed but not understood: 3.5.
- `framesParsed` rising, `framesApplied` 0 — parsed but not landing: 3.6.

### 3.2 Firewall and network (TCP analyzers)

The analyzer connects **to the PC**; the app binds `0.0.0.0:<tcp_port>`.

- `lab.overview` → the PC's local IPv4s. The analyzer's "host/LIS IP" setting
  must be one of them — not the router, not 127.0.0.1. IPs change when a PC
  moves from Wi-Fi to cable or after a DHCP lease; a static IP or DHCP
  reservation on the PC is the lasting fix.
- `instruments.ports` → is the configured TCP port `bound`? Not bound = the
  bind failed (another program on that port, or the app is not allowed).
- `instruments.probe { tcp_host, tcp_port }` from the PC to the **analyzer's**
  IP/port checks the cable and subnet answer at all (3 s timeout). "Refused"
  means reachable but not listening on that port; "timeout" means no route,
  wrong IP, or a firewall between.
- Windows Firewall: the first bind triggers the "allow BNM Lab on private
  networks" prompt; if staff clicked Cancel the analyzer's connection is
  dropped silently. `peerIp` empty + `bytesIn` 0 while the analyzer says
  "connected" is the classic sign. Ask staff to open Windows Security ▸
  Firewall ▸ Allow an app and tick BNM Lab (private), or add an inbound rule
  for the port. `app.screenshot` (screen consent) shows what they see.
- Analyzer on another subnet/VLAN: the probe times out; the lab's IT must
  route or move it.
- `peerIp` set but the wrong device: two analyzers pointed at one port —
  give each its own row and port.

### 3.3 Serial port (USB-serial / RS-232 analyzers)

- `instruments.ports` → is the port the row names in the list at all? USB
  adapters renumber (COM3 → COM5) when re-plugged into another socket; the
  description (FTDI, Prolific, CH340) tells you which is which. Fix the row
  with `instruments.set_config` `{serial_port}`, or ask staff to move the
  plug back to the original socket.
- `held_by` shows another enabled instrument owning the port — two rows on
  one port; disable or fix one.
- `instruments.probe { serial_port }` opens and closes the port (zero reads).
  Refused when an enabled instrument owns it — that is expected; it exists
  for the not-yet-configured case. "Access denied" = another program (the
  analyzer vendor's own software, a serial monitor) has it open.
- No driver for the adapter: the port is absent from the list entirely;
  staff must install the vendor driver (a PC-side task, out of our reach).

### 3.4 Baud and line settings

Bytes arrive but `framesIn` stays 0, or the log shows "Frame not understood"
with garbage excerpts (`ÿ`, `?`, box characters): the baud rate does not match
the analyzer's. Read the analyzer's own communication menu (staff can
photograph it) and set `baud` with `instruments.set_config`. Common: Mispa
9600, Mindray 9600/115200, Sysmex 9600. Data bits/parity/stop are fixed
8-N-1 in our serial transport — an analyzer set to 7-E-1 must be changed on
the analyzer.

### 3.5 Driver

`instruments.list` → `driver_key`. The log's "Frame not understood by
<driver>" rows (each with a masked 120-char excerpt) tell you what actually
arrives:

- Starts with `MSH|^~\&` — HL7 (Mindray BC-5xxx family, many others).
- Fixed fields with a PatientID in field 3, Mispa-style — `mispa_count_x`.
- ASTM (`H|\^&`) — no driver in v1; note it for the roadmap and tell the
  customer.

Wrong driver → `instruments.set_config` `{driver_key}`; this sets
`verify_pending` (3.7). Paste a real frame into `instruments.dry_run` first:
it parses with the row's driver and reports parsed keys/units, whether an
order **would** match (`would_match` only, accession masked), unmapped keys
and unit mismatches — without routing or writing anything. Specimen ids
starting `BNMTEST-` never match, so you can dry-run test frames safely.

### 3.6 Mapping — parsed but not applied

`framesParsed` rises, `framesApplied` does not, `instruments.unmatched` grows.
Read the `reason` per row:

- **No order for specimen** — the analyzer sends a sample id the lab never
  keyed as an accession. Routing matches exact, then the numeric tail
  (`findOrder`). If staff type `A-000123` but the analyzer sends `123`, the
  tail match should catch it; if they type nothing (walk-in results), the
  frames can only be claimed by hand in the app. Coach the workflow: register
  the order **before** running the sample, with the same id.
- **Parameter keys not mapped** — `param_map_json` maps the analyzer's keys
  (`WBC`, `HGB`, `PLT` …) to the catalog's parameter keys (`catalog.tests`).
  `instruments.dry_run` lists the unmapped keys. Fix with `set_config`
  `{param_map_json}` — a JSON object of string → string; it sets
  `verify_pending`.
- **Unit mismatch** — the analyzer reports `10^3/uL` while the catalog says
  `10^9/L`. Unit scaling is not in v1; either change the analyzer's output
  unit or the catalog parameter's unit (a lab decision, not ours).
- **Analyzer settings changed by support — check one known sample, then
  press Verified** — that is 3.7, not a fault.

### 3.7 `verify_pending`

After support changes `driver_key` or `param_map_json` the row is flagged and
every frame from that instrument is queued as unmatched (never auto-applied)
until a person at the lab presses **Verified** on the Instruments screen
(amber "Settings changed by BNM support — Verified?" row). This is on
purpose: a wrong mapping must not write into patient results unseen.

Close the loop: ask staff to run **one known sample** (a control, or a sample
they already have a result for), open Instruments, compare the queued values,
then press Verified. The owner can hand the bench over with **Switch user**
while you watch — that does not end the session. The queued frames can then be claimed in the app as
usual. Tell them before you end the session, and write it in the ticket —
"waiting for lab to verify" is the most common reason a fixed link still
looks broken the next morning.

### 3.8 Still nothing?

- `logs.tail` around the timestamps — bind errors, permission errors and
  crashes show here (redacted).
- `lab.overview` → clock skew vs relay: a PC clock hours off breaks nothing
  in linking but confuses every timestamp you read.
- `app.update.prefetch` if the fix is in a newer build (someone at the PC
  clicks the installer).
- Anything needing hands — cables, adapter drivers, the analyzer's own menus,
  Windows prompts — is a phone call with `app.screenshot` as your eyes
  (screen consent).

## 4. Ending, and what is left behind

- Tell the lab you are done and ask them to press **End** (or `session.end`).
  The app sends `end`, the relay drops both sides, the banner disappears.
  Expiry does the same on its own, and so does the owner pressing **Sign out**.
  **Switch user** does not — staff can take the bench mid-session (3.7 needs
  exactly that), and the idle auto-lock leaves the session running too.
- What stays on the lab PC: Support history (`support_sessions`,
  `support_audit`): when, who started it, what was consented, every tool call
  with a PHI-free summary, ok/refused/failed, duration. It survives a tenant
  switch on purpose. Nothing else: no service, no port, no key.
- What stays with you: nothing from the lab unless you copied it. Do not.

## 5. When the bridge itself misbehaves

| You see | Do |
| --- | --- |
| `Could not connect to the relay … Check BNM_RELAY_URL and BNM_SUPPORT_TOKEN` | Relay down, DNS, or wrong token (a 401 is indistinguishable client-side). `curl https://<relay-host>/v1/health` for a pulse (`{"ok":true}`; every other path is 404 on purpose); check the token with whoever holds the Worker secrets. |
| `No support session with that code` | Misread code (0/O, 1/I/L are mapped, but 8/B, 5/S are not) or the session ended. Ask them to read it again or start a new one. |
| `Another engineer is already connected` | One connection per session. |
| `Refused by the lab: Bad signature` | The build does not embed your public key — dev build vs your prod key or the reverse. `lab.overview` → `support_key`. |
| `Refused by the lab: Request timestamp is outside the 5-minute window` | Your clock; `lab.overview` shows the lab's skew vs relay for comparison. |
| `The lab's connection to the relay dropped` | Their network. The app reconnects with backoff (2 s → 30 s) until expiry; wait and retry — the calls flow again on their own, no reconnect needed on your side. |
| `The lab did not answer within 60 s` | Slow PC or a stuck tool. `lab_status`; if the peer is still there, retry once, then `lab_disconnect` + `lab_connect`. |

## 6. Rules that do not bend

- Only the owner starts a session; you never can. No session, no access.
- Read before write; announce and confirm every destructive call.
- Consent boxes are the lab's decision. Do not ask for more than the ticket
  needs and never work around a refusal.
- Patient data stays on the lab PC. Never copy frames, names or ids into
  tickets, chats or screenshots outside BNM.
- Not in v1, do not improvise them: result entry, claiming or discarding
  unmatched results, staff/PIN or licence changes, arbitrary SQL writes,
  file system, shell, printing, remote desktop.
