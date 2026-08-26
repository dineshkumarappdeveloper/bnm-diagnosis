package com.bnm.diagnosis.sync

import kotlinx.serialization.Serializable

/**
 * Wire shapes for the round-1 tables that the sync spine carries.
 *
 * They live here rather than in LabModels because nothing outside sync needs
 * them: the repository works against the SQLDelight rows directly, and these
 * exist only to give `lab_entities.json` a stable schema.
 *
 * All fields are nullable-or-defaulted on purpose — the pull side decodes with
 * `ignoreUnknownKeys = true`, so adding a field later stays backward AND forward
 * compatible with seats running an older build.
 */

/**
 * One per-(referrer, test) commission override.
 *
 * The sync spine keys every row on a single string id, but this table's key is a
 * pair — so the push encodes it as `"<referrerId>|<testId>"` and applyRow reads
 * the parts back out of the document rather than parsing that id. Keeping the
 * authoritative values inside the doc means the id format can change later
 * without stranding rows.
 */
@Serializable
data class CommissionRateDoc(
    val referrerId: String,
    val testId: String,
    val commissionPct: Double,
    val updatedAt: String? = null,
)

/** One settled commission payout for a period. */
@Serializable
data class PayoutDoc(
    val id: String,
    val referrerId: String,
    val periodFrom: String,
    val periodTo: String,
    val gross: Double,
    val payable: Double,
    val paidAmount: Double = 0.0,
    val paidAt: String? = null,
    val method: String? = null,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String? = null,
)

/**
 * One lab-wide setting. The commission base lives at
 * `LabRepository.SETTING_BASE_COMMISSION`.
 *
 * Last-writer-wins with no comparison: settings are a handful of scalar knobs an
 * owner changes deliberately, and the alternative (a stamp comparison per key)
 * would strand a key whose local row predates the column.
 */
@Serializable
data class SettingDoc(
    val key: String,
    val value: String,
    val updatedAt: String? = null,
)
