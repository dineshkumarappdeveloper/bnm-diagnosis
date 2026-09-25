/**
 * bnm-lab-relay — front Worker.
 *
 *   GET /v1/lab       (Upgrade)  the BNM Lab app dials in and says hello
 *   GET /v1/support   (Upgrade)  the engineer's bridge joins by code
 *   GET /v1/health               {"ok":true}
 *   anything else                404
 *
 * Who owns the socket: a Durable Object can only take a WebSocket that is
 * routed to it at upgrade time, and both first frames (`hello` names the
 * session, `join` carries the code) arrive AFTER the upgrade. So this Worker
 * accepts the client socket, waits for that one frame, opens a second socket
 * to the right LabSession and pipes the two together. The DO seats use the
 * Hibernation API; the Worker-held end simply follows the client.
 *
 * A lab that puts its session id in the URL (`/v1/lab?session=<uuid>`) skips
 * the peek: the upgrade is routed straight to its DO and no Worker pipe
 * exists for the life of the session. Preferred for the app — the hello frame
 * is validated by the DO in both cases.
 */

import { directoryStub, helloTimeoutMs, sessionStub, type Env } from "./env";
import {
  Close,
  DEFAULT_HELLO_TIMEOUT_MS,
  closeQuietly,
  codeHashOf,
  encodeFrame,
  isUuid,
  mirrorClose,
  parseJoinCode,
  peekHelloSessionId,
  sendQuietly,
  sha256Hex,
  tokenMatches,
} from "./protocol";

export { LabSession } from "./session";
export { SessionDirectory } from "./directory";

const UPGRADE_INIT: RequestInit = { headers: { Upgrade: "websocket" } };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    switch (url.pathname) {
      case "/v1/health":
        return Response.json({ ok: true });
      case "/v1/lab":
        if (request.method !== "GET") return methodNotAllowed();
        return labUpgrade(request, env, url);
      case "/v1/support":
        if (request.method !== "GET") return methodNotAllowed();
        return supportUpgrade(request, env);
      default:
        return new Response("not found", { status: 404 });
    }
  },
} satisfies ExportedHandler<Env>;

function methodNotAllowed(): Response {
  return new Response("method not allowed", { status: 405, headers: { Allow: "GET" } });
}

function isUpgrade(request: Request): boolean {
  return request.headers.get("Upgrade")?.toLowerCase() === "websocket";
}

// ── /v1/lab ──

async function labUpgrade(request: Request, env: Env, url: URL): Promise<Response> {
  if (!isUpgrade(request)) return new Response("expected a websocket upgrade", { status: 426 });

  const session = url.searchParams.get("session");
  if (session !== null) {
    if (!isUuid(session)) return new Response("session must be a uuid", { status: 400 });
    return sessionStub(env, session).fetch("https://lab-session/lab", UPGRADE_INIT);
  }

  return peekAndPipe({
    timeoutMs: helloTimeoutMs(env, DEFAULT_HELLO_TIMEOUT_MS),
    route: async (first) => {
      const sessionId = peekHelloSessionId(first);
      if (!sessionId) return { reject: { error: "bad_hello", closeCode: Close.BAD_FRAME, reason: "bad hello" } };
      const res = await sessionStub(env, sessionId).fetch("https://lab-session/lab", UPGRADE_INIT);
      if (res.status !== 101 || !res.webSocket) return { reject: { closeCode: Close.INTERNAL, reason: "relay error" } };
      return { upstream: res.webSocket, forwardFirst: true }; // the DO validates the hello itself
    },
  });
}

// ── /v1/support ──

let warnedNoToken = false;

async function supportUpgrade(request: Request, env: Env): Promise<Response> {
  const token = env.BNM_SUPPORT_TOKEN;
  if (!token) {
    if (!warnedNoToken) {
      warnedNoToken = true;
      console.error(JSON.stringify({ ev: "misconfigured", note: "BNM_SUPPORT_TOKEN is not set; refusing every support connection" }));
    }
    return unauthorized();
  }
  const auth = request.headers.get("Authorization");
  const presented = auth?.startsWith("Bearer ") ? auth.slice("Bearer ".length).trim() : null;
  if (!(await tokenMatches(presented, token))) return unauthorized();
  if (!isUpgrade(request)) return new Response("expected a websocket upgrade", { status: 426 });

  // The budget is per token, keyed by its hash — the directory never sees the token.
  if (!(await directoryStub(env).allowJoin(await sha256Hex(token)))) {
    return new Response("too many joins, wait a minute", { status: 429, headers: { "Retry-After": "60" } });
  }

  return peekAndPipe({
    timeoutMs: helloTimeoutMs(env, DEFAULT_HELLO_TIMEOUT_MS),
    route: async (first) => {
      const noSession = { reject: { error: "no_session", closeCode: Close.NO_SESSION, reason: "no session" } };
      const code = parseJoinCode(first);
      if (!code) return noSession;
      const entry = await directoryStub(env).lookup(await codeHashOf(code));
      if (!entry) return noSession;
      const res = await sessionStub(env, entry.sessionId).fetch("https://lab-session/support", UPGRADE_INIT);
      if (res.status === 409) return { reject: { error: "busy", closeCode: Close.CONFLICT, reason: "busy" } };
      if (res.status !== 101 || !res.webSocket) return noSession;
      return { upstream: res.webSocket, forwardFirst: false }; // the join frame stops here
    },
  });
}

function unauthorized(): Response {
  return new Response("unauthorized", { status: 401, headers: { "WWW-Authenticate": 'Bearer realm="bnm-lab-relay"' } });
}

// ── peek the first frame, then pipe ──

interface Reject {
  /** Sent as {"t":"error","code":…} before closing, when set. */
  error?: string;
  closeCode: number;
  reason: string;
}

type Routed = { upstream: WebSocket; forwardFirst: boolean } | { reject: Reject };

interface PeekOptions {
  timeoutMs: number;
  /** Decide where the client goes from its first text frame. */
  route: (first: string) => Promise<Routed>;
}

/**
 * Accept the client, hold its frames until `route` has produced an upstream
 * socket (a LabSession seat), then forward everything both ways and mirror
 * closes. Frames that arrive while routing are queued in order; nothing is
 * inspected beyond the first one.
 */
function peekAndPipe(opts: PeekOptions): Response {
  const pair = new WebSocketPair();
  const [client, server] = [pair[0], pair[1]];
  server.accept();

  let state: "routing" | "piping" | "closed" = "routing";
  let upstream: WebSocket | null = null;
  const backlog: string[] = [];

  const timer = setTimeout(() => {
    if (state === "routing") {
      state = "closed";
      closeQuietly(server, Close.TIMEOUT, "no first frame");
    }
  }, opts.timeoutMs);

  const reject = (r: Reject): void => {
    state = "closed";
    clearTimeout(timer);
    if (r.error) sendQuietly(server, encodeFrame({ t: "error", code: r.error }));
    closeQuietly(server, r.closeCode, r.reason);
  };

  const attach = (ws: WebSocket, forwardFirst: boolean): void => {
    ws.accept();
    ws.addEventListener("message", (ev) => {
      if (typeof ev.data === "string") sendQuietly(server, ev.data); // a seat only ever sends text
    });
    ws.addEventListener("close", (ev) => {
      state = "closed";
      mirrorClose(server, ev.code, ev.reason);
    });
    ws.addEventListener("error", () => {
      state = "closed";
      closeQuietly(server, Close.INTERNAL, "relay error");
    });
    clearTimeout(timer);
    upstream = ws;
    state = "piping";
    const frames = forwardFirst ? backlog.splice(0) : backlog.splice(1);
    backlog.length = 0;
    for (const f of frames) sendQuietly(ws, f);
  };

  server.addEventListener("message", (ev) => {
    if (typeof ev.data !== "string") {
      // Text only. (A Worker-side socket hands binary over as a Blob, which
      // send() would stringify — so this is refused here, never forwarded.)
      const was = state;
      state = "closed";
      clearTimeout(timer);
      if (was === "piping" && upstream) closeQuietly(upstream, Close.ENDED, "client sent binary");
      closeQuietly(server, Close.UNSUPPORTED_DATA, "text frames only");
      return;
    }
    if (state === "piping" && upstream) {
      sendQuietly(upstream, ev.data);
      return;
    }
    if (state !== "routing") return;
    backlog.push(ev.data);
    if (backlog.length > 1) return; // already routing on the first frame
    void opts
      .route(ev.data)
      .catch((): Routed => ({ reject: { closeCode: Close.INTERNAL, reason: "relay error" } }))
      .then((routed) => {
        if (state !== "routing") {
          // The client left (or timed out) while we were routing: let the seat go.
          if ("upstream" in routed) {
            try {
              routed.upstream.accept();
            } catch {
              /* ignore */
            }
            closeQuietly(routed.upstream, Close.ENDED, "client gone");
          }
          return;
        }
        if ("reject" in routed) reject(routed.reject);
        else attach(routed.upstream, routed.forwardFirst);
      });
  });

  server.addEventListener("close", (ev) => {
    const was = state;
    state = "closed";
    clearTimeout(timer);
    if (was === "piping" && upstream) mirrorClose(upstream, ev.code, ev.reason);
  });
  server.addEventListener("error", () => {
    const was = state;
    state = "closed";
    clearTimeout(timer);
    if (was === "piping" && upstream) closeQuietly(upstream, Close.INTERNAL, "client error");
  });

  return new Response(null, { status: 101, webSocket: client });
}
