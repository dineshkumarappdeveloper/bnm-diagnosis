package com.bnm.lab

import com.bnm.lab.update.ShelfPage
import com.bnm.lab.update.UpdateCheck
import com.bnm.lab.update.UpdateChecker
import com.bnm.lab.update.UpdatePlatform
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The update check fails SILENTLY when it gets version ordering wrong — it just
 * says "you're on the latest version" forever — so the comparison is pinned here.
 */
class UpdateCheckerTest {

    @Test
    fun `versions compare numerically, not as strings`() {
        // The whole reason this is not a string compare: lexicographically
        // "1.10.0" < "1.9.0", which would hide every release after 1.9 and the
        // app would report itself up to date indefinitely.
        assertTrue(UpdateChecker.compareSemver("1.10.0", "1.9.0") > 0,
            "1.10.0 must be newer than 1.9.0")
        assertTrue(UpdateChecker.compareSemver("2.0.0", "1.99.99") > 0)
        assertTrue(UpdateChecker.compareSemver("1.0.10", "1.0.9") > 0)
        assertEquals(0, UpdateChecker.compareSemver("1.2.3", "1.2.3"))
        assertTrue(UpdateChecker.compareSemver("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun `a leading v is tolerated`() {
        assertEquals(Triple(1, 2, 3), UpdateChecker.parseSemver("v1.2.3"))
        assertEquals(0, UpdateChecker.compareSemver("v1.2.3", "1.2.3"))
    }

    @Test
    fun `unparseable versions sort oldest and never look like an update`() {
        assertNull(UpdateChecker.parseSemver("1.2"))
        assertNull(UpdateChecker.parseSemver("latest"))
        assertNull(UpdateChecker.parseSemver("1.2.x"))
        // A junk tag must never be treated as newer than the running build —
        // that would offer an "update" to something that does not exist.
        assertTrue(UpdateChecker.compareSemver("latest", "1.0.0") < 0)
    }

    @Test
    fun `current version is the baked build version, not a placeholder`() {
        val v = UpdateChecker.currentVersion
        assertTrue(UpdateChecker.parseSemver(v) != null,
            "BuildInfo.VERSION must be semver so the comparison works; got '$v'")
    }

    // ── the shelf walk ────────────────────────────────────────────────────────
    // The releases repo is shared and GitHub lists it by tag name descending, so
    // BNM Admin's `v*` tags always come first. Reading one fixed page lost our
    // releases once the shelf grew — silently, as "up to date".

    private val first =
        "https://api.github.com/repos/dineshkumarappdeveloper/bnmadmin-releases/releases?per_page=100"
    private fun pageUrl(n: Int) = "https://api.github.com/repositories/1/releases?per_page=100&page=$n"
    private fun link(next: Int?, last: Int) = listOfNotNull(
        next?.let { "<${pageUrl(it)}>; rel=\"next\"" },
        "<${pageUrl(last)}>; rel=\"last\"",
    ).joinToString(", ")

    private fun release(
        tag: String,
        assets: List<String> = listOf("BNMLab-windows-x64.msi", "BNMDiagnosis-windows-x64.msi", "checksums.txt"),
        prerelease: Boolean = false,
        draft: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("tag_name", tag)
        put("draft", draft)
        put("prerelease", prerelease)
        put("body", "notes for $tag")
        putJsonArray("assets") {
            assets.forEach { name ->
                addJsonObject {
                    put("name", name)
                    put("size", 1234)
                    put("browser_download_url", "https://github.com/x/releases/download/$tag/$name")
                }
            }
        }
    }

    private fun body(releases: List<JsonObject>) = buildJsonArray { releases.forEach { add(it) } }.toString()

    /** BNM Admin filler — what crowds our releases off the first page. */
    private fun adminReleases(count: Int, from: Int = 0) =
        (from until from + count).map { release("v1.$it.0", assets = listOf("BNMAdmin-windows-x64.msi")) }

    /** Serves [pages] by URL and records every request, failing on any URL it was not given. */
    private class FakeShelf(private val pages: Map<String, ShelfPage>) {
        val requested = mutableListOf<String>()
        suspend fun fetch(url: String): ShelfPage {
            requested += url
            return pages[url] ?: fail("unexpected request: $url")
        }
    }

    private fun walk(shelf: FakeShelf, running: String = "1.2.0", platform: UpdatePlatform = UpdatePlatform.WINDOWS) =
        runBlocking { UpdateChecker.checkShelf(platform, running, shelf::fetch) }

    @Test
    fun `a lab release pushed past the first page is still found`() {
        val shelf = FakeShelf(mapOf(
            first to ShelfPage(200, body(adminReleases(100)), link(next = 2, last = 2)),
            pageUrl(2) to ShelfPage(200, body(adminReleases(5, from = 100) + release("lab-v1.3.0") + release("lab-latest")), link(null, 2)),
        ))
        val result = walk(shelf)
        val available = assertIs<UpdateCheck.Available>(result)
        assertEquals("1.3.0", available.release.version)
        assertEquals(listOf(first, pageUrl(2)), shelf.requested,
            "the second request must be exactly the Link header's rel=next URL")
    }

    @Test
    fun `the walk stops at the first page holding a lab release`() {
        // Page 2 is deliberately absent: asking for it is a wasted request
        // against the 60-per-hour unauthenticated budget.
        val shelf = FakeShelf(mapOf(
            first to ShelfPage(200, body(adminReleases(13) + release("lab-v1.3.0") + release("lab-v1.2.0")), link(next = 2, last = 3)),
        ))
        assertEquals("1.3.0", assertIs<UpdateCheck.Available>(walk(shelf)).release.version)
        assertEquals(1, shelf.requested.size)
    }

    @Test
    fun `the page cap ends the walk as a failure, never as up to date`() {
        val pages = (1..UpdateChecker.MAX_PAGES + 2).associate { n ->
            (if (n == 1) first else pageUrl(n)) to
                ShelfPage(200, body(adminReleases(100, from = n * 100)), link(next = n + 1, last = 99))
        }
        val shelf = FakeShelf(pages)
        assertIs<UpdateCheck.Failed>(walk(shelf))
        assertEquals(UpdateChecker.MAX_PAGES, shelf.requested.size)
    }

    @Test
    fun `a shelf read to its end with no lab release is up to date`() {
        val shelf = FakeShelf(mapOf(
            first to ShelfPage(200, body(adminReleases(100)), link(next = 2, last = 2)),
            pageUrl(2) to ShelfPage(200, body(listOf(release("billing-v9.0.0"), release("lab-latest"))), null),
        ))
        assertIs<UpdateCheck.UpToDate>(walk(shelf))
        assertEquals(2, shelf.requested.size)
    }

    @Test
    fun `rate limiting on a later page is reported, not read as up to date`() {
        for (status in listOf(403, 429)) {
            val shelf = FakeShelf(mapOf(
                first to ShelfPage(200, body(adminReleases(100)), link(next = 2, last = 2)),
                pageUrl(2) to ShelfPage(status, "", null),
            ))
            val failed = assertIs<UpdateCheck.Failed>(walk(shelf))
            assertTrue("rate-limiting" in failed.message, "HTTP $status: ${failed.message}")
        }
    }

    @Test
    fun `newest lab release wins, ignoring other products, prereleases, drafts and the rolling tag`() {
        val shelf = FakeShelf(mapOf(
            first to ShelfPage(200, body(listOf(
                release("v9.9.9"),
                release("lab-v2.0.0", prerelease = true),
                release("lab-v1.10.0", draft = true),
                release("lab-v1.9.0"),
                release("lab-v1.4.0"),
                release("lab-latest"),
                release("diagnosis-v1.9.0"),
                release("billing-v5.0.0"),
            )), null),
        ))
        val available = assertIs<UpdateCheck.Available>(walk(shelf))
        assertEquals("1.9.0", available.release.version)
        assertTrue(available.release.checksumsUrl.orEmpty().endsWith("/checksums.txt"))
    }

    @Test
    fun `newest equal to the running build is up to date`() {
        val shelf = FakeShelf(mapOf(first to ShelfPage(200, body(listOf(release("lab-v1.2.0"))), null)))
        assertIs<UpdateCheck.UpToDate>(walk(shelf, running = "1.2.0"))
    }

    @Test
    fun `COMPAT SHIM - a pre-rename shelf with only diagnosis tags and assets still updates`() {
        // BNMLab/CLAUDE.md shim #2: TAG_PREFIXES keeps `diagnosis-v`, and
        // assetNames falls back to the BNMDiagnosis-* filename.
        val shelf = FakeShelf(mapOf(
            first to ShelfPage(200, body(listOf(
                release("diagnosis-v1.3.0", assets = listOf("BNMDiagnosis-windows-x64.msi")),
            )), null),
        ))
        val available = assertIs<UpdateCheck.Available>(walk(shelf))
        assertEquals("diagnosis-v1.3.0", available.release.tag)
        assertEquals("https://github.com/x/releases/download/diagnosis-v1.3.0/BNMDiagnosis-windows-x64.msi",
            available.release.downloadUrl)
    }

    @Test
    fun `the BNMLab asset is preferred over the legacy copy in the same release`() {
        val shelf = FakeShelf(mapOf(first to ShelfPage(200, body(listOf(release("lab-v1.3.0"))), null)))
        assertEquals("https://github.com/x/releases/download/lab-v1.3.0/BNMLab-windows-x64.msi",
            assertIs<UpdateCheck.Available>(walk(shelf)).release.downloadUrl)
    }

    @Test
    fun `a newer release without this platform's installer says so`() {
        val shelf = FakeShelf(mapOf(
            first to ShelfPage(200, body(listOf(release("lab-v1.3.0", assets = listOf("BNMLab-linux-x64.deb")))), null),
        ))
        val failed = assertIs<UpdateCheck.Failed>(walk(shelf, platform = UpdatePlatform.MACOS))
        assertTrue("1.3.0" in failed.message && "macOS" in failed.message, failed.message)
    }

    @Test
    fun `next page URL comes from GitHub's Link header`() {
        assertEquals(pageUrl(2), UpdateChecker.nextPageUrl(link(next = 2, last = 4)))
        // rel=next need not be first.
        assertEquals(pageUrl(3), UpdateChecker.nextPageUrl(
            "<${pageUrl(1)}>; rel=\"prev\", <${pageUrl(3)}>; rel=\"next\", <${pageUrl(4)}>; rel=\"last\""))
        // The last page carries prev/first but no next.
        assertNull(UpdateChecker.nextPageUrl("<${pageUrl(3)}>; rel=\"prev\", <${pageUrl(1)}>; rel=\"first\""))
        assertNull(UpdateChecker.nextPageUrl(null))
        assertNull(UpdateChecker.nextPageUrl(""))
        // Whatever this returns gets fetched: a foreign host must end the walk.
        assertNull(UpdateChecker.nextPageUrl("<https://evil.example/releases?page=2>; rel=\"next\""))
    }
}
