package com.bnm.diagnosis.update

import com.bnm.diagnosis.BuildInfo
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
 * `bnmadmin-releases` repo as a rolling `diagnosis-latest` tag (stable download
 * URLs for the website) PLUS a versioned `diagnosis-v<x.y.z>` tag. The rolling
 * tag's name carries no version, so it cannot answer "is there something newer"
 * — this reads the VERSIONED releases and compares semver.
 *
 * The repo is public, so this is an unauthenticated call. GitHub allows 60/hour
 * per IP unauthenticated; a lab checking on demand is nowhere near that, but the
 * checker never polls on a timer for exactly that reason — it runs when the
 * operator asks, plus at most once per app start.
 */
object UpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/dineshkumarappdeveloper/bnmadmin-releases/releases?per_page=30"

    /** Only our own releases — the shelf is shared with BNM Admin and BNM Billing. */
    private const val TAG_PREFIX = "diagnosis-v"

    private val json = Json { ignoreUnknownKeys = true }

    /** The running build's version, baked in by the `generateBuildInfo` Gradle task. */
    val currentVersion: String get() = BuildInfo.VERSION

    suspend fun check(client: HttpClient, platform: UpdatePlatform): UpdateCheck =
        withContext(Dispatchers.Default) {
            runCatching {
                val res = client.get(RELEASES_API) {
                    // GitHub asks for an explicit API version + UA; without a UA it
                    // rejects some clients outright.
                    header("Accept", "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2022-11-28")
                    header("User-Agent", "BNMDiagnosis/${currentVersion}")
                }
                if (!res.status.isSuccess()) {
                    return@runCatching UpdateCheck.Failed(
                        if (res.status.value == 403)
                            "GitHub is rate-limiting update checks right now — try again in a while."
                        else "Update check failed (HTTP ${res.status.value})."
                    )
                }

                val newest = json.parseToJsonElement(res.bodyAsText()).jsonArray
                    .mapNotNull { el ->
                        val o = el.jsonObject
                        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
                        if (!tag.startsWith(TAG_PREFIX)) return@mapNotNull null
                        // Drafts are not downloadable; prereleases are not for labs.
                        if (o["draft"]?.jsonPrimitive?.contentOrNullSafe() == "true") return@mapNotNull null
                        if (o["prerelease"]?.jsonPrimitive?.contentOrNullSafe() == "true") return@mapNotNull null
                        val version = tag.removePrefix(TAG_PREFIX)
                        if (parseSemver(version) == null) return@mapNotNull null
                        val asset = o["assets"]?.jsonArray?.firstOrNull { a ->
                            a.jsonObject["name"]?.jsonPrimitive?.contentOrNullSafe() == platform.assetName
                        }?.jsonObject
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
                    .maxWithOrNull(compareBy(SEMVER_ORDER) { it.version })
                    ?: return@runCatching UpdateCheck.UpToDate(currentVersion)

                when {
                    compareSemver(newest.version, currentVersion) <= 0 ->
                        UpdateCheck.UpToDate(currentVersion)
                    newest.downloadUrl == null ->
                        // A newer version exists but not for this platform — say so
                        // rather than offering a button that cannot work.
                        UpdateCheck.Failed(
                            "Version ${newest.version} is out, but there is no " +
                                "${platform.label} build in that release."
                        )
                    else -> UpdateCheck.Available(newest)
                }
            }.getOrElse { e ->
                UpdateCheck.Failed(e.message ?: "Could not reach the update server.")
            }
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
            val res = client.get(checksumsUrl) { header("User-Agent", "BNMDiagnosis/${currentVersion}") }
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

/** Which installer this build should look for in a release. */
enum class UpdatePlatform(val assetName: String, val label: String) {
    WINDOWS("BNMDiagnosis-windows-x64.msi", "Windows"),
    MACOS("BNMDiagnosis-macos-arm64.dmg", "macOS"),
    LINUX("BNMDiagnosis-linux-x64.deb", "Linux"),
    /** Mobile updates come from the store — never from us. */
    STORE_MANAGED("", "this platform"),
}

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
