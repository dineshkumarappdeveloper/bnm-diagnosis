package com.bnm.lab.sync

import com.bnm.lab.api.PlatformLabTest
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.RefRange
import com.bnm.lab.lab.TestParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * L3 — platform product catalog → the local test catalog.
 *
 * Businesses author lab tests in BusinessStudio/BNMAdmin as SERVICE products
 * carrying a `lab_config` jsonb. The sync engine pulls the linked business's
 * whole list each sweep ([LabSyncEngine] fingerprints it — same whole-catalog
 * LWW idiom the local test/panel push uses) and this importer upserts it into
 * `lab_tests`, keyed by the `platform_product_id` origin marker so re-pulls
 * UPDATE and never duplicate.
 *
 * Rules of the road:
 *  - Locally-authored tests (no origin marker) are NEVER touched.
 *  - An imported test whose product disappears (deleted, lab_config cleared)
 *    is DEACTIVATED, not deleted — history keeps rendering, and reappearance
 *    reactivates it.
 *  - `lab_config` is stored VERBATIM on the row (`platform_json`) so fields
 *    the local schema doesn't model (per-range notes, consumables BOM) survive
 *    losslessly; the mapped columns are a projection, not the truth.
 *  - Reference ranges flow into the SAME `parameters_json` shape local tests
 *    use, so [com.bnm.lab.lab.LabRepository.enterResult]'s pickRange/
 *    computeFlag brain (sex + age-band selection) applies unchanged.
 */
class PlatformCatalogImporter(private val db: AppDatabase, private val json: Json) {

    private val tQ get() = db.testCatalogQueries
    private val paramsSerializer = ListSerializer(TestParameter.serializer())

    /**
     * Apply one full platform pull. ONE transaction: either the local catalog
     * reflects the pulled list or it is untouched — a half-applied import
     * would poison the caller's fingerprint short-circuit.
     */
    fun apply(pulled: List<PlatformLabTest>) {
        db.transaction {
            val seenIds = HashSet<String>()
            for (p in pulled) {
                if (p.id.isBlank()) continue
                seenIds += p.id
                val cfg = decodeConfig(p.labConfig)
                // Adoption: a product PROMOTED from this lab's own catalog names
                // its origin row — claim that row (keeping its id/code, which
                // orders and results reference) instead of minting a plat-* twin.
                val existing = tQ.testByPlatformProduct(p.id).executeAsOneOrNull()
                    ?: cfg?.sourceLabTestId?.takeIf { it.isNotBlank() }?.let { sid ->
                        tQ.testById(sid).executeAsOneOrNull()?.takeIf { it.platform_product_id == null }
                    }
                val outsourced = cfg?.fulfillment == "outsourced"
                val mapped = LabTest(
                    // Deterministic id: two seats importing concurrently mint the
                    // SAME row and converge instead of duplicating via E_TEST sync.
                    id = existing?.id ?: "plat-${p.id}",
                    // An established code is an identity (search, EMR code match)
                    // — never re-derived once assigned.
                    code = existing?.code ?: deriveCode(p.name, p.id),
                    name = p.name,
                    // The lab's own choice wins (it may have re-filed a test);
                    // otherwise take the platform's discipline. Before the
                    // server sent one, every imported test landed uncategorised.
                    category = existing?.category?.takeIf { it.isNotBlank() } ?: p.category?.takeIf { it.isNotBlank() },
                    price = p.sellingPrice ?: existing?.price ?: 0.0,
                    sampleType = cfg?.sampleType?.takeIf { it.isNotBlank() } ?: "blood",
                    method = cfg?.method,
                    // Import (re)activates: the product qualifying on the platform
                    // IS the platform saying "this test is offered".
                    active = true,
                    sortOrder = existing?.sort_order?.toInt() ?: 0,
                    parameters = mapParameters(p.name, cfg),
                    platformProductId = p.id,
                    fulfillment = if (outsourced) "outsourced" else "in_house",
                    outsourcePartner = if (outsourced) cfg?.outsource?.partnerName else null,
                    outsourceCost = if (outsourced) cfg?.outsource?.cost else null,
                    tatHours = cfg?.tatHours,
                    platformJson = p.labConfig?.toString(),
                )
                tQ.upsertTest(mapped.id, mapped.code, mapped.name, mapped.category, mapped.price,
                    mapped.sampleType, mapped.method, if (mapped.active) 1L else 0L,
                    mapped.sortOrder.toLong(), json.encodeToString(paramsSerializer, mapped.parameters),
                    mapped.platformProductId, mapped.fulfillment, mapped.outsourcePartner,
                    mapped.outsourceCost, mapped.tatHours, mapped.platformJson)
            }
            // Disappearance = deactivate, never delete: past orders keep their
            // catalog entry and a returning product simply flips it back on.
            for (row in tQ.listPlatformTests().executeAsList()) {
                if (row.platform_product_id !in seenIds && row.active == 1L) {
                    tQ.setTestActive(0L, row.id)
                }
            }
        }
    }

    /**
     * Order code for a NEW import: initials of the name ("Complete Blood
     * Count" → CBC), falling back to the product id when taken by a DIFFERENT
     * test. Deterministic in (name, productId) so concurrent seats agree.
     */
    private fun deriveCode(name: String, productId: String): String {
        val words = name.split(' ', '-', '/', '(', ')', ',').filter { it.isNotBlank() }
        val initials = words.mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }
            .joinToString("").uppercase()
        val base = (if (initials.length >= 2) initials.take(6)
            else name.filter { it.isLetterOrDigit() }.uppercase().take(6))
            .ifBlank { "TEST" }
        if (codeFree(base, productId)) return base
        // UNIQUE(code) + INSERT OR REPLACE would silently swallow whichever row
        // already owns the code — fall back to id-derived codes instead.
        var tail = 5
        while (tail <= productId.length) {
            val candidate = "P" + productId.takeLast(tail).uppercase()
            if (codeFree(candidate, productId)) return candidate
            tail += 3
        }
        return "P" + productId.uppercase()
    }

    /** True when [code] is unused, or used by this product's own row. */
    private fun codeFree(code: String, productId: String): Boolean {
        val owner = tQ.testByCode(code).executeAsOneOrNull() ?: return true
        return owner.platform_product_id == productId
    }

    private fun decodeConfig(cfg: JsonObject?): PlatformLabConfig? =
        cfg?.let { runCatching { json.decodeFromJsonElement(PlatformLabConfig.serializer(), it) }.getOrNull() }

    /**
     * `reference_ranges` → the local parameters shape. Each distinct analyte
     * becomes ONE [TestParameter] (first-seen order preserved) whose ranges
     * are that analyte's bands: gender any→null / male→M / female→F, age
     * bounds straight across — exactly what pickRange's specificity rules
     * expect. Per-range `notes` has no local slot; it survives verbatim in
     * `platform_json`. A config with no ranges still yields one qualitative
     * "Result" parameter so result entry has somewhere to type.
     */
    private fun mapParameters(testName: String, cfg: PlatformLabConfig?): List<TestParameter> {
        val ranges = cfg?.referenceRanges.orEmpty()
        if (ranges.isEmpty()) return listOf(TestParameter(key = "result", name = testName, decimals = 1))
        val byAnalyte = LinkedHashMap<String, MutableList<PlatformRefRange>>()
        for (r in ranges) byAnalyte.getOrPut(r.analyte.trim().ifBlank { "Result" }) { mutableListOf() } += r
        val usedKeys = HashSet<String>()
        return byAnalyte.map { (analyte, bands) ->
            var key = slug(analyte)
            var n = 2
            while (!usedKeys.add(key)) key = "${slug(analyte)}_${n++}"
            TestParameter(
                key = key,
                name = analyte,
                unit = bands.firstNotNullOfOrNull { it.unit?.takeIf { u -> u.isNotBlank() } },
                decimals = 1,
                ranges = bands.map { b ->
                    RefRange(
                        sex = when (b.gender?.lowercase()) {
                            "male" -> "M"; "female" -> "F"; else -> null // 'any'/absent = both
                        },
                        ageMinY = b.ageMin, ageMaxY = b.ageMax,
                        low = b.low, high = b.high,
                        criticalLow = b.criticalLow, criticalHigh = b.criticalHigh,
                        // NB: local `text` is the QUALITATIVE expected value —
                        // setting it flips the band to qualitative flagging.
                        // Platform `notes` stays in platform_json only.
                    )
                },
            )
        }
    }

    private fun slug(s: String): String = s.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        .trim('_').ifBlank { "value" }

    companion object {
        /**
         * Content fingerprint of one platform pull (rows sorted by id — the
         * server already orders, but never trust wire order for a hash). Same
         * FNV-ish shape as the engine's catalogFingerprint.
         */
        fun fingerprint(json: Json, pulled: List<PlatformLabTest>): Long {
            val s = json.encodeToString(
                ListSerializer(PlatformLabTest.serializer()), pulled.sortedBy { it.id })
            var h = 1125899906842597L
            for (c in s) h = 31 * h + c.code
            return h
        }
    }
}

// ── Typed view of `products.lab_config` (snake_case wire → @SerialName) ──────
// Every field defaults: a platform that grows the config never breaks parsing,
// and whatever this view doesn't model still lands in `platform_json`.

@Serializable
data class PlatformLabConfig(
    @SerialName("sample_type") val sampleType: String? = null,
    val method: String? = null,
    @SerialName("tat_hours") val tatHours: Double? = null,
    @SerialName("reference_ranges") val referenceRanges: List<PlatformRefRange> = emptyList(),
    val consumables: List<PlatformConsumable> = emptyList(),
    /** 'in_house' | 'outsourced' */
    val fulfillment: String? = null,
    val outsource: PlatformOutsource? = null,
    /** Set when this product was PROMOTED from a lab's own local test (the
     *  reverse bridge): the local lab_tests.id it came from. Lets the import
     *  ADOPT the original row instead of minting a plat-* duplicate. */
    @SerialName("source_lab_test_id") val sourceLabTestId: String? = null,
)

@Serializable
data class PlatformRefRange(
    val analyte: String = "",
    val low: Double? = null,
    val high: Double? = null,
    val unit: String? = null,
    /** 'any' | 'male' | 'female' */
    val gender: String? = null,
    @SerialName("age_min") val ageMin: Double? = null,
    @SerialName("age_max") val ageMax: Double? = null,
    val notes: String? = null,
    // Extra keys carried by promoted tests (the platform editors don't author
    // these yet) — round-trips the LIMS's critical bounds through promotion.
    @SerialName("critical_low") val criticalLow: Double? = null,
    @SerialName("critical_high") val criticalHigh: Double? = null,
)

@Serializable
data class PlatformConsumable(
    @SerialName("product_id") val productId: String = "",
    val qty: Double = 0.0,
)

@Serializable
data class PlatformOutsource(
    @SerialName("partner_name") val partnerName: String? = null,
    val cost: Double? = null,
)
