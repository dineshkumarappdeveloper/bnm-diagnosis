#!/usr/bin/env node
/**
 * relay/scripts/mint-test-jwt.mjs
 *
 * Mint a licence JWT signed with the TEST keypair in test/, for driving the
 * relay locally (wrangler dev started with LAB_LICENSE_PUBLIC_JWK set to the
 * test public key — see README.md) and for the merge-step E2E.
 *
 *   node scripts/mint-test-jwt.mjs                      # a valid standalone licence
 *   node scripts/mint-test-jwt.mjs --lab "Demo Lab" --lid lic-42 --biz biz-1 --ed connected
 *   node scripts/mint-test-jwt.mjs --expired            # exp/lic_exp in the past (still accepted by the relay)
 *   node scripts/mint-test-jwt.mjs --iss other          # wrong issuer (the relay refuses it)
 *   node scripts/mint-test-jwt.mjs --key path/to.jwk    # another private JWK
 *   node scripts/mint-test-jwt.mjs --claims             # print the claims to stderr as well
 *
 * Prints ONLY the JWT on stdout so it can be captured: JWT=$(node scripts/mint-test-jwt.mjs)
 */

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { defaultLicenseClaims, mintJwt } from "./lib/jwt-mint.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);

function flag(name) {
  const i = args.indexOf(`--${name}`);
  if (i === -1) return undefined;
  const v = args[i + 1];
  return v === undefined || v.startsWith("--") ? true : v;
}

if (flag("help")) {
  console.error(readFileSync(fileURLToPath(import.meta.url), "utf8").split("*/")[0]);
  process.exit(0);
}

const keyPath = typeof flag("key") === "string" ? path.resolve(flag("key")) : path.resolve(here, "..", "test", "license-test.private.jwk.json");
const privateJwk = JSON.parse(readFileSync(keyPath, "utf8"));

const overrides = {};
for (const k of ["lab", "lid", "biz", "mode", "ed", "iss"]) {
  const v = flag(k);
  if (typeof v === "string") overrides[k] = v;
}
if (typeof flag("seats") === "string") overrides.seats = Number(flag("seats"));
if (flag("expired")) {
  const past = Math.floor(Date.now() / 1000) - 30 * 24 * 3600;
  overrides.lic_exp = past;
  overrides.exp = past;
}

const claims = defaultLicenseClaims(overrides);
if (flag("claims")) console.error(JSON.stringify(claims, null, 2));
process.stdout.write((await mintJwt(privateJwk, claims)) + "\n");
