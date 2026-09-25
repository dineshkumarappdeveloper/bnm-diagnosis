package com.bnm.lab.remote

/**
 * The BNM support public key. Every `tools/call` an engineer sends during a
 * support session is Ed25519-signed with the matching PRIVATE key (kept in the
 * engineer's `~/.config/bnmlab-remote/support.key`, never in this repo); the
 * app verifies against this one before it runs anything. A relay compromise or
 * a stolen session code therefore still cannot drive the app.
 *
 * Format: X.509 SubjectPublicKeyInfo DER, base64 — what `node
 * tools/remote-mcp/bnmlab-remote.mjs keygen` prints and what Java's
 * `X509EncodedKeySpec` reads (`remote/RemoteProtocol.kt` on desktop).
 *
 * Key rotation = paste the new SPKI here and ship a build; an old build keeps
 * trusting the old key until updated, so rotate BEFORE revoking the old key
 * from the engineers' machines.
 */
object RemoteSupportKeys {
    /**
     * ┌──────────────────────────────────────────────────────────────────────┐
     * │  REPLACE BEFORE RELEASE.                                              │
     * │  This is the DEV keypair from tools/remote-mcp/test/dev-support.*,    │
     * │  whose PRIVATE half is committed in this repository so the bridge and │
     * │  app tests can sign. A shipped build carrying it would let anyone     │
     * │  with the repo drive any lab that reads them a session code.          │
     * │  Generate the real key with `bnmlab-remote.mjs keygen`, paste its     │
     * │  SPKI here, and `isDevKey` turns false (lab.overview reports it).     │
     * └──────────────────────────────────────────────────────────────────────┘
     */
    const val SUPPORT_PUBLIC_KEY_SPKI_B64 = "MCowBQYDK2VwAyEAu+v3Zk5RgmE3hzUUv9JtFVbakdYD86+3ZqpFnXi3+S4="

    /** The committed dev key (tools/remote-mcp/test/dev-support.pub), kept to detect a build that still trusts it. */
    const val DEV_PUBLIC_KEY_SPKI_B64 = "MCowBQYDK2VwAyEAu+v3Zk5RgmE3hzUUv9JtFVbakdYD86+3ZqpFnXi3+S4="

    /** True while the app still trusts the committed dev key — surfaced as `support_key: dev` in `lab.overview`. */
    val isDevKey: Boolean get() = SUPPORT_PUBLIC_KEY_SPKI_B64 == DEV_PUBLIC_KEY_SPKI_B64
}
