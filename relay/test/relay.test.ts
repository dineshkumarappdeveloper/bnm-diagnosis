/**
 * End-to-end through the Worker under test: the app's side (/v1/lab) and the
 * engineer's side (/v1/support) as real WebSocket clients, one LabSession
 * Durable Object between them.
 */

import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { afterEach, describe, expect, it } from "vitest";
import { Close, MAX_FRAME_BYTES, codeHashOf, normaliseCode } from "../src/protocol";
import { JOIN_LIMIT } from "../src/directory";
import {
  BASE,
  CODE,
  TOKEN,
  cleanup,
  directoryStub,
  helloFrame,
  joinSupport,
  licenseJwt,
  open,
  pair,
  resetJoinBudget,
  sessionStubOf,
  sleep,
  startLab,
  track,
} from "./helpers";
import { SELF } from "cloudflare:test";

afterEach(cleanup);

describe("routes", () => {
  it("answers 404 on GET / and anything unknown", async () => {
    expect((await SELF.fetch(BASE + "/")).status).toBe(404);
    expect((await SELF.fetch(BASE + "/v1")).status).toBe(404);
    expect((await SELF.fetch(BASE + "/v1/lab/extra")).status).toBe(404);
  });

  it("has a health endpoint", async () => {
    const res = await SELF.fetch(BASE + "/v1/health");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
  });

  it("wants an upgrade on the socket routes", async () => {
    expect((await SELF.fetch(BASE + "/v1/lab")).status).toBe(426);
    expect((await SELF.fetch(BASE + "/v1/support", { headers: { Authorization: `Bearer ${TOKEN}` } })).status).toBe(426);
    expect((await SELF.fetch(BASE + "/v1/lab", { method: "POST" })).status).toBe(405);
  });

  it("rejects a non-uuid session in the URL before touching any object", async () => {
    const res = await SELF.fetch(BASE + "/v1/lab?session=abc", { headers: { Upgrade: "websocket" } });
    expect(res.status).toBe(400);
  });
});

describe("lab hello", () => {
  it("answers ready with the server clock (hello as first frame)", async () => {
    const { ready } = await startLab();
    expect(typeof ready.server_time_ms).toBe("number");
    expect(Math.abs((ready.server_time_ms as number) - Date.now())).toBeLessThan(5000);
  });

  it("answers ready when the session id is in the URL", async () => {
    const { ready } = await startLab({ viaUrl: true });
    expect(ready.t).toBe("ready");
  });

  it("refuses a bad licence with bad_jwt and close 4401", async () => {
    const sessionId = crypto.randomUUID();
    track(sessionId);
    const { sock } = await open("/v1/lab");
    const jwt = await licenseJwt();
    sock!.send(helloFrame(jwt.slice(0, -6) + "AAAAAA", sessionId, "a".repeat(64)));
    expect(await sock!.json()).toEqual({ t: "error", code: "bad_jwt" });
    expect((await sock!.closed).code).toBe(Close.BAD_LICENSE);
  });

  it("refuses the wrong issuer", async () => {
    const sessionId = crypto.randomUUID();
    track(sessionId);
    const { sock } = await open("/v1/lab");
    sock!.send(helloFrame(await licenseJwt({ iss: "bnm-admin" }), sessionId, "a".repeat(64)));
    expect(await sock!.json()).toEqual({ t: "error", code: "bad_jwt" });
    expect((await sock!.closed).code).toBe(Close.BAD_LICENSE);
  });

  it("refuses a first frame that is not a hello", async () => {
    const { sock } = await open("/v1/lab");
    sock!.send({ jsonrpc: "2.0", id: 1, method: "ping" });
    expect(await sock!.json()).toEqual({ t: "error", code: "bad_hello" });
    expect((await sock!.closed).code).toBe(Close.BAD_FRAME);
  });

  it("refuses a hello that names a different session than the URL", async () => {
    const urlSession = crypto.randomUUID();
    track(urlSession);
    const { sock } = await open(`/v1/lab?session=${urlSession}`);
    sock!.send(helloFrame(await licenseJwt(), crypto.randomUUID(), "a".repeat(64)));
    expect(await sock!.json()).toEqual({ t: "error", code: "session_mismatch" });
    expect((await sock!.closed).code).toBe(Close.CONFLICT);
  });

  it("refuses a hello whose expiry is over a day", async () => {
    const sessionId = crypto.randomUUID();
    track(sessionId);
    const { sock } = await open("/v1/lab");
    sock!.send(helloFrame(await licenseJwt(), sessionId, "a".repeat(64), 86_401));
    expect(await sock!.json()).toEqual({ t: "error", code: "bad_expiry" });
    expect((await sock!.closed).code).toBe(Close.BAD_FRAME);
  });

  it("closes a socket that never says hello (front Worker)", async () => {
    const { sock } = await open("/v1/lab");
    const closed = await sock!.closed;
    expect(closed.code).toBe(Close.TIMEOUT);
  });

  it("closes a socket that never says hello (Durable Object alarm)", async () => {
    const sessionId = crypto.randomUUID();
    track(sessionId);
    const { sock } = await open(`/v1/lab?session=${sessionId}`);
    const stub = sessionStubOf(sessionId);
    const alarmAt = await runInDurableObject(stub, (_i, state) => state.storage.getAlarm());
    expect(alarmAt).not.toBeNull();
    await sleep(300); // HELLO_TIMEOUT_MS is 250 in tests
    await runDurableObjectAlarm(stub);
    expect((await sock!.closed).code).toBe(Close.TIMEOUT);
  });

  it("refuses a code already held by another live session", async () => {
    const first = await startLab({ code: "AAAA-BBBB" });
    const sessionId = crypto.randomUUID();
    track(sessionId);
    const { sock } = await open("/v1/lab");
    sock!.send(helloFrame(await licenseJwt(), sessionId, first.codeHash));
    expect(await sock!.json()).toEqual({ t: "error", code: "code_in_use" });
    expect((await sock!.closed).code).toBe(Close.CONFLICT);
    expect(first.lab.isOpen()).toBe(true);
  });
});

describe("support join", () => {
  it("is 401 without the engineer token, or with the wrong one", async () => {
    expect((await SELF.fetch(BASE + "/v1/support", { headers: { Upgrade: "websocket" } })).status).toBe(401);
    const wrong = await SELF.fetch(BASE + "/v1/support", { headers: { Upgrade: "websocket", Authorization: "Bearer nope" } });
    expect(wrong.status).toBe(401);
    expect(wrong.headers.get("WWW-Authenticate")).toContain("Bearer");
    const prefix = await SELF.fetch(BASE + "/v1/support", { headers: { Upgrade: "websocket", Authorization: `Bearer ${TOKEN}x` } });
    expect(prefix.status).toBe(401);
  });

  it("answers no_session for a code nobody registered", async () => {
    const { sock } = await joinSupport("ZZZZ-ZZZZ");
    expect(await sock!.json()).toEqual({ t: "error", code: "no_session" });
    expect((await sock!.closed).code).toBe(Close.NO_SESSION);
  });

  it("answers no_session for a code that cannot be a code, and for a non-join first frame", async () => {
    const a = await joinSupport("hello");
    expect(await a.sock!.json()).toEqual({ t: "error", code: "no_session" });
    expect((await a.sock!.closed).code).toBe(Close.NO_SESSION);

    const { sock } = await open("/v1/support", { Authorization: `Bearer ${TOKEN}` });
    sock!.send({ jsonrpc: "2.0", id: 1, method: "initialize" });
    expect(await sock!.json()).toEqual({ t: "error", code: "no_session" });
  });

  it("closes a support socket that never joins", async () => {
    const { sock } = await open("/v1/support", { Authorization: `Bearer ${TOKEN}` });
    expect((await sock!.closed).code).toBe(Close.TIMEOUT);
  });

  it("pairs with the lab and tells it so", async () => {
    const lab = await startLab();
    const support = await pair(lab);
    expect(support.isOpen()).toBe(true);
  });

  it("forgives how the code was typed: lowercase, spaces, I/L/O", async () => {
    const lab = await startLab({ code: "H1L0-ABCD" }); // stored as H1L0ABCD → normalised H110ABCD
    expect(normaliseCode("H1L0-ABCD")).toBe("H110ABCD");
    expect(lab.codeHash).toBe(await codeHashOf("H110ABCD"));
    const { sock } = await joinSupport(" hIl o abcd ");
    expect(await lab.lab.json()).toEqual({ t: "peer", state: "connected" });
    expect(sock!.isOpen()).toBe(true);
  });

  it("seats one engineer at a time: the second join is busy", async () => {
    const lab = await startLab();
    const first = await pair(lab);
    const { sock: second } = await joinSupport(lab.code);
    expect(await second!.json()).toEqual({ t: "error", code: "busy" });
    expect((await second!.closed).code).toBe(Close.CONFLICT);
    expect(first.isOpen()).toBe(true);
    expect(lab.lab.isOpen()).toBe(true);
  });

  it("frees the seat when the engineer leaves", async () => {
    const lab = await startLab();
    const first = await pair(lab);
    first.ws.close(1000, "bye");
    expect(await lab.lab.json()).toEqual({ t: "peer", state: "disconnected" });
    const again = await pair(lab);
    expect(again.isOpen()).toBe(true);
  });

  it(`rate-limits joins: the ${JOIN_LIMIT + 1}th upgrade in a minute is 429`, async () => {
    await resetJoinBudget();
    for (let i = 0; i < JOIN_LIMIT; i++) {
      const { res } = await open("/v1/support", { Authorization: `Bearer ${TOKEN}` });
      expect(res.status).toBe(101);
    }
    const res = await SELF.fetch(BASE + "/v1/support", { headers: { Upgrade: "websocket", Authorization: `Bearer ${TOKEN}` } });
    expect(res.status).toBe(429);
    expect(res.headers.get("Retry-After")).toBe("60");
  });
});

describe("piping", () => {
  it("forwards text frames verbatim in both directions", async () => {
    const lab = await startLab();
    const support = await pair(lab);

    const request = JSON.stringify({ jsonrpc: "2.0", id: "r-1", method: "tools/call", params: { name: "lab.overview", arguments: {} }, bnm: { ts_ms: 1, nonce: "n", sig: "s" } });
    support.send(request);
    expect(await lab.lab.next()).toBe(request);

    const response = JSON.stringify({ jsonrpc: "2.0", id: "r-1", result: { content: [{ type: "text", text: "Sunrise Diagnostics · ok ✓ €" }] } });
    lab.lab.send(response);
    expect(await support.next()).toBe(response);

    // Ordering and a big-but-legal frame (a downscaled screenshot is ~700 KB).
    const big = "p".repeat(700 * 1024);
    lab.lab.send("first");
    lab.lab.send(big);
    lab.lab.send("last");
    expect(await support.next()).toBe("first");
    expect(await support.next()).toBe(big);
    expect(await support.next()).toBe("last");
  });

  it("does the same when the lab is routed by URL", async () => {
    const lab = await startLab({ viaUrl: true });
    const support = await pair(lab);
    support.send("ping-from-support");
    expect(await lab.lab.next()).toBe("ping-from-support");
    lab.lab.send("pong-from-lab");
    expect(await support.next()).toBe("pong-from-lab");
  });

  it("counts bytes per side in the session row and never stores bodies", async () => {
    const lab = await startLab();
    const support = await pair(lab);
    support.send("12345");
    await lab.lab.next();
    lab.lab.send("€€"); // 6 bytes
    await support.next();
    await runInDurableObject(sessionStubOf(lab.sessionId), (_i, state) => {
      const row = state.storage.sql.exec<{ lab_bytes: number; support_bytes: number }>("SELECT lab_bytes, support_bytes FROM session").one();
      expect(row).toEqual({ lab_bytes: 6, support_bytes: 5 });
      const everything = JSON.stringify(state.storage.sql.exec("SELECT * FROM session").toArray());
      expect(everything).not.toContain("12345");
      expect(everything).not.toContain("€€");
    });
  });

  it("tells the engineer when the lab drops, and re-pairs when it comes back", async () => {
    const lab = await startLab();
    const support = await pair(lab);
    lab.lab.ws.close(1001, "network gone");
    expect(await support.json()).toEqual({ t: "peer", state: "lab_disconnected" });
    expect(support.isOpen()).toBe(true);

    // The app reconnects with the same session id and code.
    const back = await startLab({ sessionId: lab.sessionId, code: lab.code });
    expect(await back.lab.json()).toEqual({ t: "peer", state: "connected" });
    expect(await support.json()).toEqual({ t: "peer", state: "lab_connected" });
    support.send("still there?");
    expect(await back.lab.next()).toBe("still there?");
  });

  it("answers no_lab to the engineer while the lab is away", async () => {
    const lab = await startLab();
    const support = await pair(lab);
    lab.lab.ws.close(1001, "gone");
    expect(await support.json()).toEqual({ t: "peer", state: "lab_disconnected" });
    support.send('{"jsonrpc":"2.0","id":9,"method":"ping"}');
    expect(await support.json()).toEqual({ t: "error", code: "no_lab" });
  });

  it("lets a newer lab connection take over a stale one for the same session", async () => {
    const lab = await startLab();
    const support = await pair(lab);
    const newer = await startLab({ sessionId: lab.sessionId, code: lab.code });
    expect((await lab.lab.closed).code).toBe(Close.REPLACED);
    expect(await newer.lab.json()).toEqual({ t: "peer", state: "connected" });
    // The engineer was never told the lab left — only that it (re)connected.
    expect(await support.json()).toEqual({ t: "peer", state: "lab_connected" });
    support.send("hi");
    expect(await newer.lab.next()).toBe("hi");
  });

  it("refuses a hello with a different code for a live session", async () => {
    const lab = await startLab();
    const { sock } = await open("/v1/lab");
    sock!.send(helloFrame(await licenseJwt(), lab.sessionId, await codeHashOf("QQQQQQQQ")));
    expect(await sock!.json()).toEqual({ t: "error", code: "session_mismatch" });
    expect((await sock!.closed).code).toBe(Close.CONFLICT);
    expect(lab.lab.isOpen()).toBe(true);
  });
});

describe("limits", () => {
  it("closes a peer that sends a frame over 1 MiB with 1009", async () => {
    const lab = await startLab();
    const support = await pair(lab);
    support.send("x".repeat(MAX_FRAME_BYTES + 1));
    expect((await support.closed).code).toBe(Close.TOO_BIG);
    expect(await lab.lab.json()).toEqual({ t: "peer", state: "disconnected" });
  });

  it("closes a peer that sends binary with 1003", async () => {
    const lab = await startLab();
    const support = await pair(lab);
    support.ws.send(new Uint8Array([1, 2, 3]));
    expect((await support.closed).code).toBe(Close.UNSUPPORTED_DATA);
  });
});

describe("end and expiry", () => {
  it("end from the lab closes the engineer and forgets the code", async () => {
    const lab = await startLab();
    const support = await pair(lab);
    lab.lab.send({ t: "end" });
    const closed = await support.closed;
    expect(closed.code).toBe(Close.ENDED);
    expect(closed.reason).toBe("ended by lab");
    expect((await lab.lab.closed).code).toBe(Close.ENDED);
    expect(await directoryStub().lookup(lab.codeHash)).toBeNull();

    const { sock } = await joinSupport(lab.code);
    expect(await sock!.json()).toEqual({ t: "error", code: "no_session" });
  });

  it("the alarm closes both seats at expiry and forgets the code", async () => {
    const lab = await startLab({ expiresInS: 1 });
    const support = await pair(lab);
    const stub = sessionStubOf(lab.sessionId);

    const alarmAt = await runInDurableObject(stub, (_i, state) => state.storage.getAlarm());
    expect(alarmAt).not.toBeNull();
    expect(alarmAt! - Date.now()).toBeLessThanOrEqual(1000);
    expect(await directoryStub().lookup(lab.codeHash)).not.toBeNull();

    await sleep(1100);
    await runDurableObjectAlarm(stub); // fires it now if the runtime has not already

    const l = await lab.lab.closed;
    const s = await support.closed;
    expect(l.code).toBe(Close.EXPIRED);
    expect(s.code).toBe(Close.EXPIRED);
    expect(l.reason).toBe("session expired");
    expect(await directoryStub().lookup(lab.codeHash)).toBeNull();
    await runInDurableObject(stub, (_i, state) => {
      expect(state.storage.sql.exec("SELECT COUNT(*) AS n FROM session").one().n).toBe(0);
    });
  });

  it("an engineer cannot join an expired session even before the alarm ran", async () => {
    const lab = await startLab({ expiresInS: 1 });
    await sleep(1100);
    const { sock } = await joinSupport(lab.code);
    expect(await sock!.json()).toEqual({ t: "error", code: "no_session" });
  });
});
