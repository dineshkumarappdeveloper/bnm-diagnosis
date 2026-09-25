// Signing (contract §2.4): payload layout, Ed25519 over it, tamper detection,
// and the deterministic vectors the Kotlin RemoteRpcTest verifies with the dev key.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { signingPayload, signRequest, verifyRequest, newNonce, canonicalize, SIGNING_PREFIX, spkiBase64 } from '../bnmlab-remote.mjs';
import { payloadFor } from './fake-relay.mjs';

const here = (f) => new URL(`./${f}`, import.meta.url);
const privateKey = crypto.createPrivateKey(fs.readFileSync(here('dev-support.key'), 'utf8'));
const pubB64 = fs.readFileSync(here('dev-support.pub'), 'utf8').trim();
// The public key the way the app embeds it: SPKI DER, base64 — decoded independently of the bridge.
const publicKey = crypto.createPublicKey({ key: Buffer.from(pubB64, 'base64'), format: 'der', type: 'spki' });

const fields = {
    sessionId: '4b0e6c2a-9d1f-4c3e-8a7b-2f5d6e1c9a01',
    id: 7,
    method: 'tools/call',
    tsMs: 1790000000000,
    nonce: '0123456789abcdef0123456789abcdef',
    params: { name: 'instruments.log', arguments: { limit: 50, include_raw: false } },
};

test('dev-support.pub is the SPKI of dev-support.key', () => {
    assert.equal(spkiBase64(privateKey), pubB64);
    assert.equal(publicKey.asymmetricKeyType, 'ed25519');
});

test('payload is prefix, session id, id-as-string, method, ts, nonce, canonical(params) joined by \\n', () => {
    const expected =
        'bnm-lab-support-v1\n' +
        '4b0e6c2a-9d1f-4c3e-8a7b-2f5d6e1c9a01\n' +
        '7\n' +
        'tools/call\n' +
        '1790000000000\n' +
        '0123456789abcdef0123456789abcdef\n' +
        '{"arguments":{"include_raw":false,"limit":50},"name":"instruments.log"}';
    assert.equal(SIGNING_PREFIX, 'bnm-lab-support-v1');
    assert.equal(signingPayload(fields), expected);
    assert.equal(signingPayload(fields), payloadFor(fields), 'agrees with the fake relay\'s independent builder');
    assert.equal(signingPayload({ ...fields, id: 'req-9' }).split('\n')[2], 'req-9', 'string ids pass through unchanged');
    assert.equal(signingPayload({ ...fields, params: undefined }).split('\n')[6], '{}', 'missing params canonicalise as {}');
});

test('signature verifies with the embedded-style public key via node:crypto directly', () => {
    const sig = signRequest(privateKey, fields);
    assert.match(sig, /^[A-Za-z0-9+/]+=*$/);
    assert.equal(Buffer.from(sig, 'base64').length, 64, 'Ed25519 signatures are 64 bytes');
    const ok = crypto.verify(null, Buffer.from(signingPayload(fields), 'utf8'), publicKey, Buffer.from(sig, 'base64'));
    assert.equal(ok, true);
    assert.equal(verifyRequest(publicKey, fields, sig), true);
});

test('changing any signed field breaks the signature', () => {
    const sig = signRequest(privateKey, fields);
    const tampered = [
        { ...fields, sessionId: 'c9a2f7e1-1b3d-4e5f-9a8b-7c6d5e4f3a21' },
        { ...fields, id: 8 },
        { ...fields, id: '7 ' },
        { ...fields, method: 'tools/list' },
        { ...fields, tsMs: fields.tsMs + 1 },
        { ...fields, nonce: 'ffffffffffffffffffffffffffffffff' },
        { ...fields, params: { name: 'instruments.log', arguments: { limit: 51, include_raw: false } } },
        { ...fields, params: { name: 'instruments.restart', arguments: { limit: 50, include_raw: false } } },
    ];
    for (const t of tampered) assert.equal(verifyRequest(publicKey, t, sig), false, JSON.stringify(t));
    assert.equal(verifyRequest(publicKey, fields, 'not base64!'), false);
    assert.equal(verifyRequest(publicKey, fields, ''), false);
});

test('argument key order does not change the signature (canonicalisation)', () => {
    const a = { ...fields, params: { name: 'x', arguments: { b: 1, a: [{ d: 1, c: 2 }] } } };
    const b = { ...fields, params: { arguments: { a: [{ c: 2, d: 1 }], b: 1 }, name: 'x' } };
    assert.equal(signRequest(privateKey, a), signRequest(privateKey, b));
});

test('a different key does not verify', () => {
    const other = crypto.generateKeyPairSync('ed25519');
    const sig = signRequest(other.privateKey, fields);
    assert.equal(verifyRequest(publicKey, fields, sig), false);
    assert.equal(verifyRequest(other.publicKey, fields, sig), true);
});

test('signing-vectors.json: fixed payloads and signatures verify with the dev public key', () => {
    const fixture = JSON.parse(fs.readFileSync(here('signing-vectors.json'), 'utf8'));
    assert.equal(fixture.public_key_spki_b64, pubB64);
    assert.ok(fixture.vectors.length >= 3);
    for (const v of fixture.vectors) {
        const f = { sessionId: v.session_id, id: v.id, method: v.method, tsMs: v.ts_ms, nonce: v.nonce, params: v.params };
        assert.equal(signingPayload(f), v.payload, v.name);
        assert.equal(v.payload.split('\n')[6], canonicalize(v.params), v.name);
        assert.equal(crypto.createHash('sha256').update(v.payload, 'utf8').digest('hex'), v.payload_sha256, v.name);
        assert.equal(signRequest(privateKey, f), v.sig_b64, `${v.name}: Ed25519 is deterministic, so the fixture must match`);
        assert.equal(crypto.verify(null, Buffer.from(v.payload, 'utf8'), publicKey, Buffer.from(v.sig_b64, 'base64')), true, v.name);
    }
});

test('nonces are 32 hex characters and never repeat', () => {
    const seen = new Set();
    for (let i = 0; i < 500; i++) {
        const n = newNonce();
        assert.match(n, /^[0-9a-f]{32}$/);
        assert.ok(!seen.has(n));
        seen.add(n);
    }
});
