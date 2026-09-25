# bnm-lab-relay

The relay for BNM Lab **Remote support** (contract: `docs/remote-support/CONTRACT.md` §6).
A Cloudflare Worker plus two Durable Object classes that pair one lab
connection with one engineer connection per session and forward text frames
verbatim. It never reads, stores or logs message bodies, and never sees the
spoken code in clear.

```
relay/
  src/index.ts       routes; the front Worker that seats sockets
  src/session.ts     LabSession DO — one per session, two seats, the pipe
  src/directory.ts   SessionDirectory DO — code hash → session, join budget
  src/protocol.ts    frames, hello/join validation, code normalisation, close codes
  src/jwt.ts         ES256 licence-JWT verification (WebCrypto)
  src/keys.ts        the production licence public JWK (= docs/license-public-key.jwk.json)
  test/              vitest (@cloudflare/vitest-pool-workers) + the TEST licence keypair
  scripts/           mint-test-jwt.mjs, gen-test-keypair.mjs
```

## How a session runs

1. The app (owner pressed *Start session*) opens `wss://<relay>/v1/lab` and sends
   `{"t":"hello","v":1,"jwt":<licence>,"session_id":<uuid>,"code_hash":<sha256hex>,
   "expires_in_s":…, …}` as its first frame. The relay verifies the licence
   signature (ES256) and issuer (`bnm-lab-license`) — an *expired* licence is
   fine, support is not a licence check — registers `code_hash → session_id`
   in the directory and answers `{"t":"ready","server_time_ms":…}`.
2. The engineer's bridge opens `wss://<relay>/v1/support` with
   `Authorization: Bearer <BNM_SUPPORT_TOKEN>` and sends
   `{"t":"join","code":"XXXX-XXXX"}`. The code is normalised (uppercase, dashes
   and spaces dropped, I/L → 1, O → 0), hashed with the app's formula
   (`sha256("bnm-lab-support|" + code)`) and looked up. Wrong or expired code:
   `{"t":"error","code":"no_session"}` and close 4404. A second engineer:
   `{"t":"error","code":"busy"}` and close 4409.
3. From then on every text frame is forwarded unchanged. The relay only injects
   `{"t":"peer","state":"connected"|"disconnected"}` to the lab and
   `{"t":"peer","state":"lab_connected"|"lab_disconnected"}` to the engineer.
4. `{"t":"end"}` from the lab closes both seats (1000) and forgets the code. At
   `expires_in_s` an alarm does the same (4410). Frames over 1 MiB close the
   sender with 1009; binary frames with 1003.

A lab that reconnects (same `session_id`, same code, a fresh valid hello) takes
the seat over; the engineer stays connected and is told `lab_connected`.

### Session id in the URL (recommended for the app)

A Durable Object can only take a WebSocket that was routed to it at upgrade
time, and the hello frame naming the session arrives *after* the upgrade. So
for plain `GET /v1/lab` the front Worker accepts the socket, waits for the
hello, opens a second socket to the right `LabSession` and pipes the two.
That works, but the front Worker then holds the connection for the whole
session. If the app instead connects to `wss://<relay>/v1/lab?session=<uuid>`
the upgrade goes straight to its Durable Object and the seat hibernates
between frames — nothing is held in a Worker. The hello is validated the same
way in both cases (`session_mismatch` if the URL and the frame disagree).

`/v1/support` always uses the peek-and-pipe path (the code is in the first
frame).

### Close codes

| code | meaning |
|------|---------|
| 1000 | session ended by the lab (`end`) |
| 1003 | binary frame |
| 1009 | frame over 1 MiB |
| 4001 | this lab seat was replaced by a newer connection for the same session |
| 4400 | malformed hello |
| 4401 | licence signature or issuer wrong |
| 4404 | no such session (bad/expired code) |
| 4408 | silent socket (no hello / join within `HELLO_TIMEOUT_MS`) |
| 4409 | busy / `code_in_use` / `session_mismatch` |
| 4410 | session expired |

## Local run

```
npm ci
npm test                 # vitest inside the Workers runtime (workerd), 60+ tests
npm run typecheck
cp .dev.vars.example .dev.vars   # then set BNM_SUPPORT_TOKEN
npm run dev              # wrangler dev --local, http://localhost:8787
curl -i localhost:8787/           # 404
curl -i localhost:8787/v1/health  # {"ok":true}
```

### Driving it with the TEST licence key (E2E)

`wrangler dev` verifies licences with the production key unless
`LAB_LICENSE_PUBLIC_JWK` is set. For a local end-to-end run use the throw-away
keypair in `test/` — `test/license-test.public.jwk.json` is the public half,
`test/license-test.private.jwk.json` signs test tokens. Both are committed on
purpose; they sign nothing real.

```
# .dev.vars
BNM_SUPPORT_TOKEN=dev-token
LAB_LICENSE_PUBLIC_JWK={"kty":"EC","x":"…","y":"…","crv":"P-256"}   # paste test/license-test.public.jwk.json on one line
```

or without a file:

```
npx wrangler dev --local \
  --var BNM_SUPPORT_TOKEN:dev-token \
  --var "LAB_LICENSE_PUBLIC_JWK:$(cat test/license-test.public.jwk.json)"
```

Mint a licence JWT the relay will accept:

```
JWT=$(node scripts/mint-test-jwt.mjs --lab "Demo Lab")          # standalone, valid a year
node scripts/mint-test-jwt.mjs --expired                        # accepted too (support ≠ licence check)
node scripts/mint-test-jwt.mjs --iss other                      # refused: bad_jwt
node scripts/mint-test-jwt.mjs --help
```

The app side of the E2E (`RemoteSupportE2ETest`, opt-in with `-Dbnm.e2e=true`)
mints that JWT itself by running this script (or takes `BNM_E2E_LICENSE_JWT`),
hands it to the engine through its constructor seam — never the real prefs —
and spawns the bridge with `BNM_RELAY_URL` / `BNM_SUPPORT_TOKEN=dev-token`:

```
BNM_RELAY_URL=ws://127.0.0.1:8787 ./gradlew :composeApp:desktopTest -Dbnm.e2e=true \
    --tests 'com.bnm.lab.remote.RemoteSupportE2ETest'
```

Regenerate the test keypair only if you must (`npm run gen-test-keypair -- --force`);
the tests and the E2E docs refer to the committed one.

## Secrets and deploy

Never deploy from a feature branch. When the time comes:

```
npx wrangler secret put BNM_SUPPORT_TOKEN        # long random string; the engineer's bridge gets the same value
# LAB_LICENSE_PUBLIC_JWK: leave unset in production — src/keys.ts carries the real key
npm run deploy                                    # wrangler deploy
```

Rotating the engineer token = `secret put` again + update the engineer's
`claude mcp add … -e BNM_SUPPORT_TOKEN=…`. Rotating the licence key = change
`docs/license-public-key.jwk.json`, mirror it into `src/keys.ts` (a test
enforces they match) and deploy.

Limits worth knowing: 10 support joins per minute per token (HTTP 429),
sessions ≤ 24 h, frames ≤ 1 MiB, one engineer per session. Logs carry only
event names, the first 8 characters of a session id, close codes and byte
counts — never bodies, codes, tokens or licences.

## Out of scope here

Anything beyond a pipe: no message inspection, no persistence of frames, no
health beacon, no console. See the contract §10.
