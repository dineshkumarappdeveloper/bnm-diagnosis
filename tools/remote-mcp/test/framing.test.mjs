// Newline-delimited JSON-RPC 2.0 over stdio: initialize/initialized/ping/tools/list/
// tools/call, error codes, and the line splitter against awkward chunking.
import test from 'node:test';
import assert from 'node:assert/strict';
import { PassThrough } from 'node:stream';
import { BridgeServer, LabLink, TOOLS, PROTOCOL_VERSION, SERVER_NAME, VERSION } from '../bnmlab-remote.mjs';

function makeServer(link = new LabLink({ token: 'tok' })) {
    const input = new PassThrough();
    const output = new PassThrough();
    let raw = '';
    output.setEncoding('utf8');
    output.on('data', (d) => (raw += d));
    const server = new BridgeServer({ link, input, output }).start();
    const lines = () => raw.split('\n').filter(Boolean);
    const waitLines = async (n) => {
        const deadline = Date.now() + 2000;
        while (lines().length < n) {
            if (Date.now() > deadline) throw new Error(`expected ${n} lines, got ${lines().length}: ${raw}`);
            await new Promise((r) => setTimeout(r, 2));
        }
        return lines().map((l) => JSON.parse(l));
    };
    return { server, input, output, lines, waitLines, raw: () => raw };
}

const req = (id, method, params) => ({ jsonrpc: '2.0', id, method, ...(params !== undefined ? { params } : {}) });

test('initialize answers protocol 2025-06-18, tools capability and serverInfo bnmlab-remote', async () => {
    const { server } = makeServer();
    const res = await server.handleMessage(req(1, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'claude-code', version: '1.0' } }));
    assert.equal(res.jsonrpc, '2.0');
    assert.equal(res.id, 1);
    assert.equal(res.result.protocolVersion, PROTOCOL_VERSION);
    assert.equal(PROTOCOL_VERSION, '2025-06-18');
    assert.deepEqual(res.result.capabilities, { tools: {} });
    assert.deepEqual(res.result.serverInfo, { name: SERVER_NAME, version: VERSION });
    assert.equal(SERVER_NAME, 'bnmlab-remote');
    assert.match(res.result.instructions, /lab_connect/);
});

test('notifications/initialized and other notifications produce no output', async () => {
    const { server } = makeServer();
    assert.equal(await server.handleMessage({ jsonrpc: '2.0', method: 'notifications/initialized' }), null);
    assert.equal(await server.handleMessage({ jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: 1 } }), null);
    assert.equal(await server.handleMessage({ jsonrpc: '2.0', method: 'ping' }), null, 'a ping without an id is a notification');
});

test('ping answers an empty object', async () => {
    const { server } = makeServer();
    assert.deepEqual(await server.handleMessage(req('p-1', 'ping')), { jsonrpc: '2.0', id: 'p-1', result: {} });
});

test('tools/list exposes exactly the five bridge tools with schemas and annotations', async () => {
    const { server } = makeServer();
    const res = await server.handleMessage(req(2, 'tools/list'));
    const tools = res.result.tools;
    assert.deepEqual(tools.map((t) => t.name), ['lab_connect', 'lab_status', 'lab_tools', 'lab_call', 'lab_disconnect']);
    assert.equal(tools, TOOLS);
    for (const t of tools) {
        assert.equal(typeof t.description, 'string');
        assert.equal(t.inputSchema.type, 'object');
        assert.equal(t.inputSchema.additionalProperties, false);
        assert.equal(typeof t.annotations.title, 'string');
        assert.equal(typeof t.annotations.readOnlyHint, 'boolean');
        assert.equal(typeof t.annotations.destructiveHint, 'boolean');
    }
    const byName = Object.fromEntries(tools.map((t) => [t.name, t]));
    assert.equal(byName.lab_call.annotations.destructiveHint, true, 'lab_call must carry destructiveHint so the client asks first');
    assert.deepEqual(byName.lab_call.inputSchema.required, ['tool']);
    assert.deepEqual(byName.lab_connect.inputSchema.required, ['code']);
    assert.equal(byName.lab_status.annotations.readOnlyHint, true);
    assert.equal(byName.lab_tools.annotations.readOnlyHint, true);
    assert.match(byName.lab_tools.description, /annotations/);
});

test('unknown method → −32601 keeping the request id (string ids too)', async () => {
    const { server } = makeServer();
    const res = await server.handleMessage(req('abc', 'resources/list'));
    assert.equal(res.id, 'abc');
    assert.equal(res.error.code, -32601);
    assert.match(res.error.message, /resources\/list/);
});

test('invalid request shapes → −32600; batches are not supported', async () => {
    const { server } = makeServer();
    assert.equal((await server.handleMessage([req(1, 'ping')])).error.code, -32600);
    assert.equal((await server.handleMessage({ jsonrpc: '2.0', id: 5 })).error.code, -32600);
    assert.equal((await server.handleMessage({ jsonrpc: '2.0', id: 5 })).id, 5);
    assert.equal((await server.handleMessage('ping')).error.code, -32600);
    assert.equal((await server.handleMessage(null)).error.code, -32600);
});

test('tools/call: missing name / unknown tool → −32602', async () => {
    const { server } = makeServer();
    assert.equal((await server.handleMessage(req(1, 'tools/call', {}))).error.code, -32602);
    const res = await server.handleMessage(req(2, 'tools/call', { name: 'lab_teleport', arguments: {} }));
    assert.equal(res.error.code, -32602);
    assert.match(res.error.message, /lab_teleport/);
});

test('tools/call while disconnected is a tool error, not a protocol error', async () => {
    const { server } = makeServer();
    for (const name of ['lab_tools', 'lab_call']) {
        const res = await server.handleMessage(req(3, 'tools/call', { name, arguments: { tool: 'x' } }));
        assert.equal(res.error, undefined);
        assert.equal(res.result.isError, true);
        assert.match(res.result.content[0].text, /lab_connect/);
    }
    const status = await server.handleMessage(req(4, 'tools/call', { name: 'lab_status', arguments: {} }));
    assert.equal(status.result.isError, false);
    assert.equal(JSON.parse(status.result.content[0].text).connection, 'disconnected');
    const dc = await server.handleMessage(req(5, 'tools/call', { name: 'lab_disconnect' }));
    assert.match(dc.result.content[0].text, /Not connected/);
});

test('stream: two messages in one chunk, one message across chunks, CRLF and blank lines', async () => {
    const { input, waitLines } = makeServer();
    input.write(JSON.stringify(req(1, 'ping')) + '\r\n' + JSON.stringify(req(2, 'ping')) + '\n\n');
    const half = JSON.stringify(req(3, 'tools/list'));
    input.write(half.slice(0, 10));
    input.write(half.slice(10) + '\n   \n');
    const out = await waitLines(3);
    assert.deepEqual(out.map((o) => o.id).sort(), [1, 2, 3]);
    assert.equal(out.find((o) => o.id === 3).result.tools.length, 5);
});

test('stream: a bad line answers −32700 with id null and does not stop the server', async () => {
    const { input, waitLines } = makeServer();
    input.write('{not json\n');
    input.write(JSON.stringify(req(9, 'ping')) + '\n');
    const out = await waitLines(2);
    const bad = out.find((o) => o.error);
    assert.equal(bad.id, null);
    assert.equal(bad.error.code, -32700);
    assert.deepEqual(out.find((o) => o.id === 9).result, {});
});

test('stream: every output line is a single JSON object (stdout is protocol-only)', async () => {
    const { input, waitLines, raw } = makeServer();
    input.write(JSON.stringify(req(1, 'initialize', {})) + '\n' + JSON.stringify(req(2, 'nope')) + '\n');
    await waitLines(2);
    for (const line of raw().split('\n').filter(Boolean)) assert.doesNotThrow(() => JSON.parse(line));
    assert.ok(!raw().includes('\n\n'));
});

test('stream: a final line without a newline is still handled; end of input disconnects the link', async () => {
    const link = new LabLink({ token: 'tok' });
    let disconnects = 0;
    link.disconnect = () => (disconnects++, false);
    const { server, input, waitLines } = makeServer(link);
    input.write(JSON.stringify(req(7, 'ping')));
    input.end();
    const out = await waitLines(1);
    assert.equal(out[0].id, 7);
    await server.closed;
    assert.equal(disconnects, 1);
});
