package com.bnm.diagnosis.auth

import com.russhwolf.settings.Settings
import com.russhwolf.settings.set

class SessionManager {
    private val settings: Settings = Settings()

    companion object {
        private const val KEY_SESSION_TOKEN = "session_token"  // BusinessStudio encrypted session token
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_SELECTED_BUSINESS_ID = "selected_business_id"
        private const val KEY_SELECTED_BUSINESS_NAME = "selected_business_name"
    }

    fun saveSessionToken(token: String) = settings.set(KEY_SESSION_TOKEN, token)
    fun getSessionToken(): String? = settings.getStringOrNull(KEY_SESSION_TOKEN)

    fun saveUserId(uid: String) = settings.set(KEY_USER_ID, uid)
    fun getUserId(): String? = settings.getStringOrNull(KEY_USER_ID)

    fun saveUserEmail(email: String) = settings.set(KEY_USER_EMAIL, email)
    fun getUserEmail(): String? = settings.getStringOrNull(KEY_USER_EMAIL)

    fun saveUserName(name: String) = settings.set(KEY_USER_NAME, name)
    fun getUserName(): String? = settings.getStringOrNull(KEY_USER_NAME)

    fun saveSelectedBusiness(businessId: String, businessName: String) {
        settings.set(KEY_SELECTED_BUSINESS_ID, businessId)
        settings.set(KEY_SELECTED_BUSINESS_NAME, businessName)
    }

    fun getSelectedBusinessId(): String? = settings.getStringOrNull(KEY_SELECTED_BUSINESS_ID)
    fun getSelectedBusinessName(): String? = settings.getStringOrNull(KEY_SELECTED_BUSINESS_NAME)

    fun saveSession(user: AdminUser, sessionToken: String) {
        saveSessionToken(sessionToken)
        saveUserId(user.uid)
        user.email?.let { saveUserEmail(it) }
        user.displayName?.let { saveUserName(it) }
    }

    fun clearSessionToken() {
        settings.remove(KEY_SESSION_TOKEN)
    }

    fun clearSession() {
        settings.remove(KEY_SESSION_TOKEN)
        settings.remove(KEY_USER_ID)
        settings.remove(KEY_USER_EMAIL)
        settings.remove(KEY_USER_NAME)
        settings.remove(KEY_SELECTED_BUSINESS_ID)
        settings.remove(KEY_SELECTED_BUSINESS_NAME)
    }

    fun isLoggedIn(): Boolean = !getSessionToken().isNullOrEmpty() && !getUserId().isNullOrEmpty()
}
