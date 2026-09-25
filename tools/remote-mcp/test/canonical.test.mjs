// Canonical JSON (contract §2.4) — the shared fixture `canonical-vectors.json`
// is what the Kotlin CanonicalJsonTest loads too: same input → same string → same hash.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { canonicalize, sha256Hex } from '../bnmlab-remote.mjs';

// The file is an object (`_comment`, `vectors`) so it can explain itself; both loaders read `.vectors`.
const vectors = JSON.parse(fs.readFileSync(new URL('./canonical-vectors.json', import.meta.url), 'utf8')).vectors;

test('fixture has at least 8 vectors in the agreed shape', () => {
    assert.ok(vectors.length >= 8, `only ${vectors.length} vectors`);
    for (const v of vectors) {
        assert.equal(typeof v.name, 'string');
        assert.ok('input' in v);
        assert.equal(typeof v.canonical, 'string');
        assert.match(v.sha256, /^[0-9a-f]{64}$/);
    }
    assert.equal(new Set(vectors.map((v) => v.name)).size, vectors.length, 'vector names must be unique');
});

for (const v of vectors) {
    test(`vector ${v.name}: canonical string and sha256 match`, () => {
        assert.equal(canonicalize(v.input), v.canonical);
        // Hash checked with node:crypto directly, not only through the bridge's helper.
        assert.equal(crypto.createHash('sha256').update(v.canonical, 'utf8').digest('hex'), v.sha256);
        assert.equal(sha256Hex(v.canonical), v.sha256);
        assert.deepEqual(JSON.parse(v.canonical), v.input, 'canonical form must still parse to the same value');
    });
}

test('keys sort by UTF-16 code unit, so "10" comes before "9" (never numeric order)', () => {
    assert.equal(canonicalize({ 9: 'nine', 10: 'ten', b: 1, B: 2, _: 3 }), '{"10":"ten","9":"nine","B":2,"_":3,"b":1}');
});

test('the same tree in different insertion orders canonicalises identically', () => {
    const a = { x: { m: [1, { q: 1, p: 2 }], n: 2 }, w: null };
    const b = { w: null, x: { n: 2, m: [1, { p: 2, q: 1 }] } };
    assert.equal(canonicalize(a), canonicalize(b));
});

test('mirrors JSON.stringify: undefined members dropped, NaN → null, array holes → null, toJSON honoured', () => {
    assert.equal(canonicalize({ a: undefined, b: 1, c: () => 1 }), '{"b":1}');
    assert.equal(canonicalize({ n: NaN, i: Infinity }), '{"i":null,"n":null}');
    assert.equal(canonicalize([undefined, 1]), '[null,1]');
    const d = new Date(Date.UTC(2026, 8, 25, 10, 0, 0));
    assert.equal(canonicalize({ at: d }), '{"at":"2026-09-25T10:00:00.000Z"}');
});

test('numbers appear exactly as JSON.stringify sends them on the wire', () => {
    const params = { a: 1e21, b: 1.5e-7, c: 100000000000000000000, d: 0.1 };
    // The body the bridge sends is JSON.stringify(params); the lab canonicalises the parsed body.
    const wire = JSON.parse(JSON.stringify(params));
    assert.equal(canonicalize(wire), canonicalize(params));
    assert.equal(canonicalize(params), '{"a":1e+21,"b":1.5e-7,"c":100000000000000000000,"d":0.1}');
});

test('scalars and empty containers', () => {
    assert.equal(canonicalize(null), 'null');
    assert.equal(canonicalize(true), 'true');
    assert.equal(canonicalize(7), '7');
    assert.equal(canonicalize('s'), '"s"');
    assert.equal(canonicalize([]), '[]');
    assert.equal(canonicalize({}), '{}');
});
