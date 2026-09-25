/**
 * test/fake-relay.mjs — an in-process stand-in for relay + lab.
 *
 * Node core has no WebSocket *server*, so the fake lives at the WebSocket-object
 * level: `FakeRelay.factory` is what `LabLink` gets as `wsFactory`, and every
 * socket it hands out is a `FakeSocket` the fake drives from the "server" side.
 * The fake plays both the relay (bearer token, join, busy, error frames, peer
 * notices) and the lab (initialize, tools/list, signed tools/call).
 *
 * Signature checking here is written independently of the bridge — the payload
 * is built by hand from the contract's wording (§2.4) so the tests prove the
 * bridge signs what the lab expects, not merely what the bridge itself expects.
 */

import crypto from 'node:crypto';

const SIGNING_PREFIX = 'bnm-lab-support-v1';
const TS_WINDOW_MS = 5 * 60 * 1000;

// Independent canonicaliser: keys sorted by UTF-16 code unit, strings built by
// hand (an object rebuild would put integer-like keys first, which is wrong).
function canonical(v) {
    if (v === null || typeof v !== 'object') return JSON.stringify(v);
    if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
    return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}';
}

export function payloadFor({ sessionId, id, method, tsMs, nonce, params }) {
    return SIGNING_PREFIX + '\n' + sessionId + '\n' + String(id) + '\n' + method + '\n' + String(tsMs) + '\n' + nonce + '\n' + canonical(params);
}

/** WebSocket-shaped object driven from both sides. */
export class FakeSocket {
    readyState = 0; // CONNECTING
    sent = [];
    closed = null;
    onSend = null;
    #listeners = new Map();

    addEventListener(type, fn, opts) {
        const arr = this.#listeners.get(type) ?? [];
        arr.push({ fn, once: Boolean(opts?.once) });
        this.#listeners.set(type, arr);
    }

    removeEventListener(type, fn) {
        this.#listeners.set(type, (this.#listeners.get(type) ?? []).filter((l) => l.fn !== fn));
    }

    #emit(type, ev = {}) {
        const arr = this.#listeners.get(type) ?? [];
        this.#listeners.set(type, arr.filter((l) => !l.once));
        for (const l of arr) l.fn({ type, ...ev });
    }

    // ── client side (what the bridge calls) ──

    send(data) {
        if (this.readyState !== 1) throw new Error('InvalidStateError: socket is not open');
        const text = String(data);
        this.sent.push(text);
        this.onSend?.(text);
    }

    close(code = 1000, reason = '') {
        if (this.readyState >= 2) return;
        this.readyState = 3;
        this.closed = { code, reason, by: 'client' };
        queueMicrotask(() => this.#emit('close', { code, reason }));
    }

    // ── server side (what the fake relay calls) ──

    serverOpen() {
        this.readyState = 1;
        this.#emit('open');
    }

    serverSend(obj) {
        const data = typeof obj === 'string' ? obj : JSON.stringify(obj);
        queueMicrotask(() => this.#emit('message', { data }));
    }

    serverClose(code = 1000, reason = '') {
        if (this.readyState >= 2) return;
        this.readyState = 3;
        this.closed = { code, reason, by: 'server' };
        queueMicrotask(() => this.#emit('close', { code, reason }));
    }

    serverReject() {
        // What undici does on a non-101 upgrade answer: error, then close 1006.
        this.readyState = 3;
        this.closed = { code: 1006, reason: '', by: 'server' };
        queueMicrotask(() => {
            this.#emit('error', {});
            this.#emit('close', { code: 1006, reason: '' });
        });
    }
}

export const DEFAULT_TOOLS = [
    { name: 'session.info', description: 'Consent, remaining seconds, actions, peer state.', inputSchema: { type: 'object', properties: {} }, annotations: { readOnlyHint: true, destructiveHint: false, title: 'Session info' } },
    { name: 'echo', description: 'Returns text.', inputSchema: { type: 'object', properties: { text: { type: 'string' } } }, annotations: { readOnlyHint: true, destructiveHint: false, title: 'Echo' } },
    { name: 'picture', description: 'Returns an image (requires: screen consent).', inputSchema: { type: 'object', properties: {} }, annotations: { readOnlyHint: true, destructiveHint: false, title: 'Picture' }, requires: 'screen' },
    { name: 'records.peek', description: 'Looks at records (requires: records consent).', inputSchema: { type: 'object', properties: {} }, annotations: { readOnlyHint: true, destructiveHint: false, title: 'Peek' }, requires: 'records' },
    { name: 'instruments.restart', description: 'Restart an analyzer link.', inputSchema: { type: 'object', properties: { instrument_id: { type: 'string' } } }, annotations: { readOnlyHint: false, destructiveHint: true, title: 'Restart analyzer' } },
    { name: 'boom', description: 'Always fails.', inputSchema: { type: 'object', properties: {} }, annotations: { readOnlyHint: true, destructiveHint: false, title: 'Boom' } },
    { name: 'slow', description: 'Never answers.', inputSchema: { type: 'object', properties: {} }, annotations: { readOnlyHint: true, destructiveHint: false, title: 'Slow' } },
];

export class FakeRelay {
    constructor(opts = {}) {
        this.sessionId = opts.sessionId ?? '4b0e6c2a-9d1f-4c3e-8a7b-2f5d6e1c9a01';
        this.code = opts.code ?? 'Q7K3MX9P';
        this.token = opts.token ?? 'tok-test';
        this.lab = opts.lab ?? 'Sunrise Diagnostics';
        this.appVersion = opts.appVersion ?? '1.4.0';
        this.consent = opts.consent ?? { analyzer_data: false, records: false, screen: true };
        this.expiresInS = opts.expiresInS ?? 3600;
        this.publicKey = opts.publicKey ?? null;
        /** Where `initialize` carries the session facts: '_meta' | 'bnm' | 'none'. */
        this.metaShape = opts.metaShape ?? '_meta';
        /** Relay ack after a good join: 'none' | 'joined' | 'ready'. */
        this.joinAck = opts.joinAck ?? 'none';
        /** Lab seat empty: every forwarded frame is answered `no_lab`, socket left open. */
        this.labAway = opts.labAway ?? false;
        this.tools = opts.tools ?? DEFAULT_TOOLS;
        this.now = opts.now ?? Date.now;

        this.sockets = [];
        this.support = null; // the paired support socket
        this.calls = []; // every verified tools/call {name, arguments, id, nonce}
        this.received = []; // every JSON-RPC frame after join, in order
        this.refusals = [];
        this.nonces = new Set();
        this.actions = 0;
        this.factory = (url, init) => this.#open(url, init);
    }

    // ── what tests poke ──

    labDisconnected() {
        this.support?.serverSend({ t: 'peer', state: 'lab_disconnected' });
    }

    /** What the DO sends the engineer when a lab takes the seat again (session.ts). */
    labReconnected(state = 'lab_connected') {
        this.support?.serverSend({ t: 'peer', state });
    }

    /** The DO's answer when a frame arrives while the lab seat is empty: no close. */
    noLab() {
        this.support?.serverSend({ t: 'error', code: 'no_lab' });
    }

    labEnd() {
        const s = this.support;
        if (!s) return;
        s.serverSend({ t: 'end' });
        queueMicrotask(() => s.serverClose(1000, 'ended'));
        this.support = null;
    }

    dropSupport(code = 1006) {
        this.support?.serverClose(code, '');
        this.support = null;
    }

    // ── relay behaviour ──

    #open(url, init) {
        const sock = new FakeSocket();
        this.sockets.push(sock);
        this.lastUrl = url;
        this.lastHeaders = init?.headers ?? {};
        sock.onSend = (text) => this.#onFrame(sock, text);
        queueMicrotask(() => {
            if (this.lastHeaders.Authorization !== `Bearer ${this.token}`) sock.serverReject();
            else sock.serverOpen();
        });
        return sock;
    }

    #onFrame(sock, text) {
        const msg = JSON.parse(text);
        if (msg.t === 'join') {
            if (msg.code !== this.code) {
                sock.serverSend({ t: 'error', code: 'no_session' });
                queueMicrotask(() => sock.serverClose(1008, 'no_session'));
                return;
            }
            if (this.support && this.support.readyState === 1) {
                sock.serverSend({ t: 'error', code: 'busy' });
                queueMicrotask(() => sock.serverClose(1008, 'busy'));
                return;
            }
            sock.paired = true;
            this.support = sock;
            if (this.joinAck !== 'none') sock.serverSend({ t: this.joinAck, session_id: this.sessionId, server_time_ms: this.now() });
            return;
        }
        if (!sock.paired) return; // the relay drops frames from unpaired sockets
        this.received.push(msg);
        if (this.labAway) return this.noLab();
        this.#lab(sock, msg);
    }

    // ── lab behaviour ──

    #lab(sock, msg) {
        const reply = (result) => sock.serverSend({ jsonrpc: '2.0', id: msg.id, result });
        const error = (code, message) => sock.serverSend({ jsonrpc: '2.0', id: msg.id, error: { code, message } });
        switch (msg.method) {
            case 'initialize': {
                const facts = { session_id: this.sessionId, lab: this.lab, app_version: this.appVersion, consent: this.consent, expires_in_s: this.expiresInS };
                const result = { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'bnm-lab', version: this.appVersion } };
                if (this.metaShape === '_meta') result._meta = { bnm: facts };
                if (this.metaShape === 'bnm') result.bnm = facts;
                return reply(result);
            }
            case 'notifications/initialized':
                this.initialized = true;
                return;
            case 'ping':
                return reply({});
            case 'tools/list':
                return reply({ tools: this.tools.map(({ requires, ...t }) => t) });
            case 'tools/call':
                return this.#call(msg, reply, error);
            default:
                return error(-32601, `Method not found: ${msg.method}`);
        }
    }

    #call(msg, reply, error) {
        const refuse = (why) => {
            this.refusals.push(why);
            this.actions++;
            return error(-32001, why);
        };
        const bnm = msg.bnm;
        if (!bnm || typeof bnm.sig !== 'string') return refuse('Unsigned request');
        const now = this.now();
        if (Math.abs(bnm.ts_ms - now) > TS_WINDOW_MS) return refuse('Request too old or too far in the future');
        if (this.nonces.has(bnm.nonce)) return refuse('Nonce already used');
        const payload = payloadFor({ sessionId: this.sessionId, id: msg.id, method: msg.method, tsMs: bnm.ts_ms, nonce: bnm.nonce, params: msg.params });
        let ok = false;
        try {
            ok = this.publicKey ? crypto.verify(null, Buffer.from(payload, 'utf8'), this.publicKey, Buffer.from(bnm.sig, 'base64')) : false;
        } catch {
            ok = false;
        }
        if (!ok) return refuse('Bad signature');
        this.nonces.add(bnm.nonce);

        const name = msg.params?.name;
        const args = msg.params?.arguments ?? {};
        const spec = this.tools.find((t) => t.name === name);
        if (!spec) return error(-32602, `Unknown tool: ${name}`);
        if (spec.requires && !this.consent[spec.requires]) return refuse(`Requires ${spec.requires} consent`);
        this.actions++;
        this.calls.push({ name, arguments: args, id: msg.id, nonce: bnm.nonce, ts_ms: bnm.ts_ms });
        const text = (s) => ({ content: [{ type: 'text', text: s }], isError: false });
        switch (name) {
            case 'session.info':
                return reply(text(JSON.stringify({ lab: this.lab, app_version: this.appVersion, consent: this.consent, remaining_s: this.expiresInS - 5, actions: this.actions, peer: 'connected' })));
            case 'echo':
                return reply(text(String(args.text ?? '')));
            case 'picture':
                return reply({ content: [{ type: 'image', data: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', mimeType: 'image/png' }], isError: false });
            case 'records.peek':
                return reply(text('{"patients":0}'));
            case 'instruments.restart':
                return reply(text(`restarted ${args.instrument_id ?? 'all'}`));
            case 'boom':
                return error(-32603, 'Tool crashed');
            case 'slow':
                return; // never answers
            default:
                return error(-32602, `Unknown tool: ${name}`);
        }
    }
}

/** Waits until `fn()` is truthy (polling microtasks/timers), for the async fake. */
export async function until(fn, { timeoutMs = 2000 } = {}) {
    const deadline = Date.now() + timeoutMs;
    while (!fn()) {
        if (Date.now() > deadline) throw new Error('until(): condition not met in time');
        await new Promise((r) => setTimeout(r, 2));
    }
    return fn();
}
