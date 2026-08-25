package com.bnm.diagnosis.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ContactDetails(
    @SerialName("primaryPhone") val primaryPhone: String? = null,
    @SerialName("primaryEmail") val primaryEmail: String? = null,
    @SerialName("websiteUrl") val websiteUrl: String? = null
)

@Serializable
data class GeoPoint(
    @SerialName("coordinates") val coordinates: List<Double> = emptyList()
) {
    val longitude: Double? get() = coordinates.getOrNull(0)
    val latitude: Double? get() = coordinates.getOrNull(1)
}

@Serializable
data class SessionConfig(
    @SerialName("inactivity_timeout_minutes") val inactivityTimeoutMinutes: Int = 60,
    @SerialName("cancel_keywords") val cancelKeywords: List<String> = emptyList(),
    @SerialName("cancel_message") val cancelMessage: String? = null,
    @SerialName("expired_message") val expiredMessage: String? = null
)

@Serializable
data class Business(
    @SerialName("_id") val id: String,
    @SerialName("businessName") val name: String,
    @SerialName("businessStatus") val status: String? = null,
    @SerialName("logoUrl") val logo: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("fullAddress") val address: String? = null,
    @SerialName("categories") val categories: List<String> = emptyList(),
    @SerialName("services") val services: List<String> = emptyList(),
    @SerialName("supportedLanguages") val supportedLanguages: List<String> = emptyList(),
    @SerialName("contactDetails") val contact: ContactDetails? = null,
    @SerialName("location") val location: GeoPoint? = null,
    @SerialName("collaborators") val collaborators: List<Collaborator> = emptyList(),
    @SerialName("sessionConfig") val sessionConfig: SessionConfig? = null,
    @SerialName("createdAt") val createdAt: String? = null
) {
    // First category as display label
    val category: String? get() = categories.firstOrNull()
    val phone: String? get() = contact?.primaryPhone
    val email: String? get() = contact?.primaryEmail
    val website: String? get() = contact?.websiteUrl
}

/** Partial business-profile update. Encoded with a null-omitting Json so only the
 *  set fields reach the PATCH /business-accounts/:id route (unset stays unchanged). */
@Serializable
data class BusinessUpdateRequest(
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("website") val website: String? = null,
    @SerialName("categories") val categories: List<String>? = null,
    @SerialName("services") val services: List<String>? = null,
    @SerialName("supportedLanguages") val supportedLanguages: List<String>? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("sessionConfig") val sessionConfig: SessionConfig? = null
)

/** One operating/service area for the Business → Areas tab. */
@Serializable
data class OperatingArea(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("type") val type: String = "Region",
    @SerialName("isActive") val isActive: Boolean = true,
    @SerialName("zipCodes") val zipCodes: List<String> = emptyList(),
    @SerialName("createdAt") val createdAt: String? = null
)

/** Wallet settings (Settings → Wallet). One row per business, synced offline. */
@Serializable
data class WalletSettings(
    @SerialName("walletEnabled") val walletEnabled: Boolean = false,
    @SerialName("minimumBalance") val minimumBalance: Double = 0.0,
    @SerialName("lowBalanceThreshold") val lowBalanceThreshold: Double = 0.0,
    @SerialName("allowNegativeBalance") val allowNegativeBalance: Boolean = false,
    @SerialName("autoDeductForOrders") val autoDeductForOrders: Boolean = false,
    @SerialName("disabledMessage") val disabledMessage: String? = null
)

@Serializable
data class WalletSettingsUpdate(
    @SerialName("walletEnabled") val walletEnabled: Boolean,
    @SerialName("minimumBalance") val minimumBalance: Double,
    @SerialName("lowBalanceThreshold") val lowBalanceThreshold: Double,
    @SerialName("allowNegativeBalance") val allowNegativeBalance: Boolean,
    @SerialName("autoDeductForOrders") val autoDeductForOrders: Boolean,
    @SerialName("disabledMessage") val disabledMessage: String? = null
)

/** Dedicated session-config update — sent with the full object (all fields,
 *  including explicit nulls) so the server overwrites session_config completely. */
@Serializable
data class SessionConfigUpdate(
    @SerialName("sessionConfig") val sessionConfig: SessionConfig
)

@Serializable
data class CollaboratorAddRequest(
    @SerialName("email") val email: String,
    @SerialName("businessRole") val businessRole: String
)

@Serializable
data class CollaboratorRoleRequest(
    @SerialName("businessRole") val businessRole: String
)

@Serializable
data class OperatingAreaUpsertRequest(
    @SerialName("name") val name: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("isActive") val isActive: Boolean? = null,
    @SerialName("zipCodes") val zipCodes: List<String>? = null
)

@Serializable
data class BusinessListResponse(
    @SerialName("businesses") val businesses: List<Business> = emptyList()
)

@Serializable
data class ExtensionInstallation(
    @SerialName("isInstalled") val isInstalled: Boolean = false,
    @SerialName("status") val status: String? = null,
    @SerialName("activatedAt") val activatedAt: String? = null
)

@Serializable
data class Extension(
    @SerialName("slug") val slug: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("installation") val installation: ExtensionInstallation? = null
) {
    val installed: Boolean get() = installation?.isInstalled == true
    val active: Boolean get() = installation?.status == "active"
}

@Serializable
data class ExtensionsResponse(
    @SerialName("extensions") val extensions: List<Extension> = emptyList()
)

@Serializable
data class Collaborator(
    @SerialName("userId") val userId: String,
    @SerialName("businessRole") val businessRole: String,
    @SerialName("status") val status: String = "Accepted",
    @SerialName("email") val email: String? = null,
    @SerialName("phoneNumber") val phoneNumber: String? = null,
    @SerialName("invitedAt") val invitedAt: String? = null,
    @SerialName("acceptedAt") val acceptedAt: String? = null
)

@Serializable
data class BusinessDetail(
    @SerialName("_id") val id: String,
    @SerialName("businessName") val name: String,
    @SerialName("businessStatus") val status: String? = null,
    @SerialName("logoUrl") val logo: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("fullAddress") val address: String? = null,
    @SerialName("categories") val categories: List<String> = emptyList(),
    @SerialName("collaborators") val collaborators: List<Collaborator> = emptyList(),
    @SerialName("createdAt") val createdAt: String? = null
)

@Serializable
data class DashboardStats(
    @SerialName("todayOrders") val todayOrders: Int = 0,
    @SerialName("todayAppointments") val todayAppointments: Int = 0,
    @SerialName("todayRevenue") val todayRevenue: Double = 0.0,
    @SerialName("openTickets") val openTickets: Int = 0,
    @SerialName("totalCustomers") val totalCustomers: Int = 0,
    @SerialName("activeChannels") val activeChannels: Int = 0
)
