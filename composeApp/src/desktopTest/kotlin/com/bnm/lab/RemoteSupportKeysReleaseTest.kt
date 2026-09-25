package com.bnm.lab

import com.bnm.lab.remote.RemoteSupportKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The release gate for the remote-support trust root. The private half of
 * [RemoteSupportKeys.DEV_PUBLIC_KEY_SPKI_B64] is committed in this repository
 * (tools/remote-mcp/test/dev-support.key) so the bridge and app tests can
 * sign; a shipped build that still trusts it would let anyone with the repo
 * and a session code drive any lab. So a VERSIONED build — one cut with
 * `-PappVersion`, which is what CI and the release workflow pass — fails this
 * test until the real key is pasted in. A developer's local build (no
 * `-PappVersion`), a `-dev` version, or an explicit
 * `-Dbnm.allowDevSupportKey=true` (CI on a branch, the E2E) is let through.
 *
 * The packaging tasks carry the same gate (`checkSupportKeyNotDev` in
 * composeApp/build.gradle.kts), because release.yml packages without running
 * tests — this one is the developer-facing half of the pair.
 */
class RemoteSupportKeysReleaseTest {

    @Test
    fun `a versioned build must not trust the committed dev support key`() {
        val allowed = System.getProperty("bnm.allowDevSupportKey") == "true"
        val development = !BuildInfo.VERSIONED_BUILD || BuildInfo.VERSION.endsWith("-dev")
        if (allowed || development) {
            println("RemoteSupportKeysReleaseTest: dev support key allowed here (version ${BuildInfo.VERSION}, " +
                "versioned=${BuildInfo.VERSIONED_BUILD}, allowDevSupportKey=$allowed)")
            return
        }
        assertFalse(
            RemoteSupportKeys.isDevKey,
            "Build ${BuildInfo.VERSION} still trusts the committed DEV support key. Run " +
                "`node tools/remote-mcp/bnmlab-remote.mjs keygen`, paste the SPKI it prints into " +
                "RemoteSupportKeys.SUPPORT_PUBLIC_KEY_SPKI_B64, and cut the release again.",
        )
    }

    @Test
    fun `isDevKey is exactly 'the embedded key equals the committed dev key'`() {
        assertEquals(
            RemoteSupportKeys.SUPPORT_PUBLIC_KEY_SPKI_B64 == RemoteSupportKeys.DEV_PUBLIC_KEY_SPKI_B64,
            RemoteSupportKeys.isDevKey,
        )
    }
}
