// keygen / pubkey / key loading — file modes, refusal to overwrite, key-type checks.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { keygen, loadPrivateKey, spkiBase64, pubFileFor, BridgeError } from '../bnmlab-remote.mjs';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'bnmlab-remote-'));
const mode = (f) => fs.statSync(f).mode & 0o777;

test('keygen writes a PKCS#8 Ed25519 key with mode 0600 plus its SPKI beside it, creating the directory', () => {
    const dir = tmp();
    const file = path.join(dir, 'nested', 'deeper', 'support.key');
    const out = keygen(file);
    assert.equal(out.file, file);
    assert.equal(out.pubFile, path.join(dir, 'nested', 'deeper', 'support.pub'));
    assert.equal(mode(file), 0o600);
    assert.equal(mode(path.dirname(file)), 0o700);
    const pem = fs.readFileSync(file, 'utf8');
    assert.match(pem, /^-----BEGIN PRIVATE KEY-----\n/);
    assert.match(pem, /-----END PRIVATE KEY-----\n$/);
    assert.equal(fs.readFileSync(out.pubFile, 'utf8'), out.pub + '\n');
    assert.match(out.pub, /^[A-Za-z0-9+/]+=*$/);
    const der = Buffer.from(out.pub, 'base64');
    assert.equal(der.length, 44, 'Ed25519 SPKI is 44 bytes');
    assert.equal(crypto.createPublicKey({ key: der, format: 'der', type: 'spki' }).asymmetricKeyType, 'ed25519');
    assert.equal(spkiBase64(loadPrivateKey(file)), out.pub);
});

test('keygen refuses to overwrite unless --force, and --force yields a different key', () => {
    const file = path.join(tmp(), 'support.key');
    const first = keygen(file);
    assert.throws(() => keygen(file), (e) => e instanceof BridgeError && /already exists/.test(e.message) && /--force/.test(e.message));
    assert.equal(spkiBase64(loadPrivateKey(file)), first.pub, 'untouched after the refusal');
    fs.chmodSync(file, 0o644);
    const second = keygen(file, { force: true });
    assert.notEqual(second.pub, first.pub);
    assert.equal(mode(file), 0o600, 'mode is fixed even when the file pre-existed');
    assert.equal(fs.readFileSync(second.pubFile, 'utf8').trim(), second.pub);
});

test('pubFileFor swaps .key for .pub, otherwise appends', () => {
    assert.equal(pubFileFor('/a/support.key'), '/a/support.pub');
    assert.equal(pubFileFor('/a/dev-support.key'), '/a/dev-support.pub');
    assert.equal(pubFileFor('/a/support'), '/a/support.pub');
    assert.equal(pubFileFor('/a/support.pem'), '/a/support.pem.pub');
});

test('loadPrivateKey explains a missing file, a non-PEM file, and a key that is not Ed25519', () => {
    const dir = tmp();
    assert.throws(() => loadPrivateKey(path.join(dir, 'missing.key')), (e) => e instanceof BridgeError && /Support key not found at .*missing\.key/.test(e.message) && /keygen/.test(e.message));

    const garbage = path.join(dir, 'garbage.key');
    fs.writeFileSync(garbage, 'hello\n');
    assert.throws(() => loadPrivateKey(garbage), (e) => e instanceof BridgeError && /not a readable PEM private key/.test(e.message));

    const ec = path.join(dir, 'ec.key');
    fs.writeFileSync(ec, crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey.export({ type: 'pkcs8', format: 'pem' }));
    assert.throws(() => loadPrivateKey(ec), (e) => e instanceof BridgeError && /is ec, not Ed25519/.test(e.message));

    const rsa = path.join(dir, 'rsa.key');
    fs.writeFileSync(rsa, crypto.generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' }));
    assert.throws(() => loadPrivateKey(rsa), /is rsa, not Ed25519/);
});

test('the committed dev key pair loads and matches (it is the key the app embeds on this branch)', () => {
    const key = loadPrivateKey(new URL('./dev-support.key', import.meta.url).pathname);
    assert.equal(key.asymmetricKeyType, 'ed25519');
    assert.equal(spkiBase64(key), fs.readFileSync(new URL('./dev-support.pub', import.meta.url), 'utf8').trim());
});
