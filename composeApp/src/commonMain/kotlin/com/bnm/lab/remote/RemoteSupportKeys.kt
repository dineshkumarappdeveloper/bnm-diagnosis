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
     * The PRODUCTION support key, generated 2026-09-26. Its private half lives
     * ONLY at `~/.config/bnmlab-remote/support.key` (0600) on the support
     * engineer's machine and is in no repository, no CI secret and no backup
     * that leaves that machine — lose it and no engineer can open a session
     * until a new key ships; leak it and every lab on this build is reachable.
     *
     * To rotate: `bnmlab-remote.mjs keygen --force`, paste the new SPKI here,
     * ship the build, and only THEN retire the old private key — a lab keeps
     * trusting the key its installed build carries.
     */
    const val SUPPORT_PUBLIC_KEY_SPKI_B64 = "MCowBQYDK2VwAyEAmvvVrnkRpRG+YtdOzQkxB55Yf3uV3qSuCRRLOlO8vvE="

    /** The committed dev key (tools/remote-mcp/test/dev-support.pub), kept to detect a build that still trusts it. */
    const val DEV_PUBLIC_KEY_SPKI_B64 = "MCowBQYDK2VwAyEAKRprPO/KtAKrrHADx75Xi0ZZzgWaSU7fTaI0wrq9aMA="

    /** True while the app still trusts the committed dev key — surfaced as `support_key: dev` in `lab.overview`. */
    val isDevKey: Boolean get() = SUPPORT_PUBLIC_KEY_SPKI_B64 == DEV_PUBLIC_KEY_SPKI_B64
}
