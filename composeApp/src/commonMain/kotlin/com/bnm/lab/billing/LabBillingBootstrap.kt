package com.bnm.lab.billing

import com.bnm.lab.api.LabApi
import com.bnm.lab.api.LabBillingSeries
import com.bnm.lab.api.LabSyncDisabledException
import com.bnm.lab.chat.BillingRepository
import com.bnm.lab.chat.currentFy
import com.bnm.lab.license.LicenseManager

private const val LOCAL_PREFIX = "LAB"
private const val LOCAL_FORMAT = "{prefix}-{series}-{seq}"

/**
 * Self-heal for the missing counter-pairing step: lab devices never see
 * BNMBilling's pairing flow, so before the first bill (and on every sync
 * sweep) make sure this device holds a bound numbering series. Connected
 * licences fetch their per-seat series (L1/L2/L3) from `admin-lab
 * /billing-series`; OFFLINE licences mint a local series without asking anyone
 * ([BillingScope.offlineSeriesCode]) — their bills never sync, so the number
 * lives only on this device. No-op once bound.
 *
 * @return true when a series is bound and billing can proceed.
 */
suspend fun ensureLabBillingSeries(
    api: LabApi,
    billing: BillingRepository,
    businessId: String,
    licence: LicenseManager = LicenseManager(),
): Boolean {
    val st = licence.state.value
    return ensureLabBillingSeriesWith(
        billing = billing,
        businessId = businessId,
        standalone = st.isStandalone,
        offlineSeries = BillingScope.offlineSeriesCode(licence.deviceId),
    ) { fy -> api.billingSeries(fy) }
}

/**
 * [ensureLabBillingSeries] with the licence facts and the server call injected,
 * so the rule that matters most — an offline lab never touches the network to
 * bill — is testable without either.
 */
internal suspend fun ensureLabBillingSeriesWith(
    billing: BillingRepository,
    businessId: String,
    standalone: Boolean,
    offlineSeries: String,
    fetchServerSeries: suspend (fy: String) -> Result<LabBillingSeries>,
): Boolean {
    if (businessId.isBlank()) return false
    if (billing.deviceSeries(businessId) != null) return true
    val fy = currentFy()

    // The offline edition's promise is that nothing leaves the PC, and the bill
    // bootstrap used to break it: it asked the server first and minted locally
    // only after being refused. The offline archive key is refused outright too
    // (a connected licence that lost its business must not post a key no server
    // knows).
    if (standalone || BillingScope.isOffline(businessId)) {
        billing.registerSeriesLocal(businessId, offlineSeries, fy, LOCAL_PREFIX, LOCAL_FORMAT, 0)
        return billing.deviceSeries(businessId) != null
    }

    fetchServerSeries(fy)
        .onSuccess { s ->
            // An install that billed offline before its move to the connected
            // edition may already have printed this very code (older offline
            // builds numbered LAB-L1…); continue past it rather than repeat 0001.
            val printed = billing.maxHighWater(s.seriesCode, fy)
            billing.registerSeriesLocal(businessId, s.seriesCode, fy, s.prefix, s.numberFormat, maxOf(s.highWater, printed))
        }
        .onFailure { e ->
            // Refused as offline-only: no server anchor exists or ever will.
            if (e is LabSyncDisabledException) {
                billing.registerSeriesLocal(businessId, offlineSeries, fy, LOCAL_PREFIX, LOCAL_FORMAT, 0)
            }
        }
    return billing.deviceSeries(businessId) != null
}
