/**
 * SessionDirectory — ONE global Durable Object (idFromName("v1")).
 *
 * Maps the hash of the spoken session code to the session id a LabSession is
 * addressed by, and counts support joins per token so a leaked token cannot
 * be used to guess codes at speed. Rows die with the session: `unregister`
 * on end, and a sweep of expired rows on every touch.
 *
 * It never sees a code in clear (only sha256("bnm-lab-support|" + code)), a
 * token in clear (only its hash), or a message body.
 */

import { DurableObject } from "cloudflare:workers";
import type { Env } from "./env";

export const JOIN_LIMIT = 10;
export const JOIN_WINDOW_MS = 60_000;

export interface DirectoryEntry {
  sessionId: string;
  expiresAt: number;
}

export class SessionDirectory extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS sessions (
          code_hash  TEXT PRIMARY KEY,
          session_id TEXT NOT NULL,
          expires_at INTEGER NOT NULL
        )`);
      ctx.storage.sql.exec(`CREATE INDEX IF NOT EXISTS sessions_by_session ON sessions (session_id)`);
      ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS joins (
          key TEXT NOT NULL,
          win INTEGER NOT NULL,
          n   INTEGER NOT NULL,
          PRIMARY KEY (key, win)
        )`);
    });
  }

  /**
   * Claim a code for a session. A code already held by ANOTHER live session
   * is refused (`code_in_use`) so a hello can never redirect an engineer who
   * was told a different lab's code. Re-registering the same session refreshes
   * nothing but is allowed (reconnects).
   */
  async register(codeHash: string, sessionId: string, expiresAt: number): Promise<"ok" | "code_in_use"> {
    const sql = this.ctx.storage.sql;
    this.sweep();
    const holder = sql.exec<{ session_id: string }>("SELECT session_id FROM sessions WHERE code_hash = ?", codeHash).toArray()[0];
    if (holder && holder.session_id !== sessionId) return "code_in_use";
    // One code per session: forget any earlier code this session registered.
    sql.exec("DELETE FROM sessions WHERE session_id = ? AND code_hash != ?", sessionId, codeHash);
    sql.exec(
      `INSERT INTO sessions (code_hash, session_id, expires_at) VALUES (?, ?, ?)
       ON CONFLICT (code_hash) DO UPDATE SET expires_at = excluded.expires_at`,
      codeHash,
      sessionId,
      expiresAt,
    );
    return "ok";
  }

  /** The live session behind a code hash, or null (unknown or expired). */
  async lookup(codeHash: string): Promise<DirectoryEntry | null> {
    this.sweep();
    const row = this.ctx.storage.sql
      .exec<{ session_id: string; expires_at: number }>("SELECT session_id, expires_at FROM sessions WHERE code_hash = ?", codeHash)
      .toArray()[0];
    return row ? { sessionId: row.session_id, expiresAt: row.expires_at } : null;
  }

  /** Forget a code — only when it still belongs to that session. */
  async unregister(codeHash: string, sessionId: string): Promise<void> {
    this.ctx.storage.sql.exec("DELETE FROM sessions WHERE code_hash = ? AND session_id = ?", codeHash, sessionId);
  }

  /** Count a join attempt for `key` (the token's hash); false once the minute's budget is spent. */
  async allowJoin(key: string): Promise<boolean> {
    const sql = this.ctx.storage.sql;
    const win = Math.floor(Date.now() / JOIN_WINDOW_MS);
    sql.exec("DELETE FROM joins WHERE win < ?", win - 1);
    const n = sql
      .exec<{ n: number }>(
        `INSERT INTO joins (key, win, n) VALUES (?, ?, 1)
         ON CONFLICT (key, win) DO UPDATE SET n = n + 1
         RETURNING n`,
        key,
        win,
      )
      .one().n;
    return n <= JOIN_LIMIT;
  }

  private sweep(): void {
    this.ctx.storage.sql.exec("DELETE FROM sessions WHERE expires_at <= ?", Date.now());
  }
}
