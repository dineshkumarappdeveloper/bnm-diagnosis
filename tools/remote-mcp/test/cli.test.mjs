// The real executable over real stdio: the smoke an engineer can repeat by hand
// (echo an initialize line into the server), plus the keygen/pubkey subcommands.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const SCRIPT = new URL('../bnmlab-remote.mjs', import.meta.url).pathname;
const DEV_PUB = fs.readFileSync(new URL('./dev-support.pub', import.meta.url), 'utf8').trim();

function run(args, { input = '', env = {} } = {}) {
    return new Promise((resolve) => {
        const child = spawn(process.execPath, [SCRIPT, ...args], {
            env: { PATH: process.env.PATH, HOME: process.env.HOME, ...env },
            stdio: ['pipe', 'pipe', 'pipe'],
        });
        let stdout = '';
        let stderr = '';
        child.stdout.setEncoding('utf8').on('data', (d) => (stdout += d));
        child.stderr.setEncoding('utf8').on('data', (d) => (stderr += d));
        child.on('close', (code) => resolve({ code, stdout, stderr }));
        child.stdin.end(input);
    });
}

const line = (id, method, params) => JSON.stringify({ jsonrpc: '2.0', id, method, ...(params ? { params } : {}) }) + '\n';

test('stdio smoke: initialize, initialized, ping, tools/list, a tool call — answered as JSON lines, exit 0 on EOF', async () => {
    const input =
        line(1, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'smoke', version: '0' } }) +
        JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n' +
        line(2, 'ping') +
        line(3, 'tools/list') +
        line(4, 'tools/call', { name: 'lab_status', arguments: {} }) +
        line(5, 'tools/call', { name: 'lab_call', arguments: { tool: 'lab.overview' } });
    const { code, stdout, stderr } = await run([], { input, env: { BNM_RELAY_URL: 'wss://relay.invalid', BNM_SUPPORT_TOKEN: 'x' } });
    assert.equal(code, 0, stderr);
    const out = stdout.split('\n').filter(Boolean).map((l) => JSON.parse(l));
    assert.equal(out.length, 5, stdout);
    const byId = Object.fromEntries(out.map((o) => [o.id, o]));
    assert.equal(byId[1].result.protocolVersion, '2025-06-18');
    assert.equal(byId[1].result.serverInfo.name, 'bnmlab-remote');
    assert.deepEqual(byId[2].result, {});
    assert.equal(byId[3].result.tools.length, 5);
    assert.equal(JSON.parse(byId[4].result.content[0].text).connection, 'disconnected');
    assert.equal(byId[5].result.isError, true);
    assert.match(byId[5].result.content[0].text, /Not connected to a lab/);
    for (const l of stderr.split('\n').filter(Boolean)) assert.match(l, /^\[bnmlab-remote\] /, 'stderr lines are tagged');
});

test('stdio: a broken line gets −32700 and the server keeps going', async () => {
    const { code, stdout } = await run([], { input: 'garbage\n' + line(1, 'ping') });
    assert.equal(code, 0);
    const out = stdout.split('\n').filter(Boolean).map((l) => JSON.parse(l));
    assert.equal(out[0].error.code, -32700);
    assert.equal(out[0].id, null);
    assert.deepEqual(out[1], { jsonrpc: '2.0', id: 1, result: {} });
});

test('keygen subcommand writes the key and prints the Kotlin line; pubkey prints the same SPKI', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bnmlab-remote-cli-'));
    const file = path.join(dir, 'support.key');
    const gen = await run(['keygen', file]);
    assert.equal(gen.code, 0, gen.stderr);
    assert.match(gen.stdout, /Support key written to .*support\.key \(mode 0600\)/);
    const m = gen.stdout.match(/const val SUPPORT_PUBLIC_KEY_SPKI_B64 = "([A-Za-z0-9+/=]+)"/);
    assert.ok(m, gen.stdout);
    assert.equal(fs.readFileSync(path.join(dir, 'support.pub'), 'utf8').trim(), m[1]);
    assert.equal(fs.statSync(file).mode & 0o777, 0o600);

    const pub = await run(['pubkey', file]);
    assert.equal(pub.code, 0);
    assert.equal(pub.stdout.trim(), m[1]);

    const again = await run(['keygen', file]);
    assert.equal(again.code, 1);
    assert.match(again.stderr, /already exists/);
    assert.equal(again.stdout, '');
});

test('keygen honours BNM_SUPPORT_KEY_FILE when no path is given', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bnmlab-remote-cli-'));
    const file = path.join(dir, 'from-env.key');
    const gen = await run(['keygen'], { env: { BNM_SUPPORT_KEY_FILE: file } });
    assert.equal(gen.code, 0, gen.stderr);
    assert.ok(fs.existsSync(file));
    const pub = await run(['pubkey'], { env: { BNM_SUPPORT_KEY_FILE: file } });
    assert.equal(pub.stdout.trim(), fs.readFileSync(path.join(dir, 'from-env.pub'), 'utf8').trim());
});

test('pubkey of the committed dev key equals dev-support.pub', async () => {
    const pub = await run(['pubkey', new URL('./dev-support.key', import.meta.url).pathname]);
    assert.equal(pub.code, 0);
    assert.equal(pub.stdout.trim(), DEV_PUB);
});

test('help and unknown commands', async () => {
    const help = await run(['--help']);
    assert.equal(help.code, 0);
    assert.match(help.stdout, /keygen/);
    assert.match(help.stdout, /BNM_SUPPORT_TOKEN/);
    const bad = await run(['frobnicate']);
    assert.equal(bad.code, 2);
    assert.match(bad.stderr, /Unknown command "frobnicate"/);
});
