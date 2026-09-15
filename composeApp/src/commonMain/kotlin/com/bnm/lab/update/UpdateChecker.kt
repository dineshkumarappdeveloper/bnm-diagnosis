package com.bnm.lab.update

import com.bnm.lab.BuildInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * In-app update check for the DESKTOP build.
 *
 * WHERE THE VERSION COMES FROM: releases are published to the public
 * `bnmadmin-releases` repo as a rolling `lab-latest` tag (stable download
 * URLs for the website) PLUS a versioned `lab-v<x.y.z>` tag. The rolling
 * tag's name carries no version, so it cannot answer "is there something newer"
 * — this reads the VERSIONED releases and compares semver.
 *
 * The repo is public, so this is an unauthenticated call. GitHub allows 60/hour
 * per IP unauthenticated; a lab checking on demand is nowhere near that, but the
 * checker never polls on a timer for exactly that reason — it runs when the
 * operator asks, plus at most once per app start.
 *
 * WHY IT WALKS PAGES: the shelf is shared, and GitHub lists it by tag name
 * descending — every BNM Admin `v*` tag sorts ahead of `lab-*`, which sorts
 * ahead of `diagnosis-*` and `billing-*`. A single fixed-size page therefore
 * loses our releases once enough other releases pile up in front of them, and
 * the app reports "up to date" forever with no error. So the check reads
 * 100-per-page (GitHub's maximum) and follows `Link: rel="next"` until a page
 * holds one of our releases, capped at [MAX_PAGES]. On today's shelf that is
 * still exactly one request.
 */
object UpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/dineshkumarappdeveloper/bnmadmin-releases/releases?per_page=100"

    /**
     * Hard stop on the page walk: 500 releases deep. Every page is a request
     * against the 60/hour budget, and reaching this means the shelf has changed
     * shape — so it reports a failure instead of pretending to be up to date.
     */
    internal const val MAX_PAGES = 5

    /**
     * Only our own releases — the shelf is shared with BNM Admin and BNM Billing.
     *
     * TWO prefixes, and the legacy one is permanent. The tag prefix is COMPILED
     * INTO every shipped build, so a copy installed before the BNM Lab rename is
     * looking for `diagnosis-v*` and nothing we do server-side can teach it
     * otherwise. CI therefore keeps publishing both tags, and this build accepts
     * both — otherwise the first renamed release would make every older install
     * report "you are up to date" forever, silently, with no way back except a
     * manual reinstall.
     */
    private val TAG_PREFIXES = listOf("lab-v", "diagnosis-v")

    private val json = Json { ignoreUnknownKeys = true }

    /** The running build's version, baked in by the `generateBuildInfo` Gradle task. */
    val currentVersion: String get() = BuildInfo.VERSION

    suspend fun check(client: HttpClient, platform: UpdatePlatform): UpdateCheck =
        withContext(Dispatchers.Default) {
            runCatching {
                checkShelf(platform, currentVersion) { url ->
                    val res = client.get(url) {
                        // GitHub asks for an explicit API version + UA; without a UA it
                        // rejects some clients outright.
                        header("Accept", "application/vnd.github+json")
                        header("X-GitHub-Api-Version", "2022-11-28")
                        header("User-Agent", "BNMLab/${currentVersion}")
                    }
                    ShelfPage(
                        status = res.status.value,
                        body = if (res.status.isSuccess()) res.bodyAsText() else "",
                        link = res.headers["Link"],
                    )
                }
            }.getOrElse { e ->
                UpdateCheck.Failed(e.message ?: "Could not reach the update server.")
            }
        }

    /**
     * The whole decision, with the network behind [fetchPage] so the page walk
     * can be tested without GitHub.
     *
     * Stops at the FIRST page holding one of our releases. GitHub lists newest
     * tag first (tag commit date, then tag name descending, versions compared
     * numerically), so a newer lab release never sits on a later page than an
     * older one — stopping there keeps a check to as few requests as the shelf
     * allows.
     */
    internal suspend fun checkShelf(
        platform: UpdatePlatform,
        runningVersion: String,
        fetchPage: suspend (url: String) -> ShelfPage,
    ): UpdateCheck {
        var url = RELEASES_API
        repeat(MAX_PAGES) {
            val page = fetchPage(url)
            if (page.status !in 200..299) {
                return UpdateCheck.Failed(
                    if (page.status == 403 || page.status == 429)
                        "GitHub is rate-limiting update checks right now — try again in a while."
                    else "Update check failed (HTTP ${page.status})."
                )
            }
            val newest = parseReleases(page.body, platform)
                .maxWithOrNull(compareBy(SEMVER_ORDER) { it.version })
            if (newest != null) return verdict(newest, runningVersion, platform)
            // No next page: the whole shelf has been read and none of it is ours.
            url = nextPageUrl(page.link) ?: return UpdateCheck.UpToDate(runningVersion)
        }
        return UpdateCheck.Failed(
            "Could not find BNM Lab releases on the update server — please report this to support."
        )
    }

    private fun verdict(newest: ReleaseInfo, runningVersion: String, platform: UpdatePlatform): UpdateCheck =
        when {
            compareSemver(newest.version, runningVersion) <= 0 ->
                UpdateCheck.UpToDate(runningVersion)
            newest.downloadUrl == null ->
                // A newer version exists but not for this platform — say so
                // rather than offering a button that cannot work.
                UpdateCheck.Failed(
                    "Version ${newest.version} is out, but there is no " +
                        "${platform.label} build in that release."
                )
            else -> UpdateCheck.Available(newest)
        }

    /** Our releases on one page of the shelf listing; everything else is dropped. */
    internal fun parseReleases(body: String, platform: UpdatePlatform): List<ReleaseInfo> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            val prefix = TAG_PREFIXES.firstOrNull { tag.startsWith(it) }
                ?: return@mapNotNull null
            // Drafts are not downloadable; prereleases are not for labs.
            if (o["draft"]?.jsonPrimitive?.contentOrNullSafe() == "true") return@mapNotNull null
            if (o["prerelease"]?.jsonPrimitive?.contentOrNullSafe() == "true") return@mapNotNull null
            val version = tag.removePrefix(prefix)
            if (parseSemver(version) == null) return@mapNotNull null
            val asset = platform.assetNames.firstNotNullOfOrNull { wanted ->
                o["assets"]?.jsonArray?.firstOrNull { a ->
                    a.jsonObject["name"]?.jsonPrimitive?.contentOrNullSafe() == wanted
                }?.jsonObject
            }
            val checksums = o["assets"]?.jsonArray?.firstOrNull { a ->
                a.jsonObject["name"]?.jsonPrimitive?.contentOrNullSafe() == "checksums.txt"
            }?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.contentOrNullSafe()
            ReleaseInfo(
                version = version,
                tag = tag,
                notes = o["body"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                downloadUrl = asset?.get("browser_download_url")?.jsonPrimitive?.contentOrNullSafe(),
                sizeBytes = asset?.get("size")?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull(),
                checksumsUrl = checksums,
            )
        }

    /**
     * The `rel="next"` target of a GitHub `Link` header, or null on the last page.
     *
     * Only ever a GitHub API URL: whatever this returns gets fetched next, so a
     * header pointing anywhere else ends the walk rather than redirecting it.
     */
    internal fun nextPageUrl(link: String?): String? =
        link.orEmpty().split(',').firstNotNullOfOrNull { entry ->
            val parts = entry.split(';').map { it.trim() }
            val target = parts.first()
            if (!target.startsWith("<") || !target.endsWith(">")) return@firstNotNullOfOrNull null
            val isNext = parts.drop(1).any { param ->
                val (key, value) = param.split('=', limit = 2).takeIf { it.size == 2 }
                    ?: return@any false
                key.trim().equals("rel", ignoreCase = true) &&
                    value.trim().removeSurrounding("\"").split(' ').any { it.equals("next", ignoreCase = true) }
            }
            target.removeSurrounding("<", ">").takeIf { isNext && it.startsWith("https://api.github.com/") }
        }

    /**
     * sha256 for [assetName] out of a release's `checksums.txt`, or null.
     *
     * Format is `sha256sum` output: `<hex>  <filename>` per line. Null on any
     * problem — the caller must treat null as "unverified", never as "fine".
     */
    suspend fun fetchChecksum(client: HttpClient, checksumsUrl: String?, assetName: String): String? {
        if (checksumsUrl.isNullOrBlank()) return null
        return runCatching {
            val res = client.get(checksumsUrl) { header("User-Agent", "BNMLab/${currentVersion}") }
            if (!res.status.isSuccess()) return null
            res.bodyAsText().lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2 && parts[1].trim().removePrefix("*") == assetName) parts[0] else null
                }
                .firstOrNull()
                ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
        }.getOrNull()
    }

    // ── semver ────────────────────────────────────────────────────────────────
    // Compared numerically, NOT as strings: "1.10.0" < "1.9.0" lexicographically,
    // which would hide every update after 1.9.

    private val SEMVER_ORDER = Comparator<String> { a, b -> compareSemver(a, b) }

    fun parseSemver(v: String): Triple<Int, Int, Int>? {
        val parts = v.trim().removePrefix("v").split('.')
        if (parts.size != 3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return Triple(nums[0], nums[1], nums[2])
    }

    /** >0 when [a] is newer than [b]. Unparseable sorts oldest. */
    fun compareSemver(a: String, b: String): Int {
        val pa = parseSemver(a) ?: return if (parseSemver(b) == null) 0 else -1
        val pb = parseSemver(b) ?: return 1
        return compareValuesBy(pa, pb, { it.first }, { it.second }, { it.third })
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()
}

/**
 * Which installer this build should look for in a release.
 *
 * In preference order: the BNM Lab filename first, then the pre-rename
 * BNM Lab one. Releases published during the transition carry both, and a
 * release that only ever carried the old names still resolves.
 */
enum class UpdatePlatform(val assetNames: List<String>, val label: String) {
    WINDOWS(listOf("BNMLab-windows-x64.msi", "BNMDiagnosis-windows-x64.msi"), "Windows"),
    MACOS(listOf("BNMLab-macos-arm64.dmg", "BNMDiagnosis-macos-arm64.dmg"), "macOS"),
    LINUX(listOf("BNMLab-linux-x64.deb", "BNMDiagnosis-linux-x64.deb"), "Linux"),
    /** Mobile updates come from the store — never from us. */
    STORE_MANAGED(emptyList(), "this platform"),
}

/** One raw response from the release listing — just what the page walk reads. */
internal data class ShelfPage(
    val status: Int,
    /** Empty unless [status] is 2xx. */
    val body: String,
    /** The `Link` header, which carries the next page's URL. */
    val link: String?,
)

data class ReleaseInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val downloadUrl: String?,
    val sizeBytes: Long?,
    /** `checksums.txt` on the release, if published. Null for releases cut before
     *  CI started emitting it — the updater then says the download is unverified
     *  rather than silently implying it checked something. */
    val checksumsUrl: String? = null,
)

sealed interface UpdateCheck {
    data class UpToDate(val version: String) : UpdateCheck
    data class Available(val release: ReleaseInfo) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}
