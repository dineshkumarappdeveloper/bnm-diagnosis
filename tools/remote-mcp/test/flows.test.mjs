// Join / forward / refusal flows against the in-process fake relay + lab.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { LabLink, BridgeServer, runTool } from '../bnmlab-remote.mjs';
import { FakeRelay, until } from './fake-relay.mjs';

const here = (f) => new URL(`./${f}`, import.meta.url);
const KEY_FILE = new URL('./dev-support.key', import.meta.url).pathname;
const publicKey = crypto.createPublicKey({ key: Buffer.from(fs.readFileSync(here('dev-support.pub'), 'utf8').trim(), 'base64'), format: 'der', type: 'spki' });
const RELAY = 'wss://relay.test';

function setup(relayOpts = {}, linkOpts = {}) {
    const relay = new FakeRelay({ publicKey, ...relayOpts });
    const link = new LabLink({ relayUrl: RELAY, token: relay.token, keyFile: KEY_FILE, wsFactory: relay.factory, requestMs: 500, connectMs: 500, ...linkOpts });
    return { relay, link };
}
const textOf = (res) => res.content.map((c) => c.text ?? '').join('\n');

test('lab_connect: joins with the normalised code over /v1/support with the bearer token, then initialize + initialized + session.info', async () => {
    const { relay, link } = setup({ code: 'Q7K30X1P' });
    const res = await runTool(link, 'lab_connect', { code: ' q7k3-OxLp ' }); // lowercase, spaces, dash, O for 0, L for 1
    assert.equal(res.isError, false, textOf(res));
    assert.equal(relay.lastUrl, `${RELAY}/v1/support`);
    assert.deepEqual(relay.lastHeaders, { Authorization: 'Bearer tok-test' });
    assert.deepEqual(JSON.parse(relay.sockets[0].sent[0]), { t: 'join', code: 'Q7K30X1P' });
    assert.deepEqual(relay.received.map((m) => m.method), ['initialize', 'notifications/initialized', 'tools/call']);
    assert.equal(relay.received[0].params.protocolVersion, '2025-06-18');
    assert.equal(relay.received[0].params.clientInfo.name, 'bnmlab-remote');
    assert.equal(relay.initialized, true);
    assert.deepEqual(relay.calls.map((c) => c.name), ['session.info']);

    const t = textOf(res);
    assert.match(t, /^Connected to "Sunrise Diagnostics" \(BNM Lab 1\.4\.0\)\./);
    assert.match(t, /Session ends in 59 min\./);
    assert.match(t, /analyzer data: no, records: no, screen: yes/);
    assert.match(t, /Run lab_tools next/);
    assert.ok(!t.includes('Note:'), 'no note when everything went well');
    const summary = JSON.parse(t.slice(t.indexOf('{')));
    assert.equal(summary.connection, 'connected');
    assert.equal(summary.peer, 'lab connected');
    assert.equal(summary.session, '4b0e6c2a');
    assert.equal(summary.actions, 1, 'session.info counted, as the lab counts it');
    assert.equal(link.sessionId, relay.sessionId);
});

test('lab_connect: the code must look like a session code before anything is dialled', async () => {
    const { relay, link } = setup();
    for (const code of ['', 'ABC', 'Q7K3-MX9', 'Q7K3-MX9PP', 'Q7K3-MX9U', 12345678]) {
        const res = await runTool(link, 'lab_connect', { code });
        assert.equal(res.isError, true);
        assert.match(textOf(res), /8 letters and digits|needs the session code/);
    }
    assert.equal(relay.sockets.length, 0);
});

test('lab_connect: missing token or bad relay URL fail before dialling', async () => {
    const { relay, link } = setup({}, { token: '' });
    let res = await runTool(link, 'lab_connect', { code: 'Q7K3MX9P' });
    assert.equal(res.isError, true);
    assert.match(textOf(res), /BNM_SUPPORT_TOKEN is not set/);
    const { link: link2 } = setup({}, { relayUrl: 'https://relay.test' });
    res = await runTool(link2, 'lab_connect', { code: 'Q7K3MX9P' });
    assert.match(textOf(res), /must start with wss:\/\//);
    assert.equal(relay.sockets.length, 0);
});

test('lab_connect: a wrong token is refused at the upgrade and explained', async () => {
    const { link } = setup({}, { token: 'wrong' });
    const res = await runTool(link, 'lab_connect', { code: 'Q7K3MX9P' });
    assert.equal(res.isError, true);
    assert.match(textOf(res), /Check BNM_RELAY_URL and BNM_SUPPORT_TOKEN/);
    assert.equal(link.state, 'disconnected');
});

test('lab_connect: wrong code → no_session in plain words; state stays disconnected', async () => {
    const { link } = setup();
    const res = await runTool(link, 'lab_connect', { code: 'AAAA-AAAA' });
    assert.equal(res.isError, true);
    assert.match(textOf(res), /No support session with that code/);
    assert.equal(link.state, 'disconnected');
    const status = JSON.parse(textOf(await runTool(link, 'lab_status', {})));
    assert.equal(status.connection, 'disconnected');
    assert.match(status.last_error, /No support session/);
});

test('lab_connect: a second engineer on the same session is told it is busy', async () => {
    const { relay, link } = setup();
    assert.equal((await runTool(link, 'lab_connect', { code: relay.code })).isError, false);
    const second = new LabLink({ relayUrl: RELAY, token: relay.token, keyFile: KEY_FILE, wsFactory: relay.factory, requestMs: 500 });
    const res = await runTool(second, 'lab_connect', { code: relay.code });
    assert.equal(res.isError, true);
    assert.match(textOf(res), /Another engineer is already connected/);
    assert.equal(link.state, 'connected', 'the first connection is untouched');
});

test('lab_connect: session facts are read from _meta.bnm, bnm, or a relay join ack', async () => {
    for (const [metaShape, joinAck] of [['_meta', 'none'], ['bnm', 'none'], ['none', 'joined'], ['none', 'ready']]) {
        const { relay, link } = setup({ metaShape, joinAck });
        const res = await runTool(link, 'lab_connect', { code: relay.code });
        assert.equal(res.isError, false, `${metaShape}/${joinAck}: ${textOf(res)}`);
        assert.equal(link.sessionId, relay.sessionId, `${metaShape}/${joinAck}`);
        assert.equal(relay.calls.length, 1, `${metaShape}/${joinAck}: session.info was signed and accepted`);
        assert.match(textOf(res), /Connected to "Sunrise Diagnostics"/, `${metaShape}/${joinAck}: lab name learned from session.info`);
    }
});

test('lab_connect: when the lab never sends a session id, the connection stands but the engineer is told', async () => {
    const { relay, link } = setup({ metaShape: 'none', joinAck: 'none' });
    const res = await runTool(link, 'lab_connect', { code: relay.code });
    assert.equal(res.isError, false);
    assert.match(textOf(res), /Note: The lab did not send its session id/);
    const call = await runTool(link, 'lab_call', { tool: 'echo', args: { text: 'hi' } });
    assert.equal(call.isError, true);
    assert.match(textOf(call), /cannot sign/);
});

test('lab_tools returns the lab\'s tools/list verbatim (annotations included)', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const res = await runTool(link, 'lab_tools', {});
    assert.equal(res.isError, false);
    const t = textOf(res);
    assert.match(t, /^7 tools on the lab\./);
    const tools = JSON.parse(t.slice(t.indexOf('[')));
    assert.deepEqual(tools, relay.tools.map(({ requires, ...x }) => x));
    assert.equal(tools.find((x) => x.name === 'instruments.restart').annotations.destructiveHint, true);
});

test('lab_call: signs each call with a fresh nonce and rising id; the lab verifies and answers; text returned verbatim', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const a = await runTool(link, 'lab_call', { tool: 'echo', args: { text: 'one' } });
    const b = await runTool(link, 'lab_call', { tool: ' echo ', args: { text: 'two' } });
    assert.deepEqual(a, { content: [{ type: 'text', text: 'one' }], isError: false });
    assert.deepEqual(b, { content: [{ type: 'text', text: 'two' }], isError: false });
    const [, c1, c2] = relay.calls;
    assert.equal(c1.name, 'echo');
    assert.deepEqual(c1.arguments, { text: 'one' });
    assert.notEqual(c1.nonce, c2.nonce);
    assert.ok(c2.id > c1.id);
    assert.equal(relay.refusals.length, 0);
    const wire = relay.received.at(-1);
    assert.deepEqual(Object.keys(wire).sort(), ['bnm', 'id', 'jsonrpc', 'method', 'params']);
    assert.deepEqual(Object.keys(wire.bnm).sort(), ['nonce', 'sig', 'ts_ms']);
    assert.equal(JSON.parse(textOf(await runTool(link, 'lab_status', {}))).actions, 3);
});

test('lab_call: args default to {} and must be an object', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const ok = await runTool(link, 'lab_call', { tool: 'echo' });
    assert.equal(ok.isError, false);
    assert.deepEqual(relay.calls.at(-1).arguments, {});
    for (const bad of [{ tool: 'echo', args: [1] }, { tool: 'echo', args: 'x' }, { tool: 'echo', args: null }, { tool: '' }, { args: {} }]) {
        const res = await runTool(link, 'lab_call', bad);
        assert.equal(res.isError, true, JSON.stringify(bad));
        assert.match(textOf(res), /lab_call/);
    }
});

test('lab_call: image content passes through untouched', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const res = await runTool(link, 'lab_call', { tool: 'picture', args: {} });
    assert.equal(res.isError, false);
    assert.equal(res.content.length, 1);
    assert.equal(res.content[0].type, 'image');
    assert.equal(res.content[0].mimeType, 'image/png');
    assert.match(res.content[0].data, /^iVBORw0KGgo/);
});

test('lab_call: a policy refusal (−32001) is reported as "Refused by the lab" with the lab\'s reason', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const res = await runTool(link, 'lab_call', { tool: 'records.peek', args: {} });
    assert.equal(res.isError, true);
    assert.equal(textOf(res), 'Refused by the lab: Requires records consent');
    assert.deepEqual(relay.refusals, ['Requires records consent']);
});

test('lab_call: lab-side crash and unknown tool are surfaced with their codes', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const crash = await runTool(link, 'lab_call', { tool: 'boom' });
    assert.equal(crash.isError, true);
    assert.equal(textOf(crash), 'The lab answered with an error (-32603): Tool crashed');
    const unknown = await runTool(link, 'lab_call', { tool: 'instruments.teleport' });
    assert.equal(textOf(unknown), 'The lab answered with an error (-32602): Unknown tool: instruments.teleport');
});

test('lab_call: an unreadable key file is explained and nothing unsigned ever reaches the lab', async () => {
    const { relay, link } = setup({}, { keyFile: '/nonexistent/support.key' });
    const conn = await runTool(link, 'lab_connect', { code: relay.code });
    assert.equal(conn.isError, false, 'the connection itself succeeds');
    assert.match(textOf(conn), /Note: Connected, but the first signed call failed: Support key not found/);
    const res = await runTool(link, 'lab_call', { tool: 'echo', args: { text: 'x' } });
    assert.equal(res.isError, true);
    assert.match(textOf(res), /Support key not found at \/nonexistent\/support\.key/);
    assert.equal(relay.received.filter((m) => m.method === 'tools/call').length, 0);
});

test('lab_call: a key the lab does not know is refused as a bad signature', async () => {
    const other = crypto.generateKeyPairSync('ed25519');
    const { relay, link } = setup({ publicKey: other.publicKey });
    await runTool(link, 'lab_connect', { code: relay.code });
    const res = await runTool(link, 'lab_call', { tool: 'echo', args: { text: 'x' } });
    assert.equal(textOf(res), 'Refused by the lab: Bad signature');
    assert.ok(relay.refusals.includes('Bad signature'));
});

test('lab_call: a clock that is 10 minutes off is refused as stale', async () => {
    const { relay, link } = setup({}, { now: () => Date.now() - 10 * 60 * 1000 });
    const conn = await runTool(link, 'lab_connect', { code: relay.code });
    assert.match(textOf(conn), /Note: Connected, but the lab refused the first signed call: Request too old or too far in the future\. Check this computer's clock/);
    const res = await runTool(link, 'lab_call', { tool: 'echo' });
    assert.equal(textOf(res), 'Refused by the lab: Request too old or too far in the future');
});

test('lab_status: before, during and after; remaining time counts down on the bridge clock', async () => {
    let t = 1_800_000_000_000;
    const { relay, link } = setup({}, { now: () => t });
    let s = JSON.parse(textOf(await runTool(link, 'lab_status', {})));
    assert.equal(s.connection, 'disconnected');
    assert.equal(s.peer, 'none');
    assert.equal(s.remaining, 'unknown');
    assert.equal(s.actions, 0);

    relay.now = () => t; // keep the fake's ts window on the same clock
    await runTool(link, 'lab_connect', { code: relay.code });
    s = JSON.parse(textOf(await runTool(link, 'lab_status', {})));
    assert.equal(s.connection, 'connected');
    assert.equal(s.peer, 'lab connected');
    assert.equal(s.lab, 'Sunrise Diagnostics');
    assert.equal(s.app_version, '1.4.0');
    assert.deepEqual(s.consent, { analyzer_data: false, records: false, screen: true });
    assert.equal(s.remaining_s, 3595);
    assert.equal(s.remaining, '59 min');
    assert.equal(s.relay, RELAY);

    t += 30 * 60 * 1000;
    s = JSON.parse(textOf(await runTool(link, 'lab_status', {})));
    assert.equal(s.remaining_s, 3595 - 1800);
    assert.equal(s.remaining, '29 min');
    t += 60 * 60 * 1000;
    s = JSON.parse(textOf(await runTool(link, 'lab_status', {})));
    assert.equal(s.remaining_s, 0);
    assert.equal(s.remaining, 'ended');

    await runTool(link, 'lab_disconnect', {});
    s = JSON.parse(textOf(await runTool(link, 'lab_status', {})));
    assert.equal(s.connection, 'disconnected');
});

test('lab_disconnect closes the socket cleanly and is idempotent; a new lab_connect works after it', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const sock = relay.sockets[0];
    assert.equal(textOf(await runTool(link, 'lab_disconnect', {})), 'Disconnected from the lab.');
    assert.deepEqual(sock.closed, { code: 1000, reason: 'engineer disconnected', by: 'client' });
    assert.equal(textOf(await runTool(link, 'lab_disconnect', {})), 'Not connected to any lab.');
    await until(() => sock.readyState === 3);
    relay.support = null; // the relay notices the close
    const again = await runTool(link, 'lab_connect', { code: relay.code });
    assert.equal(again.isError, false, textOf(again));
    assert.equal(relay.sockets.length, 2);
});

test('peer notices: lab drops off → calls are held back with a reason; lab back → calls flow again', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    relay.labDisconnected();
    await until(() => link.peer === 'lab_disconnected');
    let s = JSON.parse(textOf(await runTool(link, 'lab_status', {})));
    assert.equal(s.peer, 'lab disconnected');
    const held = await runTool(link, 'lab_call', { tool: 'echo' });
    assert.equal(held.isError, true);
    assert.match(textOf(held), /lab's connection to the relay dropped/);
    relay.labReconnected();
    await until(() => link.peer === 'lab');
    assert.equal((await runTool(link, 'lab_call', { tool: 'echo', args: { text: 'back' } })).content[0].text, 'back');
});

test('peer notices: the lab-facing wording (`connected`/`disconnected`) is understood too', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    relay.support.serverSend({ t: 'peer', state: 'disconnected' });
    await until(() => link.peer === 'lab_disconnected');
    relay.labReconnected('connected');
    await until(() => link.peer === 'lab');
    assert.equal((await runTool(link, 'lab_call', { tool: 'echo', args: { text: 'back' } })).content[0].text, 'back');
});

test('no_lab holds the calls but keeps the seat: the socket stays open and the session works when the lab returns', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const sock = relay.support;
    const pending = runTool(link, 'lab_call', { tool: 'slow' });
    await until(() => relay.calls.some((c) => c.name === 'slow'));
    relay.noLab(); // the real DO answers this and leaves the socket alone
    const res = await pending;
    assert.equal(res.isError, true);
    assert.match(textOf(res), /lab's connection to the relay dropped/);
    assert.equal(sock.closed, null, 'the support seat must not be abandoned');
    assert.equal(link.state, 'connected');
    assert.equal(JSON.parse(textOf(await runTool(link, 'lab_status', {}))).peer, 'lab disconnected');
    relay.labReconnected();
    await until(() => link.peer === 'lab');
    assert.equal((await runTool(link, 'lab_call', { tool: 'echo', args: { text: 'back' } })).content[0].text, 'back');
});

test('after no_lab a fresh lab_connect is not refused as busy — the old socket was hung up', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const first = relay.support;
    relay.noLab();
    await until(() => link.peer === 'lab_disconnected');
    const again = await runTool(link, 'lab_connect', { code: relay.code });
    assert.equal(again.isError, false, textOf(again));
    assert.equal(first.closed?.by, 'client', 'the first socket was hung up, not left seated');
    assert.equal(relay.sockets.length, 2);
});

test('a fatal relay error frame hangs the socket up instead of leaving it open', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const sock = relay.support;
    sock.serverSend({ t: 'error', code: 'busy' }); // the real DO does not close on every error
    await until(() => link.state === 'disconnected');
    assert.equal(sock.closed?.by, 'client');
    assert.match(link.lastError, /Another engineer is already connected/);
});

test('the lab ending the session rejects the call in flight and leaves the bridge disconnected', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const pending = runTool(link, 'lab_call', { tool: 'slow' });
    await until(() => relay.calls.some((c) => c.name === 'slow'));
    relay.labEnd();
    const res = await pending;
    assert.equal(res.isError, true);
    assert.equal(textOf(res), 'The lab ended the support session.');
    assert.equal(link.state, 'disconnected');
    assert.match(textOf(await runTool(link, 'lab_call', { tool: 'echo' })), /Not connected to a lab/);
});

test('the relay dropping the socket rejects the call in flight with the close code', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const pending = runTool(link, 'lab_call', { tool: 'slow' });
    await until(() => relay.calls.some((c) => c.name === 'slow'));
    relay.dropSupport(1006);
    const res = await pending;
    assert.equal(res.isError, true);
    assert.match(textOf(res), /connection to the relay closed \(1006\)/);
    assert.equal(link.state, 'disconnected');
});

test('a lab that never answers times out with advice, and later calls still work', async () => {
    const { relay, link } = setup({}, { requestMs: 40 });
    await runTool(link, 'lab_connect', { code: relay.code });
    const res = await runTool(link, 'lab_call', { tool: 'slow' });
    assert.equal(res.isError, true);
    assert.match(textOf(res), /did not answer within 0\.04 s \(tools\/call slow\)/);
    assert.equal(link.state, 'connected');
    assert.equal((await runTool(link, 'lab_call', { tool: 'echo', args: { text: 'still here' } })).content[0].text, 'still here');
});

test('a ping from the lab is answered; frames that are not JSON or not for us are ignored', async () => {
    const { relay, link } = setup();
    await runTool(link, 'lab_connect', { code: relay.code });
    const sock = relay.support;
    sock.serverSend('this is not json');
    sock.serverSend({ jsonrpc: '2.0', method: 'notifications/progress', params: {} });
    sock.serverSend({ jsonrpc: '2.0', id: 999999, result: {} }); // no such pending request
    sock.serverSend({ jsonrpc: '2.0', id: 'lab-ping-1', method: 'ping' });
    await until(() => sock.sent.some((s) => s.includes('"lab-ping-1"')));
    assert.deepEqual(JSON.parse(sock.sent.at(-1)), { jsonrpc: '2.0', id: 'lab-ping-1', result: {} });
    assert.equal(link.state, 'connected');
});

test('end to end through the JSON-RPC server: initialize, connect, list, call, disconnect', async () => {
    const { relay, link } = setup();
    const server = new BridgeServer({ link, input: { on() {}, setEncoding() {} }, output: { write() {} } });
    const call = (id, name, args) => server.handleMessage({ jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: args } });
    assert.equal((await server.handleMessage({ jsonrpc: '2.0', id: 0, method: 'initialize', params: {} })).result.serverInfo.name, 'bnmlab-remote');
    const c = await call(1, 'lab_connect', { code: relay.code });
    assert.match(c.result.content[0].text, /^Connected to "Sunrise Diagnostics"/);
    assert.match((await call(2, 'lab_tools', {})).result.content[0].text, /instruments\.restart/);
    assert.deepEqual((await call(3, 'lab_call', { tool: 'echo', args: { text: 'via server' } })).result, { content: [{ type: 'text', text: 'via server' }], isError: false });
    assert.equal((await call(4, 'lab_call', { tool: 'records.peek' })).result.isError, true);
    assert.equal((await call(5, 'lab_disconnect', {})).result.content[0].text, 'Disconnected from the lab.');
});
