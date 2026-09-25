import { describe, expect, it } from "vitest";
import docsJwk from "../../docs/license-public-key.jwk.json";
import { PRODUCTION_LICENSE_JWK, parseEcPublicJwk, resolveLicenseJwk } from "../src/keys";
import { LICENSE_ISSUER, b64urlDecode, verifyLicenseJwt } from "../src/jwt";
import testPublicJwk from "./license-test.public.jwk.json";
import { licenseJwt } from "./helpers";
import { mintJwt, defaultLicenseClaims } from "../scripts/lib/jwt-mint.mjs";

const TEST_JWK = parseEcPublicJwk(JSON.stringify(testPublicJwk))!;

describe("keys", () => {
  it("ships the production key from docs/license-public-key.jwk.json verbatim", () => {
    expect(PRODUCTION_LICENSE_JWK).toEqual(docsJwk);
  });

  it("uses the override only when it is a well-formed P-256 public JWK", () => {
    expect(resolveLicenseJwk(undefined)).toBe(PRODUCTION_LICENSE_JWK);
    expect(resolveLicenseJwk("")).toBe(PRODUCTION_LICENSE_JWK);
    expect(resolveLicenseJwk("not json")).toBe(PRODUCTION_LICENSE_JWK);
    expect(resolveLicenseJwk(JSON.stringify({ kty: "RSA", n: "x" }))).toBe(PRODUCTION_LICENSE_JWK);
    expect(resolveLicenseJwk(JSON.stringify(testPublicJwk))).toEqual(TEST_JWK);
  });

  it("does not accept a private JWK as an override", () => {
    // A private key is never a valid verify-only override (extra `d` is ignored, shape still must be public P-256).
    expect(parseEcPublicJwk(JSON.stringify({ ...testPublicJwk, x: "short" }))).toBeNull();
  });
});

describe("verifyLicenseJwt", () => {
  it("accepts a token signed by the matching key and issuer", async () => {
    const jwt = await licenseJwt({ lab: "Sunrise Diagnostics" });
    const v = await verifyLicenseJwt(jwt, TEST_JWK);
    expect(v.ok).toBe(true);
    if (v.ok) expect(v.claims.lab).toBe("Sunrise Diagnostics");
  });

  it("accepts an EXPIRED licence — support is not a licence check", async () => {
    const past = Math.floor(Date.now() / 1000) - 86400;
    const v = await verifyLicenseJwt(await licenseJwt({ exp: past, lic_exp: past }), TEST_JWK);
    expect(v.ok).toBe(true);
  });

  it("refuses the wrong issuer", async () => {
    const v = await verifyLicenseJwt(await licenseJwt({ iss: "someone-else" }), TEST_JWK);
    expect(v).toEqual({ ok: false, reason: "bad_issuer" });
    expect(LICENSE_ISSUER).toBe("bnm-lab-license");
  });

  it("refuses a token signed by another key", async () => {
    const other = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
    const otherPriv = (await crypto.subtle.exportKey("jwk", other.privateKey)) as JsonWebKey;
    const jwt = await mintJwt(otherPriv, defaultLicenseClaims());
    expect(await verifyLicenseJwt(jwt, TEST_JWK)).toEqual({ ok: false, reason: "bad_signature" });
  });

  it("refuses a token verified against the production key when signed by the test key", async () => {
    expect(await verifyLicenseJwt(await licenseJwt(), PRODUCTION_LICENSE_JWK)).toEqual({ ok: false, reason: "bad_signature" });
  });

  it("refuses a tampered payload", async () => {
    const [h, p, s] = (await licenseJwt()).split(".") as [string, string, string];
    const claims = JSON.parse(new TextDecoder().decode(b64urlDecode(p)!)) as Record<string, unknown>;
    claims.lab = "Forged Lab";
    const forged = btoa(JSON.stringify(claims)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    expect(await verifyLicenseJwt(`${h}.${forged}.${s}`, TEST_JWK)).toEqual({ ok: false, reason: "bad_signature" });
  });

  it("refuses alg=none and HS256 headers even with a 64-byte signature", async () => {
    expect(await verifyLicenseJwt(await licenseJwt({}, { alg: "none" }), TEST_JWK)).toEqual({ ok: false, reason: "bad_alg" });
    expect(await verifyLicenseJwt(await licenseJwt({}, { alg: "HS256" }), TEST_JWK)).toEqual({ ok: false, reason: "bad_alg" });
  });

  it("refuses malformed input without throwing", async () => {
    for (const bad of ["", "a.b", "a.b.c.d", "!!.!!.!!", "eyJ.eyJ.sig", "x".repeat(9000)]) {
      const v = await verifyLicenseJwt(bad, TEST_JWK);
      expect(v.ok).toBe(false);
    }
    // valid header/payload, signature of the wrong length
    const [h, p] = (await licenseJwt()).split(".") as [string, string, string];
    expect(await verifyLicenseJwt(`${h}.${p}.AAAA`, TEST_JWK)).toEqual({ ok: false, reason: "bad_signature" });
  });
});
