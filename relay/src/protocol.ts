/**
 * The wire vocabulary the relay itself speaks (everything else is forwarded
 * untouched) and the validation of the two first frames — the lab's `hello`
 * and the engineer's `join`. Kept free of runtime bindings so it unit-tests
 * as plain functions.
 *
 * Relay → lab:      {"t":"ready","server_time_ms":…}
 *                   {"t":"peer","state":"connected"|"disconnected"}
 *                   {"t":"error","code":…}   (then close)
 * Relay → support:  {"t":"peer","state":"lab_connected"|"lab_disconnected"}
 *                   {"t":"error","code":"no_session"|"busy"|"no_lab"}
 * Lab → relay:      {"t":"hello",…} first, {"t":"end"} to finish.
 * Support → relay:  {"t":"join","code":"XXXX-XXXX"} first.
 */

import type { EcPublicJwk } from "./keys";
import { verifyLicenseJwt } from "./jwt";

/** Frames larger than this are refused with close code 1009. */
export const MAX_FRAME_BYTES = 1024 * 1024;
export const MAX_SESSION_SECONDS = 86_400;
/** Default silence allowed before hello / join; `HELLO_TIMEOUT_MS` overrides. */
export const DEFAULT_HELLO_TIMEOUT_MS = 10_000;
/** What the app hashes: sha256hex("bnm-lab-support|" + code). */
export const CODE_HASH_PREFIX = "bnm-lab-support|";

/** WebSocket close codes the relay uses (4xxx = application-defined). */
export const Close = {
  ENDED: 1000, // lab sent {"t":"end"}
  GOING_AWAY: 1001, // the peer left without saying why
  UNSUPPORTED_DATA: 1003, // binary frame
  TOO_BIG: 1009,
  INTERNAL: 1011,
  REPLACED: 4001, // a newer lab connection for the same session took over
  BAD_FRAME: 4400, // malformed hello / join
  BAD_LICENSE: 4401,
  NO_SESSION: 4404,
  TIMEOUT: 4408, // silent socket
  CONFLICT: 4409, // busy / code_in_use / session_mismatch
  EXPIRED: 4410,
} as const;

export interface Hello {
  sessionId: string;
  codeHash: string;
  expiresInS: number;
}

export type HelloError =
  | "bad_hello"
  | "bad_version"
  | "bad_jwt"
  | "bad_session_id"
  | "bad_code_hash"
  | "bad_expiry";

export type HelloVerdict = { ok: true; hello: Hello } | { ok: false; code: HelloError };

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const HEX64 = /^[0-9a-f]{64}$/;
/** Crockford base32 without I, L, O, U — what the app's code generator emits. */
const CODE = /^[0-9A-HJKMNP-TV-Z]{8}$/;

export function isUuid(s: unknown): s is string {
  return typeof s === "string" && UUID.test(s);
}

function parseObject(text: string): Record<string, unknown> | null {
  try {
    const v: unknown = JSON.parse(text);
    return v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Cheap pre-check the front Worker uses to route a hello to its DO without verifying anything. */
export function peekHelloSessionId(text: string): string | null {
  const o = parseObject(text);
  if (!o || o.t !== "hello") return null;
  return isUuid(o.session_id) ? o.session_id : null;
}

/** Full hello validation: shape, licence signature + issuer, ids, expiry bound. */
export async function validateHello(text: string, jwk: EcPublicJwk): Promise<HelloVerdict> {
  const o = parseObject(text);
  if (!o || o.t !== "hello") return { ok: false, code: "bad_hello" };
  if (o.v !== 1) return { ok: false, code: "bad_version" };
  if (typeof o.jwt !== "string") return { ok: false, code: "bad_jwt" };
  if (!isUuid(o.session_id)) return { ok: false, code: "bad_session_id" };
  if (typeof o.code_hash !== "string" || !HEX64.test(o.code_hash)) return { ok: false, code: "bad_code_hash" };
  const exp = o.expires_in_s;
  if (typeof exp !== "number" || !Number.isInteger(exp) || exp < 1 || exp > MAX_SESSION_SECONDS) {
    return { ok: false, code: "bad_expiry" };
  }
  const jwt = await verifyLicenseJwt(o.jwt, jwk);
  if (!jwt.ok) return { ok: false, code: "bad_jwt" };
  return { ok: true, hello: { sessionId: o.session_id, codeHash: o.code_hash, expiresInS: exp } };
}

/**
 * What a person might type or read out → the 8 characters the app generated.
 * Uppercase, drop dashes/spaces, I/L → 1, O → 0 (Crockford's forgiving read).
 * Returns null when the result is not a possible code, so callers can answer
 * `no_session` without touching the directory.
 */
export function normaliseCode(raw: string): string | null {
  const s = raw.toUpperCase().replace(/[\s-]/g, "").replace(/[IL]/g, "1").replace(/O/g, "0");
  return CODE.test(s) ? s : null;
}

export function parseJoinCode(text: string): string | null {
  const o = parseObject(text);
  if (!o || o.t !== "join" || typeof o.code !== "string" || o.code.length > 32) return null;
  return normaliseCode(o.code);
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** The directory key for a normalised code — the same formula the app uses. */
export function codeHashOf(code: string): Promise<string> {
  return sha256Hex(CODE_HASH_PREFIX + code);
}

/** Is this frame the lab's `{"t":"end"}`? Only tiny frames are even parsed. */
export function isEndFrame(text: string): boolean {
  if (text.length > 64 || !text.includes('"end"')) return false;
  const o = parseObject(text);
  return o !== null && o.t === "end";
}

/** UTF-8 size of a text frame, with a fast path for anything that cannot be too big. */
export function frameTooLarge(message: string | ArrayBuffer): boolean {
  if (typeof message !== "string") return message.byteLength > MAX_FRAME_BYTES;
  if (message.length * 3 <= MAX_FRAME_BYTES) return false;
  return new TextEncoder().encode(message).byteLength > MAX_FRAME_BYTES;
}

export function encodeFrame(frame: Record<string, unknown>): string {
  return JSON.stringify(frame);
}

/** Constant-time bearer compare: hash both sides so the lengths always match. */
export async function tokenMatches(presented: string | null, expected: string | undefined): Promise<boolean> {
  if (!presented || !expected) return false;
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(presented)),
    crypto.subtle.digest("SHA-256", enc.encode(expected)),
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}

/** Close codes must be 1000–1014 (not 1004–1006) or 3000–4999; anything else becomes `fallback`. */
export function safeCloseCode(code: number, fallback: number = Close.INTERNAL): number {
  if ((code >= 1000 && code <= 1014 && code !== 1004 && code !== 1005 && code !== 1006) || (code >= 3000 && code <= 4999)) {
    return code;
  }
  return fallback;
}

/** `close()` on a socket that is already gone throws; the relay never cares. */
export function closeQuietly(ws: WebSocket, code: number, reason: string, fallback: number = Close.INTERNAL): void {
  try {
    ws.close(safeCloseCode(code, fallback), reason.slice(0, 120));
  } catch {
    /* already closed */
  }
}

/** Pass a peer's close on to its seat; a close with no usable status reads as "going away". */
export function mirrorClose(ws: WebSocket, code: number, reason: string): void {
  closeQuietly(ws, code, reason, Close.GOING_AWAY);
}

export function sendQuietly(ws: WebSocket, data: string | ArrayBuffer): boolean {
  try {
    ws.send(data);
    return true;
  } catch {
    return false;
  }
}
