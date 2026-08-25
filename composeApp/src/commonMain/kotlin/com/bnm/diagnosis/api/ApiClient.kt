package com.bnm.diagnosis.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object ApiClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true  // safely coerce mismatched types (e.g. numeric strings → Double)
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
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        println("[BNMAdmin HTTP] $message")
                    }
                }
                level = LogLevel.BODY
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
