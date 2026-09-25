/**
 * relay/scripts/lib/jwt-mint.mjs
 *
 * Mint an ES256 JWS the way the licence server does (RFC 7515 compact form,
 * RFC 7518 raw r||s signature), from a P-256 private JWK. Zero dependencies —
 * WebCrypto only — so the same code runs under Node ≥ 22 (the CLI in
 * ../mint-test-jwt.mjs) and inside the Workers test runtime (test/helpers.ts).
 *
 * TEST USE ONLY. The production private key never leaves the licence server;
 * this exists so the relay can be exercised with a throw-away keypair.
 */

const subtle = globalThis.crypto.subtle;

/** Standard base64url (no padding) of bytes or an ASCII/UTF-8 string. */
export function b64url(input) {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : new Uint8Array(input);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function importPrivateJwk(jwk) {
  return subtle.importKey("jwk", jwk, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
}

/**
 * @param {JsonWebKey} privateJwk  P-256 private JWK (with `d`)
 * @param {Record<string, unknown>} claims  the JWT payload, verbatim
 * @param {{ alg?: string }} [opts]  `alg` lets tests mint a wrong-alg header
 * @returns {Promise<string>} compact JWS
 */
export async function mintJwt(privateJwk, claims, opts = {}) {
  const key = await importPrivateJwk(privateJwk);
  const header = { alg: opts.alg ?? "ES256", typ: "JWT" };
  const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(claims))}`;
  const sig = await subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${b64url(sig)}`;
}

/** The claim set the licence server issues, with test-friendly defaults. */
export function defaultLicenseClaims(overrides = {}) {
  const now = Math.floor(Date.now() / 1000);
  const year = 365 * 24 * 3600;
  return {
    lid: "lic-test-0001",
    lab: "Test Lab",
    mode: "subscription",
    seats: 1,
    biz: null,
    ed: "standalone",
    lic_exp: now + year,
    gr: 45 * 24 * 3600,
    iat: now,
    exp: now + year + 45 * 24 * 3600,
    iss: "bnm-lab-license",
    ...overrides,
  };
}
