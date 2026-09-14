package com.bnm.lab.api

import io.ktor.client.HttpClient
import com.bnm.lab.diagnostics.AppLog
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

object ApiClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true  // safely coerce mismatched types (e.g. numeric strings → Double)
    }

    /** Developer switch (desktop main reads BNM_HTTP_DEBUG). Console only — never logged. */
    var consoleHttpBodies: Boolean = false

    /**
     * One activity-log line per HTTP call. Slow and failed calls are what explain
     * "sync is stuck" and "it says no connection", so failures carry the exception
     * type and the elapsed time at which they gave up.
     */
    private val HttpActivityLog = createClientPlugin("HttpActivityLog") {
        on(Send) { request ->
            val started = TimeSource.Monotonic.markNow()
            val what = "${request.method.value} ${request.url.host}/${loggablePath(request.url.pathSegments)}"
            try {
                val call = proceed(request)
                val status = call.response.status.value
                val ms = started.elapsedNow().inWholeMilliseconds
                if (status >= 400) AppLog.w("HTTP", "$what -> $status in $ms ms")
                else AppLog.i("HTTP", "$what -> $status in $ms ms")
                call
            } catch (e: Throwable) {
                // Exception CLASS only. Ktor's timeout messages embed the full URL
                // ("Request timeout has expired [url=…]") — query string and any
                // capability token included — and the class alone already says
                // what happened: UnknownHostException, ConnectException,
                // HttpRequestTimeoutException, SSLHandshakeException…
                val cause = e.cause?.let { " (cause ${it::class.simpleName})" }.orEmpty()
                AppLog.w("HTTP", "$what FAILED after ${started.elapsedNow().inWholeMilliseconds} ms: " +
                    "${e::class.simpleName}$cause")
                throw e
            }
        }
    }

    private val uuidSegment = Regex("""[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}""")
    private val opaqueSegment = Regex("""[A-Za-z0-9_-]{20,}""")

    /**
     * The request path as a TEMPLATE fit for an emailed log. Record ids (UUIDs)
     * stay — they are what lets support find the row — but any other long opaque
     * segment becomes `{token}`: some routes carry a capability in the path
     * (`/reports/<64-hex share token>/whatsapp`), and that token alone opens a
     * patient's report PDF.
     */
    internal fun loggablePath(segments: List<String>): String =
        segments.filter { it.isNotEmpty() }.joinToString("/") { seg ->
            when {
                uuidSegment.matches(seg) -> seg
                // Route names are hyphenated WORDS ("clinical-recording-process");
                // random tokens effectively always contain a digit.
                opaqueSegment.matches(seg) && seg.any { it.isDigit() } -> "{token}"
                else -> seg
            }
        }

    // Standard timeout for regular API calls
    const val DEFAULT_TIMEOUT_MS = 30_000L
    // Longer timeout for AI calls which run agentic loops (tool calls + LLM round-trips)
    const val AI_TIMEOUT_MS = 120_000L

    fun create(): HttpClient {
        return HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = DEFAULT_TIMEOUT_MS
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = DEFAULT_TIMEOUT_MS
            }
            // Every call goes on the activity log as METADATA ONLY — method, host,
            // path, status, duration. Never headers (bearer tokens), never the
            // query string, never bodies (patients, results): the log is emailed.
            install(HttpActivityLog)
            // Full bodies are for a developer at a console, opt-in via
            // BNM_HTTP_DEBUG, and deliberately bypass AppLog so they can never
            // reach a log file. (This used to be LogLevel.BODY unconditionally,
            // which also buffered every response body just to print it.)
            if (consoleHttpBodies) {
                install(Logging) {
                    logger = object : Logger {
                        override fun log(message: String) = println("[HTTP] $message")
                    }
                    level = LogLevel.BODY
                }
            }
            // No default URL — every API call uses the absolute Supabase Edge
            // Function URL via `BusinessStudioApi.edgeUrl()`. Keeping a default
            // here would silently mask any accidental relative-URL bug.
            defaultRequest {
                contentType(ContentType.Application.Json)
            }
        }
    }
}
