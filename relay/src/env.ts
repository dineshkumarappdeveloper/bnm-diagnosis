import type { LabSession } from "./session";
import type { SessionDirectory } from "./directory";

/** Bindings and secrets — see wrangler.toml and .dev.vars.example. */
export interface Env {
  SESSIONS: DurableObjectNamespace<LabSession>;
  DIRECTORY: DurableObjectNamespace<SessionDirectory>;
  /** Engineer bearer token. Unset ⇒ every support connection is 401. */
  BNM_SUPPORT_TOKEN?: string;
  /** One-line P-256 public JWK overriding src/keys.ts (tests, E2E). */
  LAB_LICENSE_PUBLIC_JWK?: string;
  /** Milliseconds a fresh socket may stay silent before it is closed. */
  HELLO_TIMEOUT_MS?: string;
}

export function helloTimeoutMs(env: Env, fallback: number): number {
  const n = Number(env.HELLO_TIMEOUT_MS);
  return Number.isFinite(n) && n >= 50 ? n : fallback;
}

/** The single global directory instance. */
export function directoryStub(env: Env): DurableObjectStub<SessionDirectory> {
  return env.DIRECTORY.get(env.DIRECTORY.idFromName("v1"));
}

/** The session object — always addressed by the lab's session id. */
export function sessionStub(env: Env, sessionId: string): DurableObjectStub<LabSession> {
  return env.SESSIONS.get(env.SESSIONS.idFromName(sessionId));
}
