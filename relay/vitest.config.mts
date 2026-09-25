import { readFileSync } from "node:fs";
import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";

// Tests run against the TEST licence keypair in test/ — never the production
// key — and a fixed engineer token. HELLO_TIMEOUT_MS is short so the
// "silent socket is closed" paths can be exercised without waiting 10 s.
const testPublicJwk = readFileSync(new URL("./test/license-test.public.jwk.json", import.meta.url), "utf8").trim();

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: {
        bindings: {
          BNM_SUPPORT_TOKEN: "test-support-token",
          LAB_LICENSE_PUBLIC_JWK: testPublicJwk,
          HELLO_TIMEOUT_MS: "250",
        },
      },
    }),
  ],
  test: {
    include: ["test/**/*.test.ts"],
    testTimeout: 15_000,
  },
});
