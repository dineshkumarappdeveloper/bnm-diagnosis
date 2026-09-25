#!/usr/bin/env node
/**
 * tools/remote-mcp/bnmlab-remote.mjs — BNM Lab remote support, the engineer's side.
 *
 * A stdio MCP server that Claude Code (or any MCP client) talks to. It dials
 * BNM's relay over WSS, joins the session a lab owner started, and forwards
 * tool calls to that lab's copy of BNM Lab. Every `tools/call` it forwards is
 * signed with the engineer's Ed25519 support key (contract §2.4); the lab
 * verifies the signature against the public key compiled into the app
 * (`RemoteSupportKeys.kt`) and refuses anything else.
 *
 * Zero dependencies — Node ≥ 22 (global `WebSocket`, `node:crypto` Ed25519).
 *
 *   claude mcp add bnmlab-remote \
 *     -e BNM_RELAY_URL=wss://lab-relay.bnmapp.com \
 *     -e BNM_SUPPORT_TOKEN=… \
 *     -- node /abs/path/tools/remote-mcp/bnmlab-remote.mjs
 *
 *   node bnmlab-remote.mjs keygen [path] [--force]   # new key (0600) + SPKI base64 to embed
 *   node bnmlab-remote.mjs pubkey [path]             # SPKI base64 of an existing key
 *
 * Environment: BNM_RELAY_URL (default wss://lab-relay.bnmapp.com — a placeholder
 * until the relay is deployed), BNM_SUPPORT_TOKEN (the relay's engineer token),
 * BNM_SUPPORT_KEY_FILE (default ~/.config/bnmlab-remote/support.key, PEM PKCS#8).
 *
 * stdout carries JSON-RPC lines ONLY. Diagnostics go to stderr and never include
 * message bodies, session codes, tokens or keys.
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// ─────────────────────────────────────────────────────────────────────────────
//  Constants
// ─────────────────────────────────────────────────────────────────────────────

export const PROTOCOL_VERSION = '2025-06-18';
export const SERVER_NAME = 'bnmlab-remote';
export const DEFAULT_RELAY_URL = 'wss://lab-relay.bnmapp.com';
export const DEFAULT_KEY_FILE = path.join(os.homedir(), '.config', 'bnmlab-remote', 'support.key');
/** Contract §2.4 — the first line of everything the engineer signs. */
export const SIGNING_PREFIX = 'bnm-lab-support-v1';
/** Crockford base32 without I/L/O/U, as the app generates session codes. */
const CODE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const CODE_LENGTH = 8;

const CONNECT_TIMEOUT_MS = 15_000;
const REQUEST_TIMEOUT_MS = 60_000;
/** The lab refuses frames over 900 KB; our arguments should never get near it. */
const MAX_ARGS_BYTES = 256 * 1024;

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export const VERSION = (() => {
    try {
        return JSON.parse(fs.readFileSync(path.join(__dirname, 'package.json'), 'utf8')).version ?? '0.0.0';
    } catch {
        return '0.0.0';
    }
})();

/** An error whose message is meant for the engineer — plain words, no stack. */
export class BridgeError extends Error {}

// ─────────────────────────────────────────────────────────────────────────────
//  Canonical JSON + signing (contract §2.4) — must match CanonicalJson.kt
// ─────────────────────────────────────────────────────────────────────────────

/**
 * JSON with object keys sorted recursively (by UTF-16 code unit, the same order
 * Kotlin's `sorted()` gives), no whitespace, numbers exactly as JSON.stringify
 * emits them — which is also how they go on the wire, so the lab sees the same
 * literals it canonicalises. Mirrors JSON.stringify's edge cases (undefined
 * members dropped, NaN → null, toJSON honoured) so the string we sign is the
 * string the lab can rebuild from the body we send.
 */
export function canonicalize(value) {
    if (value !== null && typeof value === 'object' && typeof value.toJSON === 'function') {
        value = value.toJSON();
    }
    if (value === null || typeof value !== 'object') {
        const s = JSON.stringify(value);
        return s === undefined ? 'null' : s;
    }
    if (Array.isArray(value)) {
        return '[' + value.map((v) => canonicalize(v)).join(',') + ']';
    }
    const parts = [];
    for (const key of Object.keys(value).sort()) {
        const v = value[key];
        if (v === undefined || typeof v === 'function' || typeof v === 'symbol') continue;
        parts.push(JSON.stringify(key) + ':' + canonicalize(v));
    }
    return '{' + parts.join(',') + '}';
}

export function sha256Hex(text) {
    return crypto.createHash('sha256').update(text, 'utf8').digest('hex');
}

/** The exact UTF-8 text that is signed. `id` is the JSON-RPC id as a string. */
export function signingPayload({ sessionId, id, method, tsMs, nonce, params }) {
    return [SIGNING_PREFIX, sessionId, String(id), method, String(tsMs), nonce, canonicalize(params ?? {})].join('\n');
}

/** Ed25519 signature, base64, over `signingPayload(fields)`. */
export function signRequest(privateKey, fields) {
    return crypto.sign(null, Buffer.from(signingPayload(fields), 'utf8'), privateKey).toString('base64');
}

/** What the lab does with the public key; here for tests and the fake relay. */
export function verifyRequest(publicKey, fields, sigB64) {
    try {
        return crypto.verify(null, Buffer.from(signingPayload(fields), 'utf8'), publicKey, Buffer.from(sigB64, 'base64'));
    } catch {
        return false;
    }
}

export function newNonce() {
    return crypto.randomBytes(16).toString('hex');
}

// ─────────────────────────────────────────────────────────────────────────────
//  Keys
// ─────────────────────────────────────────────────────────────────────────────

export function loadPrivateKey(file) {
    let pem;
    try {
        pem = fs.readFileSync(file, 'utf8');
    } catch {
        throw new BridgeError(
            `Support key not found at ${file}. Run "bnmlab-remote.mjs keygen" once, then have the ` +
                `public key it prints embedded in the app (RemoteSupportKeys.kt).`,
        );
    }
    let key;
    try {
        key = crypto.createPrivateKey(pem);
    } catch {
        throw new BridgeError(`Support key at ${file} is not a readable PEM private key.`);
    }
    if (key.asymmetricKeyType !== 'ed25519') {
        throw new BridgeError(`Support key at ${file} is ${key.asymmetricKeyType}, not Ed25519. Run keygen to make a new one.`);
    }
    return key;
}

/** X.509 SubjectPublicKeyInfo DER, base64 — the form `RemoteSupportKeys.kt` embeds. */
export function spkiBase64(key) {
    return crypto.createPublicKey(key).export({ type: 'spki', format: 'der' }).toString('base64');
}

/** `support.key` → `support.pub`; anything else gets `.pub` appended. */
export function pubFileFor(keyFile) {
    return keyFile.endsWith('.key') ? keyFile.slice(0, -4) + '.pub' : keyFile + '.pub';
}

/** Writes a fresh Ed25519 key (0600) and its SPKI beside it; returns what to embed. */
export function keygen(file, { force = false } = {}) {
    if (fs.existsSync(file) && !force) {
        throw new BridgeError(`${file} already exists. Pass --force to replace it (the old public key stops working everywhere it is embedded).`);
    }
    const { privateKey } = crypto.generateKeyPairSync('ed25519');
    fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
    fs.writeFileSync(file, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
    fs.chmodSync(file, 0o600); // writeFileSync's mode is ignored when the file existed
    const pub = spkiBase64(privateKey);
    const pubFile = pubFileFor(file);
    fs.writeFileSync(pubFile, pub + '\n', { mode: 0o644 });
    return { file, pubFile, pub };
}

// ─────────────────────────────────────────────────────────────────────────────
//  Session codes
// ─────────────────────────────────────────────────────────────────────────────

/** Uppercase, dashes/spaces dropped, I/L→1, O→0 — the same forgiveness the relay applies. */
export function normalizeCode(raw) {
    const cleaned = String(raw ?? '')
        .toUpperCase()
        .replace(/[\s-]/g, '')
        .replace(/[IL]/g, '1')
        .replace(/O/g, '0');
    if (cleaned.length !== CODE_LENGTH || [...cleaned].some((c) => !CODE_ALPHABET.includes(c))) {
        throw new BridgeError('A session code has 8 letters and digits, shown on the lab\'s screen as XXXX-XXXX (for example 7K3M-QX9P).');
    }
    return cleaned;
}

export function describeRemaining(seconds) {
    if (seconds == null) return 'unknown';
    if (seconds <= 0) return 'ended';
    const m = Math.floor(seconds / 60);
    if (m < 1) return 'under a minute';
    const h = Math.floor(m / 60);
    if (h > 0) return m % 60 === 0 ? `${h} h` : `${h} h ${m % 60} min`;
    return `${m} min`;
}

// ─────────────────────────────────────────────────────────────────────────────
//  LabLink — one connection to one lab through the relay
// ─────────────────────────────────────────────────────────────────────────────

const defaultWsFactory = (url, opts) => new WebSocket(url, opts);

const RELAY_ERRORS = {
    no_session: 'No support session with that code. Ask the lab to check the code on their screen — the session may have ended or the code was misread.',
    busy: 'Another engineer is already connected to this lab session. Only one connection is allowed at a time.',
};

/**
 * Owns the WebSocket to `<relay>/v1/support`, the join handshake, request/
 * response pairing and signing. The socket comes from an injectable factory so
 * tests run against an in-process fake (`test/fake-relay.mjs`).
 */
export class LabLink {
    #ws = null;
    #pending = new Map();
    #nextId = 1;
    #privateKey = null;
    #keyError = null;

    constructor({ relayUrl = DEFAULT_RELAY_URL, token, keyFile = DEFAULT_KEY_FILE, wsFactory = defaultWsFactory, now = Date.now, log = () => {}, connectMs = CONNECT_TIMEOUT_MS, requestMs = REQUEST_TIMEOUT_MS } = {}) {
        this.relayUrl = relayUrl;
        this.connectMs = connectMs;
        this.requestMs = requestMs;
        this.token = token;
        this.keyFile = keyFile;
        this.wsFactory = wsFactory;
        this.now = now;
        this.log = log;
        this.state = 'disconnected'; // disconnected | connecting | connected
        this.peer = 'none'; // none | lab | lab_disconnected
        this.sessionId = null;
        this.info = null; // { lab, appVersion, consent, remainingS, at }
        this.actions = 0;
        this.lastError = null;
    }

    // ── public ──

    async connect(code) {
        const normalized = normalizeCode(code);
        if (!this.token) throw new BridgeError('BNM_SUPPORT_TOKEN is not set. Add it to the MCP server\'s environment (claude mcp add … -e BNM_SUPPORT_TOKEN=…).');
        if (!/^wss?:\/\//.test(this.relayUrl)) throw new BridgeError(`BNM_RELAY_URL must start with wss:// (got "${this.relayUrl}").`);
        if (this.state !== 'disconnected') this.disconnect('reconnecting');

        const url = this.relayUrl.replace(/\/+$/, '') + '/v1/support';
        this.state = 'connecting';
        this.lastError = null;
        let ws;
        try {
            ws = this.wsFactory(url, { headers: { Authorization: `Bearer ${this.token}` } });
        } catch (e) {
            this.state = 'disconnected';
            throw new BridgeError(`Could not open a connection to the relay: ${e?.message ?? e}`);
        }
        this.#ws = ws;
        try {
            await this.#awaitOpen(ws);
        } catch (e) {
            this.#dropSocket();
            throw e;
        }
        ws.addEventListener('message', (ev) => this.#onMessage(ws, ev));
        ws.addEventListener('close', (ev) => this.#onClose(ws, ev));
        ws.addEventListener('error', () => this.#onSocketError(ws));

        ws.send(JSON.stringify({ t: 'join', code: normalized }));
        // The relay pairs us on `join`; if the code is wrong we get an error frame
        // (or a close) before anything answers `initialize`, and #onMessage /
        // #onClose reject the pending request with the reason.
        const init = await this.#request('initialize', {
            protocolVersion: PROTOCOL_VERSION,
            capabilities: {},
            clientInfo: { name: SERVER_NAME, version: VERSION },
        });
        this.#notify('notifications/initialized');
        this.state = 'connected';
        this.peer = 'lab';
        this.actions = 0;
        this.#absorbInfo(init);
        this.log('connected to the lab through the relay');

        // Freshest picture of the session: consent, time left, actions so far.
        // Best effort — a missing or unembedded key must not hide the connection.
        let note = null;
        if (this.sessionId) {
            try {
                const res = await this.callTool('session.info', {});
                this.#absorbSessionInfo(res);
            } catch (e) {
                note = e instanceof LabRpcError && e.refused
                    ? `Connected, but the lab refused the first signed call: ${e.message}. Check this computer's clock and that the app embeds this support key.`
                    : `Connected, but the first signed call failed: ${e.message}`;
            }
        } else {
            note = 'The lab did not send its session id, so calls cannot be signed yet — check that the app and the bridge are the same version.';
        }
        return { ...this.summary(), note: note ?? undefined };
    }

    async listTools() {
        this.#requireConnected();
        const res = await this.#request('tools/list', {});
        return res?.tools ?? [];
    }

    /** Signed `tools/call`; resolves with the lab's result (content + isError), rejects on refusal or failure. */
    async callTool(name, args = {}) {
        this.#requireConnected();
        if (!this.sessionId) throw new BridgeError('No session id from the lab; cannot sign the call. Reconnect with lab_connect.');
        const key = this.#loadKey();
        const params = { name, arguments: args ?? {} };
        if (Buffer.byteLength(JSON.stringify(params), 'utf8') > MAX_ARGS_BYTES) {
            throw new BridgeError('Arguments are too large (over 256 KB). Send less at a time.');
        }
        const id = this.#nextId++;
        const tsMs = this.now();
        const nonce = newNonce();
        const sig = signRequest(key, { sessionId: this.sessionId, id, method: 'tools/call', tsMs, nonce, params });
        this.actions++;
        return this.#send({ jsonrpc: '2.0', id, method: 'tools/call', params, bnm: { ts_ms: tsMs, nonce, sig } });
    }

    disconnect(reason = 'disconnect') {
        if (this.state === 'disconnected' && !this.#ws) return false;
        const ws = this.#ws;
        this.#dropSocket();
        this.#rejectAll(new BridgeError('Disconnected from the lab.'));
        try {
            ws?.close(1000, reason);
        } catch {
            /* already closed */
        }
        this.log(`disconnected (${reason})`);
        return true;
    }

    remainingS() {
        if (!this.info || this.info.remainingS == null) return null;
        return Math.max(0, Math.round(this.info.remainingS - (this.now() - this.info.at) / 1000));
    }

    summary() {
        const remainingS = this.remainingS();
        return {
            connection: this.state,
            relay: this.relayUrl,
            peer: this.state === 'connected' ? (this.peer === 'lab' ? 'lab connected' : 'lab disconnected') : 'none',
            lab: this.info?.lab ?? null,
            app_version: this.info?.appVersion ?? null,
            session: this.sessionId ? this.sessionId.slice(0, 8) : null,
            consent: this.info?.consent ?? null,
            remaining: describeRemaining(remainingS),
            remaining_s: remainingS,
            actions: this.actions,
            last_error: this.lastError ?? undefined,
        };
    }

    // ── private ──

    #loadKey() {
        if (this.#privateKey) return this.#privateKey;
        if (this.#keyError) throw this.#keyError;
        try {
            this.#privateKey = loadPrivateKey(this.keyFile);
            return this.#privateKey;
        } catch (e) {
            this.#keyError = e;
            throw e;
        }
    }

    #requireConnected() {
        if (this.state !== 'connected') {
            throw new BridgeError('Not connected to a lab. Call lab_connect with the session code the lab owner read to you.');
        }
        if (this.peer === 'lab_disconnected') {
            throw new BridgeError('The lab\'s connection to the relay dropped (it may be reconnecting). Try again shortly, or lab_disconnect and lab_connect again.');
        }
    }

    #awaitOpen(ws) {
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => fail(new BridgeError(`The relay at ${hostOf(this.relayUrl)} did not answer within ${this.connectMs / 1000} s.`)), this.connectMs);
            timer.unref?.();
            const done = (fn, v) => {
                clearTimeout(timer);
                fn(v);
            };
            const fail = (err) => done(reject, err);
            ws.addEventListener('open', () => done(resolve), { once: true });
            ws.addEventListener('error', () => fail(new BridgeError(
                `Could not connect to the relay at ${hostOf(this.relayUrl)}. Check BNM_RELAY_URL and BNM_SUPPORT_TOKEN — a wrong token is refused at the door and looks exactly like this.`,
            )), { once: true });
            ws.addEventListener('close', (ev) => fail(new BridgeError(`The relay closed the connection (${ev?.code ?? '?'}${ev?.reason ? ': ' + ev.reason : ''}).`)), { once: true });
        });
    }

    #request(method, params, timeoutMs = this.requestMs) {
        const id = this.#nextId++;
        return this.#send({ jsonrpc: '2.0', id, method, params }, timeoutMs);
    }

    #notify(method, params) {
        const msg = { jsonrpc: '2.0', method };
        if (params !== undefined) msg.params = params;
        this.#ws?.send(JSON.stringify(msg));
    }

    #send(msg, timeoutMs = this.requestMs) {
        const ws = this.#ws;
        if (!ws) return Promise.reject(new BridgeError('Not connected to a lab.'));
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                this.#pending.delete(msg.id);
                reject(new BridgeError(`The lab did not answer within ${timeoutMs / 1000} s (${msg.method}${msg.params?.name ? ' ' + msg.params.name : ''}). It may be busy or the connection dropped — check lab_status.`));
            }, timeoutMs);
            timer.unref?.();
            this.#pending.set(msg.id, { resolve, reject, timer, method: msg.method });
            try {
                ws.send(JSON.stringify(msg));
            } catch (e) {
                clearTimeout(timer);
                this.#pending.delete(msg.id);
                reject(new BridgeError(`Could not send to the relay: ${e?.message ?? e}`));
            }
        });
    }

    #onMessage(ws, ev) {
        if (ws !== this.#ws) return;
        const text = typeof ev.data === 'string' ? ev.data : null;
        if (text === null) return; // the lab only sends text frames
        let msg;
        try {
            msg = JSON.parse(text);
        } catch {
            this.log('ignored a frame that was not JSON');
            return;
        }
        if (!msg || typeof msg !== 'object') return;

        if (typeof msg.t === 'string') return this.#onRelayNotice(msg);

        if (msg.id !== undefined && msg.id !== null && ('result' in msg || 'error' in msg)) {
            const p = this.#pending.get(msg.id);
            if (!p) return;
            this.#pending.delete(msg.id);
            clearTimeout(p.timer);
            if ('error' in msg && msg.error) {
                const code = msg.error.code;
                const message = msg.error.message ?? 'no message';
                p.reject(new LabRpcError(code, message, msg.error.data));
            } else {
                p.resolve(msg.result);
            }
            return;
        }
        // A request from the lab (it may ping us); anything else is a notification we ignore.
        if (msg.method === 'ping' && msg.id !== undefined) {
            ws.send(JSON.stringify({ jsonrpc: '2.0', id: msg.id, result: {} }));
        }
    }

    #onRelayNotice(msg) {
        switch (msg.t) {
            case 'joined':
            case 'ready':
                if (typeof msg.session_id === 'string') this.sessionId = msg.session_id;
                return;
            case 'peer':
                if (msg.state === 'lab_disconnected') {
                    this.peer = 'lab_disconnected';
                    this.log('the lab dropped off the relay');
                } else if (msg.state === 'connected') {
                    this.peer = 'lab';
                }
                return;
            case 'error': {
                const reason = RELAY_ERRORS[msg.code] ?? `The relay refused the connection (${msg.code ?? 'unknown reason'}).`;
                this.lastError = reason;
                this.#rejectAll(new BridgeError(reason));
                this.#dropSocket();
                return;
            }
            case 'end':
                this.lastError = 'The lab ended the support session.';
                this.#rejectAll(new BridgeError(this.lastError));
                this.#dropSocket();
                this.log('the lab ended the session');
                return;
            default:
                return;
        }
    }

    #onClose(ws, ev) {
        if (ws !== this.#ws) return;
        const reason = ev?.code === 1000 ? 'The connection to the lab was closed.' : `The connection to the relay closed (${ev?.code ?? '?'}${ev?.reason ? ': ' + ev.reason : ''}).`;
        if (!this.lastError) this.lastError = reason;
        this.#rejectAll(new BridgeError(this.lastError));
        this.#dropSocket();
        this.log('socket closed');
    }

    #onSocketError(ws) {
        if (ws !== this.#ws) return;
        if (!this.lastError) this.lastError = 'The connection to the relay failed.';
    }

    #rejectAll(err) {
        for (const p of this.#pending.values()) {
            clearTimeout(p.timer);
            p.reject(err);
        }
        this.#pending.clear();
    }

    #dropSocket() {
        this.#ws = null;
        this.state = 'disconnected';
        this.peer = 'none';
    }

    /** Session facts the lab tucks into its `initialize` result (`_meta.bnm` or `bnm`). */
    #absorbInfo(init) {
        const bnm = init?._meta?.bnm ?? init?.bnm ?? {};
        if (typeof bnm.session_id === 'string') this.sessionId = bnm.session_id;
        this.info = {
            lab: bnm.lab ?? null,
            appVersion: init?.serverInfo?.version ?? bnm.app_version ?? null,
            consent: normalizeConsent(bnm.consent),
            remainingS: pickNumber(bnm.remaining_s, bnm.expires_in_s),
            at: this.now(),
        };
    }

    /** `session.info` answers with a text content item holding JSON. */
    #absorbSessionInfo(res) {
        const text = res?.content?.find?.((c) => c?.type === 'text')?.text;
        if (!text) return;
        let obj;
        try {
            obj = JSON.parse(text);
        } catch {
            return;
        }
        if (!obj || typeof obj !== 'object') return;
        this.info = {
            lab: obj.lab ?? this.info?.lab ?? null,
            appVersion: obj.app_version ?? this.info?.appVersion ?? null,
            consent: normalizeConsent(obj.consent) ?? this.info?.consent ?? null,
            remainingS: pickNumber(obj.remaining_s, obj.expires_in_s) ?? this.info?.remainingS ?? null,
            at: this.now(),
        };
        if (typeof obj.actions === 'number') this.actions = obj.actions;
    }
}

/** A JSON-RPC error the lab answered with; −32001 is a policy refusal (contract §2.4). */
export class LabRpcError extends Error {
    constructor(code, message, data) {
        super(message);
        this.code = code;
        this.data = data;
    }
    get refused() {
        return this.code === -32001;
    }
}

function hostOf(url) {
    try {
        return new URL(url).host;
    } catch {
        return url;
    }
}

function pickNumber(...candidates) {
    for (const c of candidates) if (typeof c === 'number' && Number.isFinite(c)) return c;
    return null;
}

function normalizeConsent(c) {
    if (!c || typeof c !== 'object') return null;
    return {
        analyzer_data: Boolean(c.analyzer_data ?? c.analyzerData),
        records: Boolean(c.records),
        screen: Boolean(c.screen),
    };
}

// ─────────────────────────────────────────────────────────────────────────────
//  The five bridge tools
// ─────────────────────────────────────────────────────────────────────────────

const noArgs = { type: 'object', properties: {}, additionalProperties: false };

export const TOOLS = [
    {
        name: 'lab_connect',
        description:
            'Connect to a lab\'s BNM Lab through the relay using the 8-character session code the lab owner reads out ' +
            '(shown on their screen as XXXX-XXXX). Returns the lab name, app version, what the owner consented to, ' +
            'and how long the session has left. Read lab_tools next.',
        inputSchema: {
            type: 'object',
            properties: { code: { type: 'string', description: 'Session code from the lab\'s screen, e.g. 7K3M-QX9P (dashes, spaces and case do not matter).' } },
            required: ['code'],
            additionalProperties: false,
        },
        annotations: { title: 'Connect to a lab', readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true },
    },
    {
        name: 'lab_status',
        description: 'Connection state, whether the lab is on the relay, time left in the session, and how many actions this bridge has sent. Works without a connection.',
        inputSchema: noArgs,
        annotations: { title: 'Session status', readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    {
        name: 'lab_tools',
        description:
            'List the tools the connected lab offers (name, description, input schema, annotations). Read each tool\'s ' +
            'annotations before calling it: destructiveHint means it changes something on the lab PC — ask the engineer ' +
            'before calling those. Tools marked "(requires: … consent)" fail unless the owner ticked that box.',
        inputSchema: noArgs,
        annotations: { title: 'List the lab\'s tools', readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true },
    },
    {
        name: 'lab_call',
        description:
            'Call one of the lab\'s tools (from lab_tools) with its arguments. The call is signed with the engineer\'s ' +
            'support key and recorded in the lab\'s Support history. The lab\'s own annotation for the tool is what ' +
            'matters: if it is destructive (restart, set_config, delete, update), confirm with the engineer first. ' +
            'Returns the lab\'s content verbatim — text, or an image for app.screenshot.',
        inputSchema: {
            type: 'object',
            properties: {
                tool: { type: 'string', description: 'Tool name exactly as listed by lab_tools, e.g. instruments.list' },
                args: { type: 'object', description: 'Arguments object matching that tool\'s inputSchema (omit for none).', additionalProperties: true },
            },
            required: ['tool'],
            additionalProperties: false,
        },
        annotations: { title: 'Call a lab tool', readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true },
    },
    {
        name: 'lab_disconnect',
        description: 'Drop the connection to the lab. The owner\'s session keeps running until they end it or it expires; you can lab_connect again with the same code.',
        inputSchema: noArgs,
        annotations: { title: 'Disconnect', readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true },
    },
];

const text = (s) => ({ type: 'text', text: s });
const okResult = (s) => ({ content: [text(s)], isError: false });
const errResult = (s) => ({ content: [text(s)], isError: true });
const pretty = (o) => JSON.stringify(o, null, 2);

/** Runs a bridge tool; returns an MCP CallToolResult. Never throws for expected failures. */
export async function runTool(link, name, args) {
    args = args && typeof args === 'object' && !Array.isArray(args) ? args : {};
    try {
        switch (name) {
            case 'lab_connect': {
                if (typeof args.code !== 'string' || !args.code.trim()) return errResult('lab_connect needs the session code from the lab\'s screen (XXXX-XXXX).');
                const s = await link.connect(args.code);
                const lines = [
                    `Connected to ${s.lab ? `"${s.lab}"` : 'the lab'}${s.app_version ? ` (BNM Lab ${s.app_version})` : ''}.`,
                    `Session ends in ${s.remaining}. Consent — analyzer data: ${yn(s.consent?.analyzer_data)}, records: ${yn(s.consent?.records)}, screen: ${yn(s.consent?.screen)}.`,
                    'Run lab_tools next and read the annotations before calling anything.',
                ];
                if (s.note) lines.push(`Note: ${s.note}`);
                return okResult(lines.join('\n') + '\n' + pretty(s));
            }
            case 'lab_status':
                return okResult(pretty(link.summary()));
            case 'lab_tools': {
                const tools = await link.listTools();
                return okResult(`${tools.length} tools on the lab. Destructive ones (annotations.destructiveHint) need the engineer's go-ahead.\n` + pretty(tools));
            }
            case 'lab_call': {
                if (typeof args.tool !== 'string' || !args.tool.trim()) return errResult('lab_call needs "tool" — a name from lab_tools.');
                if (args.args !== undefined && (args.args === null || typeof args.args !== 'object' || Array.isArray(args.args))) {
                    return errResult('lab_call "args" must be an object matching the tool\'s inputSchema.');
                }
                const res = await link.callTool(args.tool.trim(), args.args ?? {});
                const content = Array.isArray(res?.content) && res.content.length ? res.content : [text(typeof res === 'string' ? res : pretty(res ?? {}))];
                return { content, isError: Boolean(res?.isError) };
            }
            case 'lab_disconnect':
                return okResult(link.disconnect('engineer disconnected') ? 'Disconnected from the lab.' : 'Not connected to any lab.');
            default:
                return null; // caller turns this into JSON-RPC −32602
        }
    } catch (e) {
        if (e instanceof LabRpcError) {
            return errResult(e.refused ? `Refused by the lab: ${e.message}` : `The lab answered with an error (${e.code}): ${e.message}`);
        }
        if (e instanceof BridgeError) return errResult(e.message);
        return errResult(`Unexpected failure in the bridge: ${e?.message ?? e}`);
    }
}

const yn = (b) => (b ? 'yes' : 'no');

// ─────────────────────────────────────────────────────────────────────────────
//  BridgeServer — newline-delimited JSON-RPC 2.0 over stdio
// ─────────────────────────────────────────────────────────────────────────────

const rpcError = (id, code, message) => ({ jsonrpc: '2.0', id: id ?? null, error: { code, message } });

export class BridgeServer {
    #buffer = '';

    constructor({ link, input = process.stdin, output = process.stdout, log = () => {} }) {
        this.link = link;
        this.input = input;
        this.output = output;
        this.log = log;
        this.closed = new Promise((resolve) => (this.#resolveClosed = resolve));
    }
    #resolveClosed;

    start() {
        this.input.setEncoding?.('utf8');
        this.input.on('data', (chunk) => this.#onChunk(String(chunk)));
        this.input.on('end', () => this.#onEnd());
        this.input.on('error', () => this.#onEnd());
        return this;
    }

    #onChunk(chunk) {
        this.#buffer += chunk;
        let nl;
        while ((nl = this.#buffer.indexOf('\n')) >= 0) {
            const line = this.#buffer.slice(0, nl);
            this.#buffer = this.#buffer.slice(nl + 1);
            void this.handleLine(line);
        }
    }

    #onEnd() {
        if (this.#buffer.trim()) {
            const rest = this.#buffer;
            this.#buffer = '';
            void this.handleLine(rest);
        }
        this.link.disconnect('client closed');
        this.#resolveClosed();
    }

    /** One line in → zero or one JSON line out (written to `output`). */
    async handleLine(line) {
        const trimmed = line.replace(/\r$/, '').trim();
        if (!trimmed) return null;
        let msg;
        try {
            msg = JSON.parse(trimmed);
        } catch {
            return this.#write(rpcError(null, -32700, 'Parse error: each line must be one JSON-RPC 2.0 message'));
        }
        const response = await this.handleMessage(msg);
        return response ? this.#write(response) : null;
    }

    /** Pure dispatch — returns the response object, or null for notifications. */
    async handleMessage(msg) {
        if (Array.isArray(msg)) return rpcError(null, -32600, 'Batch requests are not supported');
        if (!msg || typeof msg !== 'object' || typeof msg.method !== 'string') {
            return rpcError(msg?.id, -32600, 'Invalid Request: expected an object with a "method"');
        }
        const { id, method, params } = msg;
        const isNotification = id === undefined || id === null;
        try {
            switch (method) {
                case 'initialize':
                    return this.#reply(id, {
                        protocolVersion: PROTOCOL_VERSION,
                        capabilities: { tools: {} },
                        serverInfo: { name: SERVER_NAME, version: VERSION },
                        instructions:
                            'Bridge to a lab\'s BNM Lab for remote support. Ask the engineer for the session code, lab_connect, ' +
                            'then lab_tools; read each lab tool\'s annotations and confirm with the engineer before any destructive lab_call.',
                    });
                case 'ping':
                    return this.#reply(id, {});
                case 'tools/list':
                    return this.#reply(id, { tools: TOOLS });
                case 'tools/call': {
                    const name = params?.name;
                    if (typeof name !== 'string') return rpcError(id, -32602, 'tools/call needs params.name');
                    const result = await runTool(this.link, name, params?.arguments);
                    if (result === null) return rpcError(id, -32602, `Unknown tool: ${name}`);
                    return this.#reply(id, result);
                }
                default:
                    if (method.startsWith('notifications/')) return null;
                    return isNotification ? null : rpcError(id, -32601, `Method not found: ${method}`);
            }
        } catch (e) {
            this.log(`internal error handling ${method}: ${e?.message ?? e}`);
            return isNotification ? null : rpcError(id, -32603, 'Internal error in the bridge');
        }
    }

    #reply(id, result) {
        return id === undefined || id === null ? null : { jsonrpc: '2.0', id, result };
    }

    #write(obj) {
        this.output.write(JSON.stringify(obj) + '\n');
        return obj;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  CLI
// ─────────────────────────────────────────────────────────────────────────────

const stderr = (s) => process.stderr.write(`[${SERVER_NAME}] ${s}\n`);

function usage() {
    return [
        `${SERVER_NAME} ${VERSION} — BNM Lab remote support bridge (stdio MCP server)`,
        '',
        '  node bnmlab-remote.mjs                 run as an MCP server on stdin/stdout',
        '  node bnmlab-remote.mjs keygen [path]   create a support key (0600) and print its public key',
        '  node bnmlab-remote.mjs pubkey [path]   print the public key of an existing support key',
        '',
        `  BNM_RELAY_URL         relay base URL (default ${DEFAULT_RELAY_URL})`,
        '  BNM_SUPPORT_TOKEN     the relay\'s engineer token',
        `  BNM_SUPPORT_KEY_FILE  Ed25519 private key, PEM PKCS#8 (default ${DEFAULT_KEY_FILE})`,
    ].join('\n');
}

export async function main(argv = process.argv.slice(2), env = process.env) {
    const [cmd, ...rest] = argv;
    if (cmd === '--help' || cmd === '-h' || cmd === 'help') {
        process.stdout.write(usage() + '\n');
        return 0;
    }
    if (cmd === 'keygen') {
        const force = rest.includes('--force');
        const file = path.resolve(rest.find((a) => !a.startsWith('--')) ?? env.BNM_SUPPORT_KEY_FILE ?? DEFAULT_KEY_FILE);
        try {
            const { pubFile, pub } = keygen(file, { force });
            process.stdout.write(
                [
                    `Support key written to ${file} (mode 0600). Keep it private; never commit it.`,
                    `Public key (SPKI, base64) also saved to ${pubFile}.`,
                    '',
                    'Paste this into composeApp/src/commonMain/kotlin/com/bnm/lab/remote/RemoteSupportKeys.kt:',
                    '',
                    `    const val SUPPORT_PUBLIC_KEY_SPKI_B64 = "${pub}"`,
                    '',
                ].join('\n'),
            );
            return 0;
        } catch (e) {
            stderr(e.message);
            return 1;
        }
    }
    if (cmd === 'pubkey') {
        const file = path.resolve(rest[0] ?? env.BNM_SUPPORT_KEY_FILE ?? DEFAULT_KEY_FILE);
        try {
            process.stdout.write(spkiBase64(loadPrivateKey(file)) + '\n');
            return 0;
        } catch (e) {
            stderr(e.message);
            return 1;
        }
    }
    if (cmd !== undefined) {
        stderr(`Unknown command "${cmd}".\n${usage()}`);
        return 2;
    }

    const link = new LabLink({
        relayUrl: env.BNM_RELAY_URL || DEFAULT_RELAY_URL,
        token: env.BNM_SUPPORT_TOKEN,
        keyFile: env.BNM_SUPPORT_KEY_FILE || DEFAULT_KEY_FILE,
        log: stderr,
    });
    const server = new BridgeServer({ link, log: stderr }).start();
    const stop = () => {
        link.disconnect('bridge stopping');
        process.exit(0);
    };
    process.on('SIGINT', stop);
    process.on('SIGTERM', stop);
    await server.closed;
    return 0;
}

const invokedDirectly = (() => {
    try {
        return process.argv[1] && fs.realpathSync(process.argv[1]) === fs.realpathSync(__filename);
    } catch {
        return false;
    }
})();

if (invokedDirectly) {
    main().then((code) => process.exit(code), (e) => {
        stderr(`fatal: ${e?.message ?? e}`);
        process.exit(1);
    });
}
