package com.bnm.lab

import com.bnm.lab.api.Constants
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The relay host the app dials must be a host the Worker actually answers on.
 *
 * `Constants.REMOTE_RELAY_URL` is compiled into every installer and a shipped
 * build cannot be talked out of it — there is no setting, no server-side
 * override. `wrangler deploy` publishes to `<name>.<subdomain>.workers.dev`
 * unless a route says otherwise, so without the `custom_domain` binding in
 * `relay/wrangler.toml` the Worker would be live, the deploy green, and every
 * owner who pressed Start in Help > Remote support would get an
 * UnknownHostException and a session that ends "could not connect".
 *
 * Nothing else pairs these two files, so this test does.
 */
class RelayHostTest {

    private fun repoFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        fail("not found from ${File("").absolutePath}: $rel")
    }

    /** The host out of `wss://host[/path]`. */
    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':')

    /** Each `[[routes]]` entry's body, up to the next table header. */
    private fun routeBlocks(toml: String): List<String> =
        Regex("""(?m)^\[\[routes]]\s*$""").findAll(toml).map { m ->
            val rest = toml.substring(m.range.last + 1)
            Regex("""(?m)^\s*\[""").find(rest)?.let { rest.substring(0, it.range.first) } ?: rest
        }.toList()

    private fun valueOf(block: String, key: String): String? =
        Regex("""(?m)^\s*$key\s*=\s*"?([^"\n]+)"?\s*$""").find(block)?.groupValues?.get(1)?.trim()

    @Test
    fun `wrangler binds the very host the app dials`() {
        val toml = repoFile("relay/wrangler.toml").readText()
        val appHost = hostOf(Constants.REMOTE_RELAY_URL)
        val blocks = routeBlocks(toml)

        val route = blocks.firstOrNull { valueOf(it, "pattern") == appHost }
        assertTrue(
            route != null,
            "relay/wrangler.toml declares no route for $appHost (patterns: " +
                "${blocks.map { valueOf(it, "pattern") }}). Without one, `wrangler deploy` publishes to " +
                "bnm-lab-relay.<subdomain>.workers.dev and every lab's remote-support session fails to connect.",
        )
        // custom_domain = true is what makes Cloudflare create and own the DNS
        // record for a bare hostname; a plain route pattern needs the record to
        // exist already, which is exactly the step that gets forgotten.
        assertEquals(
            "true", valueOf(route!!, "custom_domain"),
            "the $appHost route must be `custom_domain = true`",
        )
    }

    @Test
    fun `the app dials a secure websocket, never ws in a shipped build`() {
        assertTrue(
            Constants.REMOTE_RELAY_URL.startsWith("wss://"),
            "a support session carries licence details and screen state: ws:// would put them in clear text",
        )
        assertEquals("lab-relay.bnmapp.com", hostOf(Constants.REMOTE_RELAY_URL))
    }
}
