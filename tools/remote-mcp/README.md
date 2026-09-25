# bnmlab-remote — the engineer's bridge to a lab's BNM Lab

When a lab owner starts a **support session** in BNM Lab (Help ▸ Remote
support…), their copy of the app dials out to BNM's relay and serves a small,
fixed set of MCP tools. This bridge is the other end: a **stdio MCP server**
you register with Claude Code. It joins the session with the code the owner
reads to you, signs every call with your support key, and forwards it. The
lab verifies the signature, checks the owner's consent, counts the call on a
banner and writes it to Support history.

Zero dependencies. Node ≥ 22. One file: `bnmlab-remote.mjs`.

## Install

```sh
# 1. A support key (once). Keep it private; it never leaves this machine.
node /abs/path/BNMLab/tools/remote-mcp/bnmlab-remote.mjs keygen
#    → ~/.config/bnmlab-remote/support.key (0600) and support.pub
#    → prints the SPKI base64 line to paste into RemoteSupportKeys.kt

# 2. Register with Claude Code
claude mcp add bnmlab-remote \
  -e BNM_RELAY_URL=wss://lab-relay.bnmapp.com \
  -e BNM_SUPPORT_TOKEN=<the relay's engineer token> \
  -- node /abs/path/BNMLab/tools/remote-mcp/bnmlab-remote.mjs
```

| Variable | Meaning | Default |
| --- | --- | --- |
| `BNM_RELAY_URL` | Relay base URL; the bridge appends `/v1/support` | `wss://lab-relay.bnmapp.com` (a placeholder until the relay is deployed) |
| `BNM_SUPPORT_TOKEN` | The relay's engineer token (`BNM_SUPPORT_TOKEN` secret on the Worker) | — (required) |
| `BNM_SUPPORT_KEY_FILE` | Ed25519 private key, PEM PKCS#8 | `~/.config/bnmlab-remote/support.key` |

The lab only accepts calls signed by a key whose **public** half is compiled
into the app (`composeApp/src/commonMain/kotlin/com/bnm/lab/remote/RemoteSupportKeys.kt`,
`SUPPORT_PUBLIC_KEY_SPKI_B64`). `keygen` prints exactly that line. Until a
release ships with your public key, sign with the **dev key** below.

### The dev key

`test/dev-support.key` / `test/dev-support.pub` is the pair the app embeds on
this branch (`RemoteSupportKeys.isDevKey`, shown by `lab.overview` as
`support_key: dev`). Anyone with the repo can sign with it, which is fine for
development and worthless for production — **replace before release**:

```sh
node bnmlab-remote.mjs keygen ~/.config/bnmlab-remote/support.key
# paste the printed SUPPORT_PUBLIC_KEY_SPKI_B64 into RemoteSupportKeys.kt, ship a build
```

To use the dev key meanwhile: `-e BNM_SUPPORT_KEY_FILE=/abs/path/tools/remote-mcp/test/dev-support.key`.

Rotation: generate a new key (`keygen --force`), embed the new public key,
ship; labs on the old build keep verifying against the old key until they
update, so keep the old key file until every lab has moved.

## A session, start to finish

**The lab** (a person at the PC — the owner, or staff with the owner's PIN):
Help ▸ Remote support… → picks how long (1 h / 4 h / 24 h) → reads the
consent text → ticks only what you need (analyzer data, records, screen) →
Start session → reads you the code on the screen, `XXXX-XXXX`.

**You**, in Claude Code with this server registered:

```
lab_connect  { "code": "7K3M-QX9P" }     ← dashes, spaces and case do not matter
  → Connected to "Sunrise Diagnostics" (BNM Lab 1.4.0).
    Session ends in 3 h 58 min. Consent — analyzer data: no, records: no, screen: yes.

lab_tools                                 ← read the annotations before calling anything
  → 15 tools on the lab. Destructive ones (annotations.destructiveHint) need the engineer's go-ahead.

lab_call { "tool": "lab.overview" }
lab_call { "tool": "instruments.list" }
lab_call { "tool": "instruments.log", "args": { "instrument_id": "inst-01", "limit": 50 } }
lab_call { "tool": "instruments.restart", "args": { "instrument_id": "inst-01" } }   ← destructive: ask first
lab_status
lab_disconnect
```

Every `lab_call` is one line in the lab's Support history and one tick on
their banner, refused ones included. The owner can press **End** at any time;
you get "The lab ended the support session." and the socket closes.

The full engineer walkthrough and the analyzer-not-linking checklist are in
[`docs/remote-support/RUNBOOK.md`](../../docs/remote-support/RUNBOOK.md).

## The safety rule

`lab_call` itself is marked `destructiveHint: true` so a well-behaved client
asks before every call — but the annotation that matters is the **lab's**, per
tool, in `lab_tools`:

- `readOnlyHint: true` — look, don't touch (`lab.overview`, `instruments.*`
  reads, `logs.tail`, `db.query`, `app.screenshot`, `session.info`).
- `destructiveHint: true` — changes the lab PC (`instruments.restart`,
  `instruments.set_config`, `instruments.delete`, `app.update.prefetch`,
  `session.end`). Say what you are about to do and why, and wait for the
  engineer's yes. A restart during a run can lose the frame the analyzer is
  sending right now (Mispa does not retransmit).
- "(requires: … consent)" in a description means the owner must have ticked
  that box; otherwise the lab answers `Refused by the lab: …`. Do not work
  around it — ask the lab to restart the session with the box ticked if you
  truly need it.

Patient data: the lab masks sample and patient ids in everything it returns
unless the owner allowed analyzer data or records. Never paste what you see
into tickets or chats outside BNM; the bridge itself never logs message
bodies, codes, tokens or keys (stderr only ever says "connected",
"disconnected", "socket closed").

## What the lab sees

- The consent dialog before anything starts; the session code, large.
- An amber banner on every screen: `BNM support session · code XXXX-XXXX ·
  ends 18:30 · 3 actions · [End]` — a green dot while you are connected.
- Support history (Settings ▸ App): time, tool, a short PHI-free summary,
  ok / refused / failed, for every call.
- Nothing after the session: no service, no open port, no way back in.

## The five tools

| Tool | Args | What it does |
| --- | --- | --- |
| `lab_connect` | `{code}` | Joins the session: WSS to `$BNM_RELAY_URL/v1/support` with the bearer token, `join`, forwards `initialize` + `notifications/initialized`, then one signed `session.info`. Returns lab name, app version, consent, time left. |
| `lab_status` | — | Connection state, whether the lab is on the relay, time left (counted down locally), actions sent. Works offline. |
| `lab_tools` | — | The lab's `tools/list` verbatim: name, description, inputSchema, annotations. |
| `lab_call` | `{tool, args?}` | Signs and forwards `tools/call`; returns the lab's `content` verbatim (text, or `image` for `app.screenshot`). Lab errors come back as `isError` text: `Refused by the lab: …` for policy (−32001), `The lab answered with an error (code): …` otherwise. |
| `lab_disconnect` | — | Closes the socket. The owner's session keeps running; `lab_connect` again with the same code. |

## For the app developer: what goes over the wire

Request the bridge sends for a tool call (one text frame):

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call",
 "params":{"name":"instruments.log","arguments":{"limit":50,"include_raw":false}},
 "bnm":{"ts_ms":1790000000000,"nonce":"0123456789abcdef0123456789abcdef","sig":"<base64>"}}
```

`sig` = Ed25519 over the UTF-8 of

```
bnm-lab-support-v1 \n session_id \n <id as string> \n tools/call \n ts_ms \n nonce \n canonical(params)
```

`canonical` = JSON with object keys sorted **recursively by UTF-16 code unit**,
no whitespace, numbers as sent. Two traps the fixtures pin down:

- `"10"` sorts before `"9"` (string order). A JS "rebuild the object with
  sorted keys, then `JSON.stringify`" would put integer-like keys first in
  numeric order — the bridge builds the string by hand instead.
- Numbers are whatever `JSON.stringify` put on the wire (`1e+21`, `1.5e-7`);
  kotlinx keeps parsed literals verbatim, so re-encoding the parsed body gives
  the same text.

`test/canonical-vectors.json` (`[{name, input, canonical, sha256}]`) is shared
with the Kotlin `CanonicalJsonTest`; `test/signing-vectors.json` holds
deterministic Ed25519 signatures made with the dev key for `RemoteRpcTest`.

Where the bridge learns the **session id** it signs with (whichever arrives
first): a relay ack `{"t":"joined"|"ready","session_id":…}` after `join`, or
the lab's `initialize` result at `result._meta.bnm.session_id` (also accepted:
`result.bnm.session_id`). The same object may carry `lab`, `app_version`,
`consent {analyzer_data, records, screen}` and `expires_in_s` /
`remaining_s`; `session.info` (a text content item holding JSON with the same
field names plus `actions`) refreshes them. Without a session id the
connection stands but `lab_call` says it cannot sign.

Relay frames the bridge understands: `{"t":"error","code":"no_session"|"busy"}`,
`{"t":"peer","state":"lab_disconnected"|"connected"}`, `{"t":"end"}`. It
answers a `ping` request from the lab with `{}` and ignores other
notifications and non-JSON frames.

## Troubleshooting

| Message | Meaning / fix |
| --- | --- |
| `A session code has 8 letters and digits…` | Typo. Codes never contain I, L, O or U — the bridge maps them to 1/1/0, but the length must be 8. |
| `No support session with that code.` | Wrong code, or the session ended/expired. Ask the lab to check their screen. |
| `Another engineer is already connected…` | One connection per session. Find who, or ask the lab to End and Start again. |
| `Could not connect to the relay at …. Check BNM_RELAY_URL and BNM_SUPPORT_TOKEN` | DNS/network, or the token was refused at the upgrade (a 401 looks identical from the client). |
| `Support key not found at …` | Run `keygen`, or point `BNM_SUPPORT_KEY_FILE` at the dev key. |
| `Refused by the lab: Bad signature` | The app does not embed this key's public half (dev vs prod key). |
| `Refused by the lab: Request too old…` | Your clock is more than 5 minutes off. |
| `Refused by the lab: … consent` | The owner did not tick that box. |
| `The lab's connection to the relay dropped` | The lab PC lost network; the app reconnects with backoff. Wait, then retry. |
| `The lab did not answer within 60 s` | Long query or screenshot on a slow PC, or a stall. `lab_status`, then retry. |

## Development

```sh
cd tools/remote-mcp
npm test                       # = node --test (78 tests, no network)
node bnmlab-remote.mjs --help

# stdio smoke by hand
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
              '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | BNM_SUPPORT_TOKEN=x node bnmlab-remote.mjs
```

The fake relay (`test/fake-relay.mjs`) plays relay and lab at the
WebSocket-object level and verifies signatures with its own payload builder,
so the tests prove the bridge signs what the contract says, not merely what
the bridge expects.

Not in v1 (by design, see the contract): result entry, claiming unmatched
results, staff/PIN or licence changes, arbitrary SQL writes, file system,
shell, printing, remote desktop.
