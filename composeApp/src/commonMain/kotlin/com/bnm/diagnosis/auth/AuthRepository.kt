package com.bnm.diagnosis.auth

import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.api.Constants
import com.bnm.diagnosis.billing.BillingPrefs
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Result of a successful device pairing — enough to seed the local series. */
data class PairResult(
    val businessId: String,
    val businessName: String,
    val seriesCode: String,
    val prefix: String,
    val numberFormat: String,
    val highWater: Long = 0,
    val upiVpa: String? = null,
)

class AuthRepository(
    private val firebaseAuthManager: FirebaseAuthManager,
    private val sessionManager: SessionManager
) {
    private val _isLoggedIn = MutableStateFlow(sessionManager.isLoggedIn())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _currentUser = MutableStateFlow(firebaseAuthManager.currentUser)
    val currentUser: StateFlow<AdminUser?> = _currentUser.asStateFlow()

    private val httpClient = ApiClient.create()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun signInWithGoogle(): Result<AdminUser> {
        val result = firebaseAuthManager.signInWithGoogle()
        result.onSuccess { user ->
            // Exchange Google ID token for BusinessStudio session token
            val idToken = firebaseAuthManager.getIdToken() ?: ""
            val nonce = firebaseAuthManager.getNonce()
            val sessionToken = exchangeForSessionToken(idToken, nonce, provider = "supabase-google") ?: ""
            sessionManager.saveSession(user, sessionToken)
            _currentUser.value = user
            _isLoggedIn.value = sessionToken.isNotEmpty()
        }
        return result
    }

    /**
     * Sign in with Apple. Mirrors the Google flow: run the native/web Apple
     * credential request, then exchange the returned identity token at
     * admin-auth/login with provider="supabase-apple". Requires the Apple
     * provider to be enabled in Supabase Auth (and the native flow wired per
     * platform) — until then the manager returns a clear "coming soon" failure.
     */
    suspend fun signInWithApple(): Result<AdminUser> {
        val result = firebaseAuthManager.signInWithApple()
        result.onSuccess { user ->
            val idToken = firebaseAuthManager.getIdToken() ?: ""
            val nonce = firebaseAuthManager.getNonce()
            val sessionToken = exchangeForSessionToken(idToken, nonce, provider = "supabase-apple") ?: ""
            sessionManager.saveSession(user, sessionToken)
            _currentUser.value = user
            _isLoggedIn.value = sessionToken.isNotEmpty()
        }
        return result
    }

    /**
     * Email + password login. Posts to `admin-auth/login` with
     * authMethod="supabase"; the server validates the credentials against
     * Supabase Auth and returns the SAME BNM session token the Google path uses.
     * Identity (uid/email) is read from the signed token's claims. All subsequent
     * API calls send this token as Bearer — no Firebase involved.
     */
    suspend fun signInWithEmailPassword(email: String, password: String): Result<AdminUser> =
        withContext(Dispatchers.Default) {
            val cleanEmail = email.trim()
            if (cleanEmail.isEmpty() || password.isEmpty()) {
                return@withContext Result.failure(Exception("Enter your email and password"))
            }
            try {
                val body = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("authMethod", "supabase")
                        put("email", cleanEmail)
                        put("password", password)
                        put("app", "billing")
                    },
                )
                val loginUrl = "${Constants.EDGE_FUNCTIONS_BASE_URL}/admin-auth/login"
                val response = httpClient.post(loginUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val token = obj["token"]?.jsonPrimitive?.content
                if (token.isNullOrEmpty()) {
                    val err = obj["error"]?.jsonPrimitive?.content ?: "Invalid email or password"
                    return@withContext Result.failure(Exception(err))
                }
                val claims = decodeJwtClaims(token)
                val resolvedEmail = claims?.get("email")?.jsonPrimitive?.content ?: cleanEmail
                val user = AdminUser(
                    uid = claims?.get("userId")?.jsonPrimitive?.content ?: cleanEmail,
                    email = resolvedEmail,
                    displayName = resolvedEmail.substringBefore("@"),
                    photoUrl = null,
                )
                sessionManager.saveSession(user, token)
                _currentUser.value = user
                _isLoggedIn.value = true
                Result.success(user)
            } catch (e: Exception) {
                Result.failure(Exception(e.message ?: "Sign-in failed"))
            }
        }

    /**
     * Pair this device as a billing counter. Exchanges a one-time QR token OR
     * 6-digit code (minted by the owner in the Billing extension) at
     * `admin-billing/pair` — NO prior auth — for a long-lived, billing-scoped
     * counter session pre-bound to the business + series. On success the session
     * + selected business are persisted; the caller seeds the local series.
     * One scan replaces email login + business pick + manual series registration.
     */
    suspend fun pairWithCode(code: String?, token: String?, deviceId: String, deviceName: String): Result<PairResult> =
        withContext(Dispatchers.Default) {
            try {
                val body = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        code?.trim()?.takeIf { it.isNotEmpty() }?.let { put("code", it) }
                        token?.trim()?.takeIf { it.isNotEmpty() }?.let { put("token", it) }
                        put("deviceId", deviceId)
                        put("deviceName", deviceName)
                    },
                )
                val url = "${Constants.EDGE_FUNCTIONS_BASE_URL}/admin-billing/pair"
                val response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
                val sessionToken = obj["token"]?.jsonPrimitive?.content
                if (sessionToken.isNullOrEmpty()) {
                    val err = obj["error"]?.jsonPrimitive?.content ?: "Pairing failed"
                    return@withContext Result.failure(Exception(pairErrorMessage(err)))
                }
                val businessId = obj["businessId"]?.jsonPrimitive?.content ?: ""
                val businessName = obj["businessName"]?.jsonPrimitive?.content ?: "Business"
                val seriesCode = obj["seriesCode"]?.jsonPrimitive?.content ?: "C1"
                val prefix = obj["prefix"]?.jsonPrimitive?.content ?: "INV"
                val numberFormat = obj["numberFormat"]?.jsonPrimitive?.content ?: "{prefix}-{series}-{seq}"
                val highWater = obj["highWater"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val counterName = obj["counterName"]?.jsonPrimitive?.content ?: "Counter"
                val counterId = obj["counterId"]?.jsonPrimitive?.content ?: businessId
                val upiVpa = obj["upiVpa"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                    ?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                val user = AdminUser(uid = counterId, email = null, displayName = counterName, photoUrl = null)
                sessionManager.saveSession(user, sessionToken)
                sessionManager.saveSelectedBusiness(businessId, businessName)
                // Counter's UPI ID for the payment sheet's QR (refreshed by counter sync).
                BillingPrefs().counterUpiVpa = upiVpa.orEmpty()
                _currentUser.value = user
                _isLoggedIn.value = true
                Result.success(PairResult(businessId, businessName, seriesCode, prefix, numberFormat, highWater, upiVpa))
            } catch (e: Exception) {
                Result.failure(Exception(e.message ?: "Pairing failed"))
            }
        }

    private fun pairErrorMessage(err: String): String = when (err) {
        "invalid_or_used" -> "That code is invalid or already used"
        "expired" -> "That code has expired — ask your owner for a new one"
        "counter_revoked" -> "This counter was removed by the owner"
        "token or code required" -> "Enter the pairing code"
        else -> err
    }

    /**
     * Request a password-reset email. Posts to `admin-auth/forgot-password`,
     * which calls Supabase `resetPasswordForEmail`. The server always reports
     * success (never reveals whether the email exists) — we surface only a
     * transport/validation error.
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.Default) {
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty()) {
            return@withContext Result.failure(Exception("Enter your email first"))
        }
        try {
            val body = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { put("email", cleanEmail) },
            )
            val url = "${Constants.EDGE_FUNCTIONS_BASE_URL}/admin-auth/forgot-password"
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val err = obj["error"]?.jsonPrimitive?.content
            if (err != null) Result.failure(Exception(err)) else Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Couldn't send reset link"))
        }
    }

    /** Decode a JWT's payload claims (base64url) without verifying the signature. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeJwtClaims(token: String): JsonObject? = try {
        val parts = token.split(".")
        if (parts.size < 2) {
            null
        } else {
            var p = parts[1].replace('-', '+').replace('_', '/')
            while (p.length % 4 != 0) p += "="
            json.parseToJsonElement(Base64.Default.decode(p).decodeToString()).jsonObject
        }
    } catch (e: Exception) {
        null
    }

    /**
     * POST Google ID token to the `admin-auth/login` Supabase edge function to
     * receive a BNM session token. Uses provider="supabase-google" so the
     * server-side exchange validates against Supabase Auth's Google provider.
     * All subsequent API calls send this token as Bearer.
     */
    private suspend fun exchangeForSessionToken(
        idToken: String,
        nonce: String? = null,
        provider: String = "supabase-google"
    ): String? = withContext(Dispatchers.Default) {
        try {
            val body = buildString {
                append("{\"idToken\":\"").append(idToken).append("\",\"provider\":\"").append(provider).append("\"")
                if (nonce != null) append(",\"nonce\":\"").append(nonce).append("\"")
                append("}")
            }
            val loginUrl = "${Constants.EDGE_FUNCTIONS_BASE_URL}/admin-auth/login"
            val response = httpClient.post(loginUrl) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val text = response.bodyAsText()
            json.parseToJsonElement(text).jsonObject["token"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    suspend fun signOut() {
        firebaseAuthManager.signOut()
        sessionManager.clearSession()
        _currentUser.value = null
        _isLoggedIn.value = false
    }

    /**
     * Returns the BusinessStudio session token for API calls.
     * If missing, tries to re-exchange the current Firebase token.
     */
    suspend fun getAuthToken(): String? {
        val saved = sessionManager.getSessionToken()
        if (!saved.isNullOrEmpty()) return saved
        // Re-exchange if session expired or was cleared
        val idToken = withContext(Dispatchers.Default) {
            firebaseAuthManager.getIdToken(forceRefresh = true)
        } ?: return null
        val nonce = firebaseAuthManager.getNonce()
        val newToken = exchangeForSessionToken(idToken, nonce) ?: return null
        sessionManager.saveSessionToken(newToken)
        return newToken
    }

    fun getSelectedBusinessId() = sessionManager.getSelectedBusinessId()
    fun getSelectedBusinessName() = sessionManager.getSelectedBusinessName()
    fun saveSelectedBusiness(id: String, name: String) = sessionManager.saveSelectedBusiness(id, name)
}
