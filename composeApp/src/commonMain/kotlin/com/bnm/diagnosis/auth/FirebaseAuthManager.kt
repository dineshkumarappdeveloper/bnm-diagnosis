package com.bnm.diagnosis.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AdminUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?
)

/**
 * Email-only auth stub. BNMDiagnosis signs in with email/password against
 * `admin-auth/login` (see [AuthRepository.signInWithEmailPassword]); Google/Apple
 * are intentionally not wired (no Firebase dependency). Kept as a concrete class
 * so AuthRepository's constructor shape is unchanged.
 */
class FirebaseAuthManager {
    val isSignedIn: Boolean = false
    val currentUser: AdminUser? = null
    val signInError: StateFlow<String?> = MutableStateFlow(null)

    suspend fun signInWithGoogle(): Result<AdminUser> =
        Result.failure(Exception("Google sign-in is not available in BNM Diagnosis"))

    suspend fun signInWithApple(): Result<AdminUser> =
        Result.failure(Exception("Apple sign-in is not available in BNM Diagnosis"))

    suspend fun getIdToken(forceRefresh: Boolean = false): String? = null
    fun getNonce(): String? = null
    suspend fun signOut() {}
    fun clearError() {}
}
