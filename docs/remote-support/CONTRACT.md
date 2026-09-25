# BNM Lab — Remote support ("Maintenance mode") contract, v1

Owner's ask: BNM installs BNM Lab at a client lab, connects the analyzer, and then needs to see from the office how the analyzer is linking and what the app is doing, and fix it remotely. Specifically: the lab's copy of BNM Lab must be reachable from a Claude Code session as an MCP server, so the engineer (a person, or Claude driving tools) can read and control the app.

Three parts, all in this repo, one branch `claude/remote-support` (base commit holds the shared interface `composeApp/src/commonMain/kotlin/com/bnm/lab/remote/RemoteSupport.kt` — do not change its public shape; a merge step may add members):

1. **App** (`composeApp`): Maintenance mode = an owner-started, time-boxed support session. While it runs the app dials OUT to the relay over WebSocket and serves MCP-shaped JSON-RPC (`initialize`, `ping`, `tools/list`, `tools/call`) with a fixed tool vocabulary. Every call is verified (Ed25519), consent-gated, audited on the lab PC and counted on a visible banner.
2. **Relay** (`relay/`): a Cloudflare Worker + Durable Object — a dumb pipe that pairs one lab connection with one support connection per session. It never interprets, stores or logs message bodies.
3. **Bridge** (`tools/remote-mcp/`): a Node ≥22 stdio MCP server the engineer registers with `claude mcp add`. It exposes generic pass-through tools (`lab_connect`, `lab_status`, `lab_tools`, `lab_call`, `lab_disconnect`), signs every `tools/call` with the engineer's Ed25519 support key, and forwards over WSS to the relay.

Ground truth (from a read-only audit; re-verify a line before editing):
- Transport today is HTTPS request/response only: Ktor 3.1.0 client, OkHttp on desktop (`api/ApiClient.kt`), NO websockets dependency, no supabase-kt. Add `io.ktor:ktor-client-websockets` (catalog `ktor-client-websockets`, version.ref ktor) to commonMain; OkHttp engine supports WS. jlink image: `java.base java.prefs java.desktop java.sql java.naming jdk.unsupported java.logging jdk.crypto.ec` ONLY (Ed25519 verify = `Signature.getInstance("Ed25519")` is in java.base/SunEC — fine; `java.awt.Robot` is java.desktop — fine; nothing else).
- `instruments/InstrumentEngine.kt`: one app-lifetime engine (`App.kt:246`, `start()` :111); `restartAll()` (:117) is the ONLY restart, all-or-nothing under `restartMutex`; `stopAll()` (:136); `saveInstrument(cfg)` (:620) → restartAll; `deleteInstrument` (:634); TCP listener `aSocket().tcp().bind("0.0.0.0", port)` (:187-235), serial via jSerialComm (`SerialPortIO.desktop.kt`), status map `StateFlow<Map<String, InstrumentStatus(state 'listening'|'error'|'off', detail, lastFrameAt)>>` (:89-90, `InstrumentModels.kt:75-79`); `lastFrameAt` moves only on a parsed RESULT frame (:354, :403); NO reconnect/re-bind after `error` — stays until restartAll; NO counters (bytes/frames/peer IP); Mispa `parse` returning null is silently dropped (`MispaCountX.kt:106`); tx (ACK) never logged; `logRow` (:643-655) writes summary→AppLog+DB and `raw` (≤4000 chars, PHI: Mispa PatientID, HL7 PID incl. name) → `instrument_log` DB ONLY, trimmed to 500 rows; `routeFrame` (:447-461) → `findOrder` exact + numeric-tail match (:483-495) → `applyFrameToOrder` (:504-568) or `queueUnmatched` (:572-580); `instrument_results` claim queue; `param_map_json` on `instruments` (`Instruments.sq:16-28`). `TenantReset.sq:81-84` KEEPS `instruments`; :85-92 wipes log/results/graphs.
- Diagnostics: `AppLog` → `RollingLogFile` (7 d, redacted by `LogRedactor` — tokens/JWT/hex≥32/keys/emails/Indian mobiles/OS user; NOT names/patient ids/accessions); `DiagnosticsContext.render()` (PHI-free by contract, `App.kt:248-279`: headline, Licence, Data counts, Sync, Analyzers); `SupportReporter` zip + mailto; `DesktopDiagnostics.environmentLine()`; Help menu `Main.kt:68-75`; `SupportUi` switch (`SupportUi.kt:13-32`) rendered at `App.kt:443-444`; `ReadOnlyBanner` slot `App.kt:630-633`; Settings ▸ App block `update/AppVersionPanel.kt:247-263` (mounted `BillingSettingsScreen.kt:343`).
- Licence: ES256 JWT in prefs `license_jwt` (`license/LicenseManager.kt`), public key `LicenseVerify.kt:9-13` (= `docs/license-public-key.jwk.json`), claims `lid, lab, mode, seats, biz, ed, lic_exp, gr, iat, exp, iss='bnm-lab-license'`. `OfflinePolicy.kt` refuses every automatic call when standalone; `allowsMasterCatalogPull(userInitiated)` (:63) is the one operator-pressed exception; summary text :66-69 promises "nothing leaves this computer".
- Staff: `staff/StaffSession.kt` (current staff + role), `StaffModels.kt` roles OWNER/PATHOLOGIST/…; `canManageStaff = role == OWNER` is the existing "owner-only" pattern. `StaffRepository.verifyPin(staffId, pin)`.
- Prefs = `Settings()` = java.util.prefs user root, SHARED with other BNM apps → every new key prefixed `lab_remote_`; prefs holders take an injectable `Settings`. Tests never touch real prefs or the real data dir.
- SQLDelight: new columns appended LAST (+ `AppDatabaseFactory.addColumn`); new tables also mirrored in `AppDatabaseFactory.createAppDatabase`.

## 1. What the lab sees (commonMain UI)

Entry points: **Help ▸ Remote support…** (`Main.kt` MenuBar; works from activation and sign-in too) and **Settings ▸ App ▸ Remote support** row (next to "Having a problem?"). Both open `RemoteSupportDialog`.

Start (owner only — `StaffSession` role OWNER; if the current staff is not the owner, the dialog asks for the owner's PIN via the existing `OwnerPinDialog` pattern — reuse or replicate `StaffRepository.verifyPin`): duration 1 h / 4 h / 24 h (default 4 h); consent text:

> Start a BNM support session? For the next {duration}, BNM's engineer can see this computer's diagnostics — app and analyzer status, analyzer settings, the activity log and record counts — and can change analyzer settings, restart analyzer links and start an app update. Every action is recorded in Support history. They cannot see patient records, results or analyzer data unless you allow it below. You can end the session at any time.
> ☐ Share analyzer data (raw frames from the analyzer — these carry sample IDs and, on some analyzers, patient names)
> ☐ Allow looking up records (patient names and results)
> ☐ Allow screen view (pictures of what is on this screen)

"Start session" → the dialog shows the **session code** (`XXXX-XXXX`, Crockford base32 without I/L/O/U) large, with "Read this code to the BNM engineer". A persistent banner (beside `ReadOnlyBanner`, all screens): "BNM support session · code XXXX-XXXX · ends 18:30 · 3 actions · [End]" — colour amber; green dot when the engineer is connected. The dialog also shows the live status and a "Support history" list (last 50 actions: time, tool, short summary, ok/refused). End = immediate close on both sides.

Standalone edition: allowed because the OWNER pressed it — add `OfflinePolicy.allowsRemoteSupport(userInitiated: Boolean) = userInitiated` and extend the summary text: "…only when you ask for it, and a remote support session only while you have started one."

Copy is plain and for non-technical staff. Times formatted like the rest of the app.

## 2. Session engine (desktopMain `com.bnm.lab.remote`)

`RemoteSupportService` implements `RemoteSupportController` (singleton, `platformRemoteSupportController()` returns it; `start()` called from `Main.kt` after `DesktopDiagnostics.install()`; tool hosts and the audit store are attached from `App.kt` once the DB/engine exist).

Start(consent, duration, startedBy):
1. `sessionId = uuid4`, `code = 8 Crockford chars from SecureRandom` (display `XXXX-XXXX`), `codeHash = sha256hex("bnm-lab-support|" + code)`; expiry = monotonic start + duration (never wall-clock; show wall-clock only for display).
2. Connect `wss://<relay>/v1/lab` (Ktor websockets). First frame: `{"t":"hello","v":1,"jwt":<license_jwt>,"session_id":…,"code_hash":…,"lab":<lab name>,"app_version":…,"expires_in_s":…,"consent":{"analyzer_data":bool,"records":bool,"screen":bool}}`. Expect `{"t":"ready","server_time_ms":…}` (record clock skew) else fail with a short reason. Relay notices: `{"t":"peer","state":"connected"|"disconnected"}` drive `peerConnected`. `{"t":"end"}` from the lab ends the session. Reconnect with backoff (2 s → 30 s) until expiry if the socket drops; the banner shows "reconnecting".
3. Every other text frame is JSON-RPC 2.0 from the engineer. Handle: `initialize` → `{protocolVersion:"2025-06-18", capabilities:{tools:{}}, serverInfo:{name:"bnm-lab", version:<app version>}}`; `notifications/initialized` → ignore; `ping` → `{}`; `tools/list` → merged tool specs from every attached `RemoteToolHost` (MCP shape: `name, description, inputSchema, annotations{readOnlyHint, destructiveHint, title}`; tools whose consent is not granted are still listed but marked in the description "(requires: analyzer data consent)"); `tools/call` → see §3. Unknown method → JSON-RPC −32601. Malformed → −32600 (never crash the socket).
4. **Request authentication (mandatory for `tools/call`)**: the request carries `"bnm":{"ts_ms":…,"nonce":…,"sig":<base64>}`; `sig` = Ed25519 over UTF-8 of `"bnm-lab-support-v1\n" + session_id + "\n" + <json-rpc id as string> + "\n" + method + "\n" + ts_ms + "\n" + nonce + "\n" + canonical(params)` where `canonical` = JSON with object keys sorted recursively, no whitespace, numbers as sent (kotlinx: rebuild `JsonObject` with sorted keys and `Json { }.encodeToString`; Node: recursive sort + `JSON.stringify`). Verify with the embedded support public key (`RemoteSupportKeys.kt`: `SUPPORT_PUBLIC_KEY_SPKI_B64` — X.509 SubjectPublicKeyInfo DER, base64; `KeyFactory.getInstance("Ed25519")` + `X509EncodedKeySpec`). Refuse when: bad signature, `|ts_ms − now| > 5 min` (after skew correction), nonce seen before in this session, session expired, or consent missing for the tool. Refusals are JSON-RPC errors with code −32001 and a short message, and are audited too.
5. Dispatch: look up the tool host by name; `call(ToolCall)`; wrap the result as MCP `{content:[{type:"text",text:…}], isError:false}` (or `{type:"image", data, mimeType}` for the screenshot). Increment `actions`, write `SupportAuditRow` (session id, at, tool, args summary ≤ 200 chars WITHOUT PHI — the host supplies `summaryForAudit`, ok/refused/failed, duration ms), `AppLog.i("RemoteSupport", "<tool> ok 120ms")`.
6. Expiry / End / owner logout: send `{"t":"end"}`, close, phase OFF; keep the audit. `RemoteSupportStatus` updates on every transition.

Limits: one engineer at a time (the relay enforces; the app also refuses a second `initialize` while one peer is connected). Max message 900 KB (screenshots are downscaled to fit). Never log message bodies; never log the code (log only `session <8 chars of id> started by <staff id prefix>`).

## 3. Tool vocabulary (v1) — `RemoteTools` (commonMain, implements `RemoteToolHost`)

Every tool: JSON input schema, `readOnly`/`destructive` annotations, consent requirement, an audit summary. PHI rules apply regardless of consent unless stated.

Read (no consent beyond the session):
- `lab.overview` — lab name, edition, licence standing/mode/expiry, app version, `environmentLine()`, uptime, DB size, disk free, `tenantRowCounts()`, local IPv4 addresses (`NetworkInterface`, non-loopback), timezone, local time and clock skew vs relay, backup status if `platformBackupController()` exists (reflection-free: it is in this repo only on another branch — call `platformBackupController()?.status?.value` guarded by null; if the symbol does not exist on this branch, omit and note it).
- `instruments.list` — every `instruments` row (id, name, driver_key, transport, serial_port, baud, tcp_port, enabled, param_map_json, verify_pending) + live `InstrumentStatus` + NEW counters (see §4).
- `instruments.log` — `{instrument_id?, since_id?, limit ≤ 200, include_raw: false}` from `instrument_log`; `summary` passes through `FrameScrubber.maskIds` (specimen/patient ids → shape + last 4, e.g. `A***1234`) unless consent ANALYZER_DATA; `raw` only when consent ANALYZER_DATA AND `include_raw`, and then through `FrameScrubber.scrubRaw(driverKey, raw)`: HL7 → blank PID-5 (name), PID-7 (DOB), PID-8, PID-11, PID-13, PID-19, NK1 entirely, OBX-5 when type is ST/TX/FT; Mispa → blank field 3 (PatientID); keep everything else (values, ids, structure — that is what debugging needs). Unknown driver → summaries only.
- `instruments.unmatched` — `instrument_results` with status unmatched: id, instrument, received_at, specimen id (masked unless ANALYZER_DATA), reason, param keys present. Never values.
- `instruments.ports` — serial ports (`SerialPortIO.listPorts()` names + descriptions) with `held_by` (instrument id when an enabled instrument owns it); TCP: configured ports, `bound` (from status), local IPs.
- `instruments.probe` — `{serial_port}` OR `{tcp_host, tcp_port}`: serial = open/close only, zero reads, REFUSED if any enabled instrument owns the port (say so); tcp = connect from the PC with 3 s timeout (to check the analyzer host answers). readOnly.
- `instruments.dry_run` — `{instrument_id, frame_text}`: parse with the instrument's driver and compute the mapping against the ordered test's parameters WITHOUT routing or writing: returns parsed keys/units, matched-order LOOKUP RESULT ONLY as `would_match: true|false` + accession shape (masked), unmapped keys, unit mismatches. Specimen ids starting `BNMTEST-` are always treated as non-matching. Never touches the claim queue.
- `catalog.tests` — `lab_tests` id, name, code, parameter keys + units (from `parameters_json`) — no prices.
- `db.query` — `{sql, max_rows ≤ 200}`: opens its own read-only connection (`SQLiteConfig().setReadOnly(true)` / `open_mode=1`, `busy_timeout=5000`, 30 s query timeout), refuses anything that is not a single statement starting with `SELECT`, `WITH`, `EXPLAIN` or `PRAGMA` (read pragmas only), refuses `;` chains, and — without consent RECORDS — refuses any SQL whose identifiers (case-insensitive word match) include `patients, lab_orders, lab_order_tests, lab_results, instrument_results, lab_result_graphs, emr_inbox, ecom_entity, lab_reports, referrer_payouts, billing_outbox`. Returns columns + rows as JSON, truncated flag. Strings longer than 2 000 chars are cut.
- `logs.tail` — `{lines ≤ 500, day?: "yyyy-MM-dd"}` from the rolling log files (already redacted) + `DiagnosticsContext.render()` when `lines` omitted.
- `app.screenshot` — consent SCREEN; desktopMain host: `java.awt.Robot().createScreenCapture(window bounds)` of the Compose window (register the `ComposeWindow` from `Main.kt`), downscale to ≤ 1280 px wide, PNG, ≤ 700 KB (else JPEG 80); returned as MCP image content. Audited as "screenshot".
- `session.info` — consent, remaining seconds, actions, peer state.

Write (destructive annotations; the bridge passes them through so Claude asks the engineer first):
- `instruments.restart` — `{instrument_id?, force:false}`: per-instrument restart (NEW `restart(id)`, §4) or all; REFUSED (with reason) when a frame arrived on that instrument in the last 10 s unless `force` (Mispa has no retransmit).
- `instruments.set_config` — `{id?, name, driver_key, transport, serial_port?, baud?, tcp_port?, enabled, param_map_json?}`: validates (driver known, transport ∈ serial|tcp, port/baud/tcp_port sane, param_map JSON object of string→string, no duplicate serial port across enabled instruments), upserts via the engine (`saveInstrument` — which must now restart ONLY that instrument), and when `driver_key` or `param_map_json` changed sets `verify_pending = 1` (§4). Audit summary: field names that changed, never the map contents.
- `instruments.delete` — `{id}` (destructive; audited).
- `app.update.prefetch` — `{version?}`: standalone-safe because the OWNER started the session; runs the existing `UpdateChecker` + `UpdateInstaller.downloadAndLaunchInstaller` path so the installer is downloaded, verified and the OS install prompt appears on the lab PC (a person at the PC must click); returns what happened.
- `session.end`.

NEVER in v1 (say so in the code doc): result entry, claim/discard of unmatched results, staff/PIN changes, licence changes, arbitrary SQL writes, file system, shell, printing.

## 4. Engine hardening (commonMain `InstrumentEngine`, `Instruments.sq`, `InstrumentsScreen`)

- Counters per instrument, in the status: `bytesIn`, `framesIn`, `framesParsed`, `framesApplied`, `framesUnmatched`, `framesIgnored`, `acksSent`, `lastErrorAt`, `lastError`, `peerIp` (TCP), `boundAt`. Extend `InstrumentStatus` with defaults so existing code compiles.
- `restart(id)` — restart exactly one listener under `restartMutex`; `saveInstrument`/`deleteInstrument` use it (never `restartAll` for one row). Refuse-when-busy lives in the tool, not the engine.
- Self-heal: while an enabled instrument's state is `error` (serial open failed / disconnected, TCP bind failed), retry opening every 10 s (log once per transition, not per attempt). This alone removes the commonest "not linking" ticket (USB-serial re-plugged).
- Parse failure visibility: a driver returning null for a frame that looked like a frame → `error` log row "Frame not understood by <driver>" with a masked 120-char excerpt; count `framesIgnored`.
- tx logging: on each ACK/NAK sent, an `info` row "ACK sent" / "NAK sent" (no raw), count `acksSent`.
- `verify_pending`: new column `instruments.verify_pending INTEGER NOT NULL DEFAULT 0` (appended LAST + addColumn). When 1, `applyFrameToOrder` is skipped and the frame is `queueUnmatched(reason = "Analyzer settings changed by support — check one known sample, then press Verified")`. `InstrumentsScreen` shows an amber "Settings changed by BNM support — Verified?" row per pending instrument with a **Verified** button (any signed-in staff; clears the flag, audit row `instruments.verified`).
- `dryRun(cfg, frameText)` — pure: driver parse + mapping computation reused by `applyFrameToOrder` (refactor the mapping selection into a pure function so both use it).

## 5. Audit (commonMain SQLDelight)

Table `support_audit(id TEXT PK, session_id TEXT, at TEXT, tool TEXT, summary TEXT, outcome TEXT, ms INTEGER, started_by TEXT)` + `support_sessions(id TEXT PK, started_at TEXT, ended_at TEXT?, duration_s INTEGER, consent_json TEXT, started_by TEXT, started_by_name TEXT, end_reason TEXT?)`. Mirrored in `AppDatabaseFactory`. NOT in `TenantReset` (the lab's audit trail outlives a tenant switch — say so in the .sq header). `SupportAuditStore` implementation over these tables. Settings ▸ App ▸ Support history reads them.

## 6. Relay (`relay/`, Cloudflare Worker, TypeScript, wrangler)

- `wrangler.toml`: name `bnm-lab-relay`, `compatibility_date` recent, `[[durable_objects.bindings]] name=SESSIONS class=LabSession`, `name=DIRECTORY class=SessionDirectory`, `[[migrations]] new_sqlite_classes` for both. Secrets: `BNM_SUPPORT_TOKEN` (engineer token), optional `LAB_LICENSE_PUBLIC_JWK` (defaults to the JWK in `docs/license-public-key.jwk.json`, copied into `src/keys.ts`). `.dev.vars.example` documents them.
- `GET /v1/lab` (Upgrade): DO `LabSession` id = `idFromName(session_id)` after validating the `hello` frame: JWT signature ES256 over `header.payload` with the public JWK (WebCrypto ECDSA P-256 SHA-256; JWS raw r||s), `iss == "bnm-lab-license"`, `session_id` uuid, `code_hash` 64 hex, `expires_in_s ≤ 86400`. Expired licences are fine (support is not a licence check). Register `code_hash → session_id, expires_at` in `SessionDirectory` (single global DO, `idFromName("v1")`), reply `ready` with `server_time_ms`.
- `GET /v1/support` (Upgrade): `Authorization: Bearer <BNM_SUPPORT_TOKEN>` (constant-time compare) else 401; first frame `{"t":"join","code":…}` → normalise code (uppercase, strip dashes/spaces, I/L→1, O→0) → hash → directory lookup → connect to that `LabSession` DO; wrong/expired code → `{"t":"error","code":"no_session"}` and close; rate limit 10 joins/min per token (directory DO counter) → `429`. One support peer per session; a second `join` gets `{"t":"error","code":"busy"}`.
- `LabSession` DO: WebSocket Hibernation API; holds ≤ 1 lab + ≤ 1 support socket; forwards text frames verbatim in both directions; injects `{"t":"peer","state":…}` to the lab on support connect/disconnect and `{"t":"peer","state":"lab_disconnected"}` to support; enforces 1 MiB frame limit (close 1009); auto-close both at `expires_at` (alarm); `{"t":"end"}` from the lab closes support and deletes the directory entry. Logs: session open/close/pair events and byte counts only — never bodies, never the code.
- Tests: vitest with `@cloudflare/vitest-pool-workers`: JWT valid/invalid/wrong-iss, hello validation, directory pairing, wrong token 401, bad code, busy, both-way piping, size limit, expiry alarm, end. Local run: `npm test`, `wrangler dev` (no deploy).

## 7. Bridge (`tools/remote-mcp/`)

- `bnmlab-remote.mjs` (Node ≥ 22, zero dependencies; global `WebSocket`; `node:crypto` Ed25519): stdio MCP server, newline-delimited JSON-RPC, protocol `2025-06-18`, `serverInfo {name:"bnmlab-remote"}`. Tools:
  - `lab_connect {code}` → WSS to `$BNM_RELAY_URL/v1/support` with `Authorization: Bearer $BNM_SUPPORT_TOKEN`, `join`, then forwards `initialize` to the lab and returns lab name / app version / consent / expiry.
  - `lab_status` → connection state, peer, remaining time, actions this session.
  - `lab_tools` → the lab's `tools/list` result (name, description, schema, annotations) — the engineer reads the annotations before calling destructive tools.
  - `lab_call {tool, args}` → signs (§2.4) with the key file `$BNM_SUPPORT_KEY_FILE` (default `~/.config/bnmlab-remote/support.key`, PEM PKCS#8 Ed25519), forwards `tools/call`, returns the lab's content verbatim (text and image).
  - `lab_disconnect`.
  - Annotations: `lab_call` is marked `destructiveHint: true` (the lab's per-tool annotation is what the engineer must read).
- `bnmlab-remote.mjs keygen` → writes the key file (0600) and prints the SPKI base64 to paste into `RemoteSupportKeys.kt`. A dev keypair for tests lives in `tools/remote-mcp/test/`; the app ships the DEV public key on this branch with a loud `// REPLACE before release` and a test that fails if the key equals the dev key when `BuildInfo.VERSION` does not end in `-dev`… (simplest: a `RemoteSupportKeys.isDevKey` check surfaced in `lab.overview` as `support_key: dev|prod`).
- Tests (`node --test`): framing, tools/list, signing canonicalisation vectors (shared JSON fixture `test/canonical-vectors.json` that the Kotlin test ALSO loads — same input → same canonical string and hash), forwarding against an in-process fake relay (a minimal WebSocket server is not in node core — implement the fake at the `WebSocket`-object level via an injectable factory).
- `README.md`: install (`claude mcp add bnmlab-remote -e BNM_RELAY_URL=wss://… -e BNM_SUPPORT_TOKEN=… -- node /abs/path/bnmlab-remote.mjs`), a session walkthrough, the safety rule (read annotations; ask before destructive), and what the lab sees.

## 8. Constants, config, docs

- `api/Constants.kt`: `REMOTE_RELAY_URL = "wss://lab-relay.bnmapp.com"` (placeholder; overridable by env `BNM_RELAY_URL` for dev, read in `RemoteSupportService`).
- `docs/remote-support/RUNBOOK.md`: how a session runs end to end (lab side, engineer side, what to do when the analyzer is not linking — a checklist: firewall, IP, port, baud, driver, mapping, verify_pending).
- CLAUDE.md: Remote support section (consent gate, PHI rules, never add a tool that writes clinical data, key rotation).

## 9. Tests (desktopTest unless noted)

- `RemoteRpcTest`: JSON-RPC handling over an in-memory transport (no sockets): initialize/ping/list/call, bad JSON, unknown method, unsigned call refused, bad signature, stale ts, nonce replay, expired session, consent gating per tool, audit rows written with PHI-free summaries.
- `CanonicalJsonTest`: vectors shared with the bridge.
- `FrameScrubberTest`: HL7 (Mindray sample from `MindrayBc5xTest`) → PID-5/7/8/11/13/19 blank, NK1 dropped, OBX numeric kept, ST text blanked; Mispa → PatientID blank, values kept; id masking.
- `DbQueryGuardTest`: refusals (INSERT, `;` chain, PHI table without consent, PRAGMA writes), row cap, read-only connection cannot write.
- `InstrumentEngineRemoteTest`: counters move on a fake transport; `restart(id)` touches only that id; self-heal reopens after a failed open; verify_pending queues as unmatched; dry-run never writes; ACK rows logged.
- `RemoteSupportUiTest` (ImageComposeScene, one scene per test): consent dialog, code display, banner states, Support history.
- `OfflinePolicyTest` gains `allowsRemoteSupport`.
- Existing suite stays green: `./gradlew :composeApp:desktopTest :composeApp:compileDebugKotlinAndroid`.
- Relay + bridge tests as in §6/§7. E2E smoke (merge step): `wrangler dev` relay + a Kotlin `RemoteSupportE2ETest` (opt-in via `-Dbnm.e2e=true`, using a TEST licence keypair the relay is started with via `LAB_LICENSE_PUBLIC_JWK`) + the bridge driven by a node script: connect, list tools, call `lab.overview`, refuse an unsigned call, end.

## 10. Out of scope (v1) — note in docs

Persistent health beacon for connected labs, Studio console, silent-device alerts, RustDesk/remote desktop hand-off, offline (QR) config path, support-zip upload, result claiming from remote.
