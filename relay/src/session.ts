/**
 * LabSession — one Durable Object per support session, addressed by
 * idFromName(session_id). A dumb pipe with two seats:
 *
 *   lab      the BNM Lab desktop app (dials out, sends `hello` first)
 *   support  the engineer's bridge (joined by code through the directory)
 *
 * Text frames are forwarded verbatim in both directions. The object itself
 * only ever emits `ready`, `peer` and `error` notices, reads the lab's
 * `hello` and `end`, and closes everything at expiry. Sockets use the
 * Hibernation API, so an idle 24-hour session costs nothing while it waits;
 * everything the object needs after waking lives in its SQLite row or in the
 * per-socket attachment.
 *
 * Never logged: message bodies, the code, the licence. Logged: open/pair/
 * close events keyed by the first 8 chars of the session id, and byte counts.
 */

import { DurableObject } from "cloudflare:workers";
import { directoryStub, helloTimeoutMs, type Env } from "./env";
import { resolveLicenseJwk } from "./keys";
import {
  Close,
  DEFAULT_HELLO_TIMEOUT_MS,
  closeQuietly,
  encodeFrame,
  frameTooLarge,
  isEndFrame,
  sendQuietly,
  validateHello,
  type HelloError,
} from "./protocol";

type Role = "lab" | "support";

/** Survives hibernation with the socket (WebSocket.serializeAttachment). */
interface Attachment {
  role: Role;
  /** Lab: hello accepted. Support: always true. */
  ready: boolean;
  openedAt: number;
}

type SessionRow = {
  session_id: string;
  code_hash: string;
  expires_at: number;
  created_at: number;
  lab_bytes: number;
  support_bytes: number;
};

type RefuseCode = HelloError | "session_mismatch" | "code_in_use";

export class LabSession extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS session (
          id            INTEGER PRIMARY KEY CHECK (id = 1),
          session_id    TEXT NOT NULL,
          code_hash     TEXT NOT NULL,
          expires_at    INTEGER NOT NULL,
          created_at    INTEGER NOT NULL,
          lab_bytes     INTEGER NOT NULL DEFAULT 0,
          support_bytes INTEGER NOT NULL DEFAULT 0
        )`);
    });
  }

  // ── entry: only the front Worker calls this ──

  override async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("expected a websocket upgrade", { status: 426 });
    }
    const path = new URL(request.url).pathname;
    if (path === "/lab") return this.acceptLab();
    if (path === "/support") return this.acceptSupport();
    return new Response("not found", { status: 404 });
  }

  /** Seat the lab socket; it must say hello before anything else happens. */
  private async acceptLab(): Promise<Response> {
    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    const att: Attachment = { role: "lab", ready: false, openedAt: Date.now() };
    server.serializeAttachment(att);
    this.ctx.acceptWebSocket(server, ["lab"]);
    await this.rescheduleAlarm();
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Seat the engineer — only beside a lab that has said hello, and only one at a time. */
  private acceptSupport(): Response {
    const session = this.session();
    const lab = this.readyLab();
    if (!session || session.expires_at <= Date.now() || !lab) return new Response("no session", { status: 404 });
    if (this.support()) return new Response("busy", { status: 409 });

    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    const att: Attachment = { role: "support", ready: true, openedAt: Date.now() };
    server.serializeAttachment(att);
    this.ctx.acceptWebSocket(server, ["support"]);
    sendQuietly(lab, encodeFrame({ t: "peer", state: "connected" }));
    this.log("pair");
    return new Response(null, { status: 101, webSocket: client });
  }

  // ── hibernation callbacks ──

  override async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const att = this.attachmentOf(ws);
    if (!att) {
      closeQuietly(ws, Close.INTERNAL, "unknown socket");
      return;
    }
    if (typeof message !== "string") {
      closeQuietly(ws, Close.UNSUPPORTED_DATA, "text frames only");
      return;
    }
    if (frameTooLarge(message)) {
      closeQuietly(ws, Close.TOO_BIG, "frame too large");
      return;
    }

    if (att.role === "lab") {
      if (!att.ready) {
        await this.handleHello(ws, att, message);
        return;
      }
      this.count("lab_bytes", message);
      if (isEndFrame(message)) {
        await this.endSession("ended by lab", Close.ENDED);
        return;
      }
      const support = this.support();
      if (support) sendQuietly(support, message); // nobody asked ⇒ nothing to deliver
      return;
    }

    // support → lab
    this.count("support_bytes", message);
    const lab = this.readyLab();
    if (!lab) {
      sendQuietly(ws, encodeFrame({ t: "error", code: "no_lab" }));
      return;
    }
    sendQuietly(lab, message);
  }

  override async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    await this.onGone(ws, code);
  }

  override async webSocketError(ws: WebSocket): Promise<void> {
    await this.onGone(ws, Close.INTERNAL);
  }

  /** Expiry, and lab seats that never said hello. */
  override async alarm(): Promise<void> {
    const now = Date.now();
    const session = this.session();
    if (session && session.expires_at <= now) {
      await this.endSession("session expired", Close.EXPIRED);
      return;
    }
    const timeout = this.helloTimeout();
    for (const ws of this.ctx.getWebSockets("lab")) {
      const att = this.attachmentOf(ws);
      if (att && !att.ready && att.openedAt + timeout <= now) closeQuietly(ws, Close.TIMEOUT, "no hello");
    }
    await this.rescheduleAlarm(now);
  }

  // ── hello ──

  private async handleHello(ws: WebSocket, att: Attachment, text: string): Promise<void> {
    const verdict = await validateHello(text, resolveLicenseJwk(this.env.LAB_LICENSE_PUBLIC_JWK));
    if (!verdict.ok) {
      this.refuse(ws, verdict.code, verdict.code === "bad_jwt" ? Close.BAD_LICENSE : Close.BAD_FRAME);
      return;
    }
    const hello = verdict.hello;
    // This object IS the session: the hello must name it.
    if (hello.sessionId !== this.ctx.id.name) {
      this.refuse(ws, "session_mismatch", Close.CONFLICT);
      return;
    }

    const now = Date.now();
    let session = this.session();
    let kind: "new" | "reconnect";
    if (session && session.expires_at > now) {
      // A reconnect keeps the original code and expiry; a different code is somebody else's hello.
      if (session.code_hash !== hello.codeHash) {
        this.refuse(ws, "session_mismatch", Close.CONFLICT);
        return;
      }
      kind = "reconnect";
    } else {
      session = {
        session_id: hello.sessionId,
        code_hash: hello.codeHash,
        expires_at: now + hello.expiresInS * 1000,
        created_at: now,
        lab_bytes: 0,
        support_bytes: 0,
      };
      kind = "new";
    }

    const claim = await directoryStub(this.env).register(session.code_hash, session.session_id, session.expires_at);
    if (claim !== "ok") {
      this.refuse(ws, claim, Close.CONFLICT);
      return;
    }
    if (kind === "new") {
      this.ctx.storage.sql.exec(
        `INSERT OR REPLACE INTO session (id, session_id, code_hash, expires_at, created_at, lab_bytes, support_bytes)
         VALUES (1, ?, ?, ?, ?, 0, 0)`,
        session.session_id,
        session.code_hash,
        session.expires_at,
        session.created_at,
      );
    }

    // This socket is the lab now; any older lab seat (a drop not yet noticed) is let go.
    ws.serializeAttachment({ ...att, ready: true } satisfies Attachment);
    for (const other of this.ctx.getWebSockets("lab")) {
      if (other !== ws) closeQuietly(other, Close.REPLACED, "replaced by a newer connection");
    }
    await this.rescheduleAlarm(now);

    sendQuietly(ws, encodeFrame({ t: "ready", server_time_ms: Date.now() }));
    const support = this.support();
    if (support) {
      sendQuietly(ws, encodeFrame({ t: "peer", state: "connected" }));
      sendQuietly(support, encodeFrame({ t: "peer", state: "lab_connected" }));
    }
    this.log("session_open", { kind, expires_in_s: Math.round((session.expires_at - now) / 1000) });
  }

  private refuse(ws: WebSocket, code: RefuseCode, closeCode: number): void {
    sendQuietly(ws, encodeFrame({ t: "error", code }));
    closeQuietly(ws, closeCode, code);
    this.log("hello_refused", { code });
  }

  // ── teardown ──

  private async onGone(ws: WebSocket, code: number): Promise<void> {
    const att = this.attachmentOf(ws);
    if (!att) return;
    if (att.role === "lab") {
      // A replaced seat leaving is not the lab leaving.
      if (att.ready && !this.readyLab(ws)) {
        const support = this.support();
        if (support) sendQuietly(support, encodeFrame({ t: "peer", state: "lab_disconnected" }));
        this.log("lab_gone", { code });
      }
      return;
    }
    const lab = this.readyLab();
    if (lab) sendQuietly(lab, encodeFrame({ t: "peer", state: "disconnected" }));
    this.log("unpair", { code });
  }

  /** Close both seats, forget the code, drop the row. Idempotent. */
  private async endSession(reason: string, closeCode: number): Promise<void> {
    const session = this.session();
    for (const ws of this.ctx.getWebSockets()) closeQuietly(ws, closeCode, reason);
    if (session) {
      await directoryStub(this.env).unregister(session.code_hash, session.session_id);
      this.ctx.storage.sql.exec("DELETE FROM session");
      this.log("session_close", { reason, lab_bytes: session.lab_bytes, support_bytes: session.support_bytes });
    }
    await this.ctx.storage.deleteAlarm();
  }

  /** One alarm: the earliest of expiry and every pending hello deadline. */
  private async rescheduleAlarm(now = Date.now()): Promise<void> {
    let next: number | null = null;
    const session = this.session();
    if (session) next = session.expires_at;
    const timeout = this.helloTimeout();
    for (const ws of this.ctx.getWebSockets("lab")) {
      const att = this.attachmentOf(ws);
      if (att && !att.ready) {
        const deadline = att.openedAt + timeout;
        if (next === null || deadline < next) next = deadline;
      }
    }
    if (next === null) await this.ctx.storage.deleteAlarm();
    else await this.ctx.storage.setAlarm(Math.max(next, now + 1));
  }

  // ── small helpers ──

  private session(): SessionRow | null {
    return this.ctx.storage.sql.exec<SessionRow>("SELECT * FROM session WHERE id = 1").toArray()[0] ?? null;
  }

  private readyLab(except?: WebSocket): WebSocket | null {
    for (const ws of this.ctx.getWebSockets("lab")) {
      if (ws === except) continue;
      if (this.attachmentOf(ws)?.ready) return ws;
    }
    return null;
  }

  private support(): WebSocket | null {
    return this.ctx.getWebSockets("support")[0] ?? null;
  }

  private attachmentOf(ws: WebSocket): Attachment | null {
    try {
      return (ws.deserializeAttachment() as Attachment | null) ?? null;
    } catch {
      return null;
    }
  }

  private count(column: "lab_bytes" | "support_bytes", text: string): void {
    const bytes = new TextEncoder().encode(text).byteLength;
    this.ctx.storage.sql.exec(`UPDATE session SET ${column} = ${column} + ? WHERE id = 1`, bytes);
  }

  private helloTimeout(): number {
    return helloTimeoutMs(this.env, DEFAULT_HELLO_TIMEOUT_MS);
  }

  private log(ev: string, extra: Record<string, unknown> = {}): void {
    const sid = (this.ctx.id.name ?? this.ctx.id.toString()).slice(0, 8);
    console.log(JSON.stringify({ ev, sid, ...extra }));
  }
}
