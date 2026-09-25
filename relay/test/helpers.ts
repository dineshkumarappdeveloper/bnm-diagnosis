/**
 * Test-side plumbing: mint licence JWTs with the TEST private key, open
 * sockets through the Worker under test (SELF), and read frames one at a
 * time. Every test's sockets are closed and its alarms cleared in afterEach —
 * Durable Object alarms do not respect vitest's isolated storage.
 */

import { SELF, env, runInDurableObject } from "cloudflare:test";
import { expect } from "vitest";
import privateJwk from "./license-test.private.jwk.json";
import { defaultLicenseClaims, mintJwt } from "../scripts/lib/jwt-mint.mjs";
import { codeHashOf, normaliseCode } from "../src/protocol";

export const TOKEN = "test-support-token";
export const BASE = "https://relay.test";
/** A valid code: Crockford base32 without I/L/O/U. */
export const CODE = "7H3Q-K2XM";

const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/**
 * A fresh code per session, as the app generates them. Storage is NOT reset
 * between tests in this pool, so two tests sharing a code would collide in
 * the directory (`code_in_use`).
 */
export function randomCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  const chars = Array.from(bytes, (b) => CODE_ALPHABET[b % 32]).join("");
  return `${chars.slice(0, 4)}-${chars.slice(4)}`;
}

export function licenseJwt(overrides: Record<string, unknown> = {}, opts: { alg?: string } = {}): Promise<string> {
  return mintJwt(privateJwk as JsonWebKey, defaultLicenseClaims(overrides), opts);
}

export interface Sock {
  ws: WebSocket;
  send(frame: unknown): void;
  /** Next text frame, or throws after `timeoutMs`. */
  next(timeoutMs?: number): Promise<string>;
  /** Next frame parsed as JSON. */
  json(timeoutMs?: number): Promise<Record<string, unknown>>;
  /** Resolves when the socket closes. */
  closed: Promise<{ code: number; reason: string }>;
  /** True until `closed` resolves. */
  isOpen(): boolean;
}

const openSockets = new Set<Sock>();
const trackedSessions = new Set<string>();

export function wrap(ws: WebSocket): Sock {
  const queue: string[] = [];
  const waiters: Array<(s: string) => void> = [];
  let open = true;
  let resolveClosed!: (v: { code: number; reason: string }) => void;
  const closed = new Promise<{ code: number; reason: string }>((r) => (resolveClosed = r));
  ws.accept();
  ws.addEventListener("message", (ev) => {
    const text = typeof ev.data === "string" ? ev.data : `<binary ${ev.data.byteLength}>`;
    const w = waiters.shift();
    if (w) w(text);
    else queue.push(text);
  });
  ws.addEventListener("close", (ev) => {
    open = false;
    resolveClosed({ code: ev.code, reason: ev.reason });
  });
  ws.addEventListener("error", () => {
    open = false;
    resolveClosed({ code: -1, reason: "error" });
  });
  const sock: Sock = {
    ws,
    send: (frame) => ws.send(typeof frame === "string" ? frame : JSON.stringify(frame)),
    next: (timeoutMs = 3000) => {
      const queued = queue.shift();
      if (queued !== undefined) return Promise.resolve(queued);
      return new Promise<string>((resolve, reject) => {
        const timer = setTimeout(() => {
          const i = waiters.indexOf(deliver);
          if (i >= 0) waiters.splice(i, 1);
          reject(new Error(`no frame within ${timeoutMs} ms`));
        }, timeoutMs);
        const deliver = (s: string): void => {
          clearTimeout(timer);
          resolve(s);
        };
        waiters.push(deliver);
      });
    },
    json: async (timeoutMs?: number) => JSON.parse(await sock.next(timeoutMs)) as Record<string, unknown>,
    closed,
    isOpen: () => open,
  };
  openSockets.add(sock);
  return sock;
}

/** Fetch an upgrade through the Worker; `sock` is null when the response is not 101. */
export async function open(path: string, headers: Record<string, string> = {}): Promise<{ res: Response; sock: Sock | null }> {
  const res = await SELF.fetch(BASE + path, { headers: { Upgrade: "websocket", ...headers } });
  if (res.status !== 101 || !res.webSocket) return { res, sock: null };
  return { res, sock: wrap(res.webSocket) };
}

export function helloFrame(jwt: string, sessionId: string, codeHash: string, expiresInS = 3600, extra: Record<string, unknown> = {}) {
  return {
    t: "hello",
    v: 1,
    jwt,
    session_id: sessionId,
    code_hash: codeHash,
    lab: "Test Lab",
    app_version: "1.2.0-test",
    expires_in_s: expiresInS,
    consent: { analyzer_data: false, records: false, screen: false },
    ...extra,
  };
}

export interface Lab {
  sessionId: string;
  code: string;
  codeHash: string;
  lab: Sock;
  ready: Record<string, unknown>;
}

/** Open a lab seat and say hello; resolves once the relay answered `ready`. */
export async function startLab(opts: { sessionId?: string; code?: string; expiresInS?: number; viaUrl?: boolean } = {}): Promise<Lab> {
  const sessionId = opts.sessionId ?? crypto.randomUUID();
  const code = opts.code ?? randomCode();
  const codeHash = await codeHashOf(normaliseCode(code)!);
  const { res, sock } = await open(opts.viaUrl ? `/v1/lab?session=${sessionId}` : "/v1/lab");
  expect(res.status).toBe(101);
  track(sessionId);
  sock!.send(helloFrame(await licenseJwt(), sessionId, codeHash, opts.expiresInS ?? 3600));
  const ready = await sock!.json();
  expect(ready.t, JSON.stringify(ready)).toBe("ready");
  return { sessionId, code, codeHash, lab: sock!, ready };
}

/** Open a support seat and send the join frame. */
export async function joinSupport(code: string, token: string = TOKEN): Promise<{ res: Response; sock: Sock | null }> {
  const { res, sock } = await open("/v1/support", { Authorization: `Bearer ${token}` });
  if (sock) sock.send({ t: "join", code });
  return { res, sock };
}

/** Join and expect to be paired: the lab sees `peer connected`. */
export async function pair(lab: Lab): Promise<Sock> {
  const { sock } = await joinSupport(lab.code);
  expect(sock).not.toBeNull();
  expect(await lab.lab.json()).toEqual({ t: "peer", state: "connected" });
  return sock!;
}

export function track(sessionId: string): void {
  trackedSessions.add(sessionId);
}

export function sessionStubOf(sessionId: string) {
  return env.SESSIONS.get(env.SESSIONS.idFromName(sessionId));
}

export function directoryStub() {
  return env.DIRECTORY.get(env.DIRECTORY.idFromName("v1"));
}

/** Zero the per-token join budget so no test inherits another's attempts. */
export async function resetJoinBudget(): Promise<void> {
  await runInDurableObject(directoryStub(), async (_instance, state) => {
    state.storage.sql.exec("DELETE FROM joins");
  });
}

/** afterEach: close what is still open, clear every alarm this test scheduled, reset the join budget. */
export async function cleanup(): Promise<void> {
  await resetJoinBudget();
  for (const s of openSockets) {
    try {
      s.ws.close(1000, "test over");
    } catch {
      /* already closed */
    }
  }
  openSockets.clear();
  for (const id of trackedSessions) {
    await runInDurableObject(sessionStubOf(id), async (_instance, state) => {
      await state.storage.deleteAlarm();
    });
  }
  trackedSessions.clear();
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
