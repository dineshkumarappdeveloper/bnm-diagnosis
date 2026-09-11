package com.bnm.lab.sync

import com.bnm.lab.api.MasterCatalogTest
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.TestParameter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * L4 — the GLOBAL master test catalog → the local test catalog.
 *
 * WHY THIS EXISTS SEPARATELY FROM [PlatformCatalogImporter]: that one imports a
 * BUSINESS's `products`, which an OFFLINE (standalone) licence does not have —
 * it has no business at all. Without this an offline lab is stuck on the
 * bundled ~40-test starter catalog with no way to reach the national set.
 *
 * Rules of the road:
 *  - INSERT-ONLY, keyed on `code`. An existing code is the LAB's row — its
 *    price, its edited reference ranges, its decision to deactivate a test.
 *    Overwriting clinical data a lab has adopted is not a trade-off we make,
 *    so a re-pull is always safe to run and only ever fills gaps.
 *  - Price lands at 0. The master catalog carries none on purpose — reference
 *    data describes MEDICINE, not what a lab charges — and the catalog screen
 *    is where the lab sets its own.
 *  - `platform_product_id` stays NULL. These did not come from a business
 *    product, so the connected sync's deactivate sweep (which reads
 *    `platform_product_id IS NOT NULL`) must never see them: a lab that later
 *    moves to the connected edition keeps its master-pulled tests.
 *  - `parameters` arrives in the canonical LIMS shape and is stored verbatim,
 *    so pickRange/computeFlag (sex + age-band selection) applies unchanged and
 *    a RANGELESS analyte stays a real parameter with a unit and no bounds.
 */
class MasterCatalogImporter(private val db: AppDatabase, private val json: Json) {

    private val tQ get() = db.testCatalogQueries
    private val paramsSerializer = ListSerializer(TestParameter.serializer())

    /** What one pull did, so the screen can say it plainly. */
    data class Outcome(val added: Int, val skipped: Int) {
        val total: Int get() = added + skipped
    }

    /**
     * Apply one full master-catalog pull. ONE transaction: either every new
     * test lands or none does, so a failure part-way cannot leave the catalog
     * holding a sliced-off fragment of the national set.
     */
    fun apply(pulled: List<MasterCatalogTest>): Outcome {
        var added = 0
        var skipped = 0
        db.transaction {
            // Append below whatever the lab already has rather than interleaving
            // with the starter set — the catalog screen orders by sort_order.
            var order = tQ.listAllTests().executeAsList().maxOfOrNull { it.sort_order } ?: 0L
            for (t in pulled) {
                val code = t.code.trim().uppercase()
                if (code.isBlank() || t.name.isBlank()) { skipped++; continue }
                if (tQ.testByCode(code).executeAsOneOrNull() != null) { skipped++; continue }
                // upsertTest is INSERT OR REPLACE, so a taken id would CLOBBER
                // that row. It can be taken while the code is free if the lab
                // renamed a previously-pulled test's code — skip rather than
                // overwrite their edit.
                val id = "master-" + code.lowercase()
                if (tQ.testById(id).executeAsOneOrNull() != null) { skipped++; continue }
                order += 1
                tQ.upsertTest(
                    id,
                    code,
                    t.name,
                    t.category?.takeIf { it.isNotBlank() },
                    0.0,
                    t.sampleType.takeIf { it.isNotBlank() } ?: "blood",
                    t.method,
                    1L,
                    order,
                    json.encodeToString(paramsSerializer, t.parameters),
                    null, null, null, null,
                    t.tatHours,
                    null,
                )
                added++
            }
        }
        return Outcome(added, skipped)
    }
}
