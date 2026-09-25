/**
 * ES256 JWS verification with WebCrypto, mirroring the app's
 * `LicenseVerify.desktop.kt`: compact JWS, `alg` must be ES256, signature is
 * RFC 7518 raw r||s (64 bytes) — which is exactly the IEEE P1363 form WebCrypto
 * ECDSA consumes, so no DER conversion is needed here.
 *
 * Only the signature and the issuer are checked. `exp` is deliberately NOT
 * enforced: a lapsed subscription still deserves support, and the licence
 * standing is the app's business, not the relay's.
 */

import type { EcPublicJwk } from "./keys";

export const LICENSE_ISSUER = "bnm-lab-license";

export type JwtVerdict =
  | { ok: true; claims: Record<string, unknown> }
  | { ok: false; reason: "malformed" | "bad_alg" | "bad_signature" | "bad_issuer" };

// importKey per JWK, once — the override never changes within an isolate.
const keyCache = new Map<string, Promise<CryptoKey>>();

function importVerifyKey(jwk: EcPublicJwk): Promise<CryptoKey> {
  const cacheKey = `${jwk.x}.${jwk.y}`;
  let p = keyCache.get(cacheKey);
  if (!p) {
    p = crypto.subtle.importKey(
      "jwk",
      { kty: "EC", crv: "P-256", x: jwk.x, y: jwk.y, ext: true },
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    keyCache.set(cacheKey, p);
  }
  return p;
}

/** base64url → bytes; null when the text is not base64url. */
export function b64urlDecode(text: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]*$/.test(text)) return null;
  const b64 = text.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (text.length % 4)) % 4);
  try {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

function decodeJsonPart(part: string): Record<string, unknown> | null {
  const bytes = b64urlDecode(part);
  if (!bytes) return null;
  try {
    const v: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Verify signature + issuer. Never throws. */
export async function verifyLicenseJwt(jwt: string, jwk: EcPublicJwk): Promise<JwtVerdict> {
  if (typeof jwt !== "string" || jwt.length > 8192) return { ok: false, reason: "malformed" };
  const parts = jwt.split(".");
  if (parts.length !== 3) return { ok: false, reason: "malformed" };
  const [h, p, s] = parts as [string, string, string];

  const header = decodeJsonPart(h);
  if (!header) return { ok: false, reason: "malformed" };
  if (header.alg !== "ES256") return { ok: false, reason: "bad_alg" };

  const claims = decodeJsonPart(p);
  if (!claims) return { ok: false, reason: "malformed" };

  const sig = b64urlDecode(s);
  if (!sig || sig.length !== 64) return { ok: false, reason: "bad_signature" };

  let valid = false;
  try {
    const key = await importVerifyKey(jwk);
    valid = await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      sig,
      new TextEncoder().encode(`${h}.${p}`),
    );
  } catch {
    valid = false;
  }
  if (!valid) return { ok: false, reason: "bad_signature" };
  if (claims.iss !== LICENSE_ISSUER) return { ok: false, reason: "bad_issuer" };
  return { ok: true, claims };
}
