#!/usr/bin/env node
/**
 * relay/scripts/gen-test-keypair.mjs
 *
 * Generate the TEST licence keypair the relay's tests and the E2E use:
 *
 *   node scripts/gen-test-keypair.mjs          # refuses to overwrite
 *   node scripts/gen-test-keypair.mjs --force  # regenerate both files
 *
 * Writes test/license-test.public.jwk.json and
 * test/license-test.private.jwk.json. Both are committed on purpose: they are
 * throw-away keys that sign nothing real. The production public key lives in
 * src/keys.ts (= docs/license-public-key.jwk.json); its private half is only
 * on the licence server.
 */

import { existsSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const testDir = path.resolve(here, "..", "test");
const pubPath = path.join(testDir, "license-test.public.jwk.json");
const privPath = path.join(testDir, "license-test.private.jwk.json");
const force = process.argv.includes("--force");

if (!force && (existsSync(pubPath) || existsSync(privPath))) {
  console.error(`Refusing to overwrite ${pubPath} — pass --force to regenerate.`);
  process.exit(1);
}

const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const priv = await crypto.subtle.exportKey("jwk", pair.privateKey);
const pub = await crypto.subtle.exportKey("jwk", pair.publicKey);

// Keep the public file in the exact shape of docs/license-public-key.jwk.json.
const pubOut = { kty: "EC", x: pub.x, y: pub.y, crv: "P-256" };
const privOut = { kty: "EC", crv: "P-256", x: priv.x, y: priv.y, d: priv.d };

writeFileSync(pubPath, JSON.stringify(pubOut) + "\n");
writeFileSync(privPath, JSON.stringify(privOut) + "\n");
console.log(`wrote ${pubPath}\nwrote ${privPath}`);
