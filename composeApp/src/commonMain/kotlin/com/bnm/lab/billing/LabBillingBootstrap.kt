package com.bnm.lab.billing

import com.bnm.lab.api.LabApi
import com.bnm.lab.api.LabSyncDisabledException
import com.bnm.lab.chat.BillingRepository
import com.bnm.lab.chat.currentFy

/**
 * Self-heal for the missing counter-pairing step: lab devices never see
 * BNMBilling's pairing flow, so before the first bill (and on every sync
 * sweep) make sure this device holds a bound numbering series. Connected
 * licences fetch their per-seat series (L1/L2/L3) from `admin-lab
 * /billing-series`; STANDALONE licences mint a local L1 — their bills never
 * sync, so the number lives only on this device. No-op once bound.
 *
 * @return true when a series is bound and billing can proceed.
 */
suspend fun ensureLabBillingSeries(api: LabApi, billing: BillingRepository, businessId: String): Boolean {
    if (businessId.isBlank()) return false
    if (billing.deviceSeries(businessId) != null) return true
    val fy = currentFy()
    api.billingSeries(fy)
        .onSuccess { s ->
            billing.registerSeriesLocal(businessId, s.seriesCode, fy, s.prefix, s.numberFormat, s.highWater)
        }
        .onFailure { e ->
            if (e is LabSyncDisabledException) {
                // Offline-only licence: no server anchor exists or ever will.
                billing.registerSeriesLocal(businessId, "L1", fy, "LAB", "{prefix}-{series}-{seq}", 0)
            }
        }
    return billing.deviceSeries(businessId) != null
}
