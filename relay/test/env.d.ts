// `env` from cloudflare:test is typed as the global Cloudflare.Env — extend it
// with the relay's bindings so tests see SESSIONS / DIRECTORY / secrets.
import type { Env as RelayEnv } from "../src/env";

declare global {
  namespace Cloudflare {
    interface Env extends RelayEnv {}
  }
}

export {};
