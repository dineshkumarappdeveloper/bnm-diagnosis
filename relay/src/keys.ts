/**
 * The BNM Lab licence public key — the P-256 JWK from
 * docs/license-public-key.jwk.json, copied verbatim (a test asserts the two
 * never drift apart). Its private half lives only on the licence server
 * (BusinessStudio `admin-lab`); the relay can verify licence JWTs but never
 * mint them.
 *
 * `LAB_LICENSE_PUBLIC_JWK` (secret / .dev.vars) overrides it so `wrangler dev`
 * can run against the TEST keypair in test/ for the end-to-end smoke.
 */

export interface EcPublicJwk {
  kty: "EC";
  crv: "P-256";
  x: string;
  y: string;
}

export const PRODUCTION_LICENSE_JWK: EcPublicJwk = {
  kty: "EC",
  x: "yQbC7YPLyrelDs8Rd79n8drr-ZgI0U0GBQ7qHlROEHE",
  y: "By-3yfBRmaz4VkDYjTGS3CovFel00UfhfvVXkSnT5Wg",
  crv: "P-256",
};

const B64URL = /^[A-Za-z0-9_-]{43}$/; // 32 bytes → 43 chars, no padding

/** Parse a JWK override, or explain why it is unusable. */
export function parseEcPublicJwk(text: string): EcPublicJwk | null {
  try {
    const j = JSON.parse(text) as Record<string, unknown>;
    if (j.kty !== "EC" || j.crv !== "P-256") return null;
    if (typeof j.x !== "string" || typeof j.y !== "string") return null;
    if (!B64URL.test(j.x) || !B64URL.test(j.y)) return null;
    return { kty: "EC", crv: "P-256", x: j.x, y: j.y };
  } catch {
    return null;
  }
}

let warnedBadOverride = false;

/** The key to verify licences with: the override when it is well-formed, else production. */
export function resolveLicenseJwk(override: string | undefined): EcPublicJwk {
  if (!override || override.trim() === "") return PRODUCTION_LICENSE_JWK;
  const parsed = parseEcPublicJwk(override);
  if (parsed) return parsed;
  if (!warnedBadOverride) {
    warnedBadOverride = true;
    console.warn(JSON.stringify({ ev: "bad_license_jwk_override", note: "LAB_LICENSE_PUBLIC_JWK is not a P-256 public JWK; using the production key" }));
  }
  return PRODUCTION_LICENSE_JWK;
}
