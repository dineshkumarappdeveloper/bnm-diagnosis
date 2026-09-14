package com.bnm.lab.diagnostics

/**
 * Last line of defence before a log line is written or shipped in a support
 * report. Call sites are expected to log IDs and counts only; this catches the
 * one that forgets, and anything a third-party library prints to stderr.
 *
 * Deliberately conservative about what it matches: it must not mangle the
 * numbers that make a log useful (durations, counts, epoch timestamps,
 * accession numbers), so each pattern is anchored to a shape that only
 * credentials or personal contact details take.
 */
object LogRedactor {

    private val rules: List<Pair<Regex, String>> = listOf(
        // Authorization headers and bare bearer tokens.
        Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}""") to "$1 [redacted]",
        // JWTs (header.payload.signature, base64url) — device sessions, licence tokens.
        Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}""") to "[jwt]",
        // Long hex runs: report-share capability tokens (64 hex, carried in URL
        // PATHS — whoever holds one can open a patient's report PDF), sha256
        // fingerprints. UUIDs are dashed, so never 32 contiguous, and survive.
        Regex("""(?<![0-9A-Fa-f])[0-9A-Fa-f]{32,}(?![0-9A-Fa-f])""") to "[token]",
        // BNM licence keys: BNMD-XXXX-XXXX-XXXX-XXXX.
        Regex("""\bBNMD(-[A-Z0-9]{4}){4}\b""") to "BNMD-[key]",
        // key=value / "key": "value" pairs for anything credential-shaped.
        Regex("""(?i)("?(?:password|passwd|pin|secret|token|api[_-]?key|access[_-]?key|device[_-]?token|license[_-]?jwt|authorization)"?\s*[:=]\s*"?)[^"\s,&}]+""") to "$1[redacted]",
        // kotlinx.serialization quotes the RECORD it failed to decode: a trailing
        // "JSON input: {…}" line (the whole doc when short), and the offending
        // value in "Failed to parse literal '<value>'". A malformed synced patient
        // or referrer would otherwise put names into the log through an exception
        // message — which no pattern below could recognise.
        Regex("""(?m)^(\s*JSON input:).*$""") to "$1 [removed]",
        Regex("""(Failed to parse (?:literal|type)\s*)'[^'\n]{0,400}'""") to "$1'[value]'",
        // Email addresses.
        Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""") to "[email]",
        // Indian mobile numbers, as people actually write them: optional +91 / 91 /
        // 0 (or a URL-encoded %2B91), then 10 digits starting 6-9 — unbroken,
        // 5+5 ("98765 43210") or 4+3+3 ("9876 543 210"), spaced or hyphenated.
        // Epoch seconds are 10 digits starting 1-2, so they are left alone.
        Regex("""(?<![\w-])(?:(?:\+|%2B)?91[\s-]?|0)?(?:[6-9]\d{4}[\s-]?\d{5}|[6-9]\d{3}[\s-]\d{3}[\s-]\d{3})(?![\w-])""") to "[phone]",
        // The OS account name inside a path, whatever form the path takes. The
        // literal home-dir replace below misses Windows 8.3 short names
        // (C:\Users\DINESH~1\…, typical of %TEMP%) and file:/ URIs.
        Regex("""(?i)\b([A-Z]:[\\/]+(?:Users|Documents and Settings)[\\/]+)[^\\/\s:*?"<>|]+""") to "$1[user]",
        Regex("""(/(?:Users|home)/)[^/\s]+""") to "$1[user]",
    )

    private var homeDir: String? = null

    /** The OS account name rides in every absolute path; show `~` instead. */
    fun setHomeDir(path: String?) {
        homeDir = path?.trimEnd('/', '\\')?.takeIf { it.length > 1 }
    }

    fun redact(line: String): String {
        var out = line
        homeDir?.let { out = out.replace(it, "~") }
        for ((pattern, replacement) in rules) out = pattern.replace(out, replacement)
        return out
    }
}
