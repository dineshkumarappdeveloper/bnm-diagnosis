package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.api.PlatformLabTest
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.sync.PlatformCatalogImporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L3 — platform product catalog → local test catalog, end to end against a
 * REAL in-memory SQLDelight DB: import maps lab_config (ranges incl. sex/age
 * bands, outsourcing, TAT) onto the local schema; re-imports UPDATE (never
 * duplicate); disappearance DEACTIVATES; locally-authored tests are untouched;
 * and imported ranges flow through the SAME enterResult flag brain local
 * tests use. Payloads are decoded from the literal wire JSON the `admin-lab`
 * /platform-tests endpoint emits, so the @SerialName mapping is under test too.
 */
class PlatformCatalogImportTest {

    private val json = ApiClient.json

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return AppDatabase(driver)
    }

    private fun parse(payload: String): List<PlatformLabTest> =
        json.decodeFromString(ListSerializer(PlatformLabTest.serializer()), payload)

    /** The wire shape of one CBC-ish service product with sex+age banded ranges. */
    private val cbcJson = """
      {"id":"prod-cbc-1","name":"Complete Blood Count","selling_price":350.0,"currency":"INR",
       "updated_at":"2026-08-30T10:00:00Z","seq":11,
       "lab_config":{
         "sample_type":"blood","method":"Automated analyzer","tat_hours":6,
         "reference_ranges":[
           {"analyte":"Haemoglobin","low":13.0,"high":17.0,"unit":"g/dL","gender":"male","age_min":18,"age_max":null,"notes":null},
           {"analyte":"Haemoglobin","low":12.0,"high":15.5,"unit":"g/dL","gender":"female","age_min":18,"age_max":null,"notes":null},
           {"analyte":"Haemoglobin","low":11.0,"high":14.0,"unit":"g/dL","gender":"any","age_min":0,"age_max":12,"notes":"paediatric"},
           {"analyte":"WBC","low":4.0,"high":11.0,"unit":"10^3/uL","gender":"any","age_min":null,"age_max":null,"notes":null}
         ],
         "consumables":[{"product_id":"prod-edta-tube","qty":1}],
         "fulfillment":"in_house","outsource":null}}
    """.trimIndent()

    /** An OUTSOURCED single-analyte test with partner + cost. */
    private val vitDJson = """
      {"id":"prod-vitd-1","name":"Vitamin D (25-OH)","selling_price":1200.0,"currency":"INR",
       "updated_at":"2026-08-30T10:00:00Z","seq":12,
       "lab_config":{
         "sample_type":"serum","method":"CLIA","tat_hours":48,
         "reference_ranges":[
           {"analyte":"Vitamin D","low":30.0,"high":100.0,"unit":"ng/mL","gender":"any","age_min":null,"age_max":null,"notes":null}
         ],
         "consumables":[],
         "fulfillment":"outsourced",
         "outsource":{"partner_name":"Metro Reference Labs","cost":650.0}}}
    """.trimIndent()

    @Test
    fun import_maps_updates_deactivates_and_spares_local_tests() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, json)
        val importer = PlatformCatalogImporter(db, json)

        // A locally-authored test that must never be touched by imports.
        repo.upsertTest(LabTest(id = "t-local", code = "LOC1", name = "Local Special", price = 99.0))

        importer.apply(parse("[$cbcJson,$vitDJson]"))

        // ── mapping ──
        val cbc = repo.testById("plat-prod-cbc-1")
        assertNotNull(cbc, "import should mint the deterministic plat-<productId> row")
        assertEquals("Complete Blood Count", cbc.name)
        assertEquals(350.0, cbc.price)
        assertEquals("blood", cbc.sampleType)
        assertEquals("Automated analyzer", cbc.method)
        assertEquals(6.0, cbc.tatHours)
        assertEquals("in_house", cbc.fulfillment)
        assertEquals("prod-cbc-1", cbc.platformProductId)
        assertTrue(cbc.active)
        assertNotNull(cbc.platformJson, "lab_config must be preserved verbatim")
        assertTrue(cbc.platformJson!!.contains("paediatric"), "unmapped fields (notes) survive losslessly")
        assertTrue(cbc.platformJson!!.contains("prod-edta-tube"), "consumables BOM survives losslessly")
        // Analytes grouped: Haemoglobin (3 bands) + WBC (1 band).
        assertEquals(listOf("Haemoglobin", "WBC"), cbc.parameters.map { it.name })
        assertEquals(3, cbc.parameters[0].ranges.size)
        assertEquals("g/dL", cbc.parameters[0].unit)
        assertEquals("M", cbc.parameters[0].ranges[0].sex)
        assertEquals("F", cbc.parameters[0].ranges[1].sex)
        assertNull(cbc.parameters[0].ranges[2].sex, "'any' maps to a sex-neutral range")
        assertEquals(0.0, cbc.parameters[0].ranges[2].ageMinY)
        assertEquals(12.0, cbc.parameters[0].ranges[2].ageMaxY)

        val vitd = repo.testById("plat-prod-vitd-1")
        assertNotNull(vitd)
        assertTrue(vitd.isOutsourced)
        assertEquals("Metro Reference Labs", vitd.outsourcePartner)
        assertEquals(650.0, vitd.outsourceCost, "partner cost persisted (margin reporting is L4)")

        // ── re-import UPDATES, never duplicates ──
        val before = repo.countTests()
        importer.apply(parse("[${cbcJson.replace("350.0", "425.0")},$vitDJson]"))
        assertEquals(before, repo.countTests(), "re-pull must not duplicate")
        assertEquals(425.0, repo.testById("plat-prod-cbc-1")!!.price)

        // ── disappearance DEACTIVATES (never deletes); reappearance reactivates ──
        importer.apply(parse("[$cbcJson]"))
        val goneVitd = repo.testById("plat-prod-vitd-1")
        assertNotNull(goneVitd, "vanished platform test stays on disk")
        assertFalse(goneVitd.active, "…but is deactivated")
        importer.apply(parse("[$cbcJson,$vitDJson]"))
        assertTrue(repo.testById("plat-prod-vitd-1")!!.active, "reappearance reactivates")

        // ── locally-authored test untouched throughout ──
        val local = repo.testById("t-local")!!
        assertEquals("Local Special", local.name)
        assertEquals(99.0, local.price)
        assertTrue(local.active)
        assertNull(local.platformProductId)

        // ── fingerprint: equal payloads agree, changed payloads differ ──
        val a = PlatformCatalogImporter.fingerprint(json, parse("[$cbcJson,$vitDJson]"))
        val b = PlatformCatalogImporter.fingerprint(json, parse("[$vitDJson,$cbcJson]"))
        assertEquals(a, b, "fingerprint is order-independent")
        assertNotEquals(a, PlatformCatalogImporter.fingerprint(json, parse("[$cbcJson]")))
    }

    @Test
    fun imported_ranges_flow_through_the_same_flag_brain() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, json)
        PlatformCatalogImporter(db, json).apply(parse("[$cbcJson]"))

        // Adult female: Hb 11.5 sits below her 12.0–15.5 band → L.
        val woman = repo.upsertPatient(Patient(id = "pat-f", name = "Asha", sex = "F", ageYears = 30))
        val orderF = repo.createLabOrder(woman.id, testIds = listOf("plat-prod-cbc-1")).getOrThrow()
        val hbF = repo.enterResult(orderF.id, "plat-prod-cbc-1", "haemoglobin", "11.5").getOrThrow()
        assertEquals("L", hbF.flag, "female band (12.0–15.5) must be picked for an adult woman")

        // Five-year-old: the SAME 11.5 is inside the 11.0–14.0 paediatric band → N.
        // (Age-banded beats sex-banded — the pickRange specificity rule.)
        val child = repo.upsertPatient(Patient(id = "pat-c", name = "Kabir", sex = "M", ageYears = 5))
        val orderC = repo.createLabOrder(child.id, testIds = listOf("plat-prod-cbc-1")).getOrThrow()
        val hbC = repo.enterResult(orderC.id, "plat-prod-cbc-1", "haemoglobin", "11.5").getOrThrow()
        assertEquals("N", hbC.flag, "paediatric band (11.0–14.0) must be picked for a 5-year-old")

        // Adult male: 11.5 under 13.0 → L, and his ref display quotes HIS band.
        val man = repo.upsertPatient(Patient(id = "pat-m", name = "Ravi", sex = "M", ageYears = 40))
        val orderM = repo.createLabOrder(man.id, testIds = listOf("plat-prod-cbc-1")).getOrThrow()
        val hbM = repo.enterResult(orderM.id, "plat-prod-cbc-1", "haemoglobin", "11.5").getOrThrow()
        assertEquals("L", hbM.flag)
        assertEquals("13 - 17", hbM.refDisplay, "the male band's own bounds print on his row")
    }

    @Test
    fun outsourced_sent_to_partner_stamp() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, json)
        PlatformCatalogImporter(db, json).apply(parse("[$vitDJson]"))

        val pat = repo.upsertPatient(Patient(id = "pat-o", name = "Meera", sex = "F", ageYears = 41))
        val order = repo.createLabOrder(pat.id, testIds = listOf("plat-prod-vitd-1")).getOrThrow()
        assertNull(repo.orderTests(order.id).single().sentToPartnerAt)

        repo.markSentToPartner(order.id, "plat-prod-vitd-1").getOrThrow()
        val stamped = repo.orderTests(order.id).single().sentToPartnerAt
        assertNotNull(stamped, "marking must stamp the timestamp")

        // Idempotent: the first stamp wins.
        repo.markSentToPartner(order.id, "plat-prod-vitd-1").getOrThrow()
        assertEquals(stamped, repo.orderTests(order.id).single().sentToPartnerAt)

        // The stamp bumped the order's LWW clock so it reaches other seats.
        assertTrue(repo.orderById(order.id)!!.updatedAt >= order.updatedAt)

        // The pipeline itself is untouched — result entry works exactly as before.
        val res = repo.enterResult(order.id, "plat-prod-vitd-1", "vitamin_d", "22").getOrThrow()
        assertEquals("L", res.flag, "partner-transcribed values flag like bench values")
    }

    @Test
    fun code_derivation_never_steals_an_existing_code() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, json)

        // The lab already owns CBC. INSERT OR REPLACE would silently swallow it
        // on a code collision, so the importer must fall back for the new row.
        repo.upsertTest(LabTest(id = "t-mine", code = "CBC", name = "My Own CBC", price = 250.0))
        PlatformCatalogImporter(db, json).apply(parse("[$cbcJson]"))

        val mine = repo.testById("t-mine")
        assertNotNull(mine, "local test must survive the import")
        assertEquals("CBC", mine.code)
        val imported = repo.testById("plat-prod-cbc-1")!!
        assertNotEquals("CBC", imported.code, "imported copy takes a fallback code")
        assertTrue(imported.code.isNotBlank())

        // A repeat import keeps the fallback code stable (identity, not re-derived).
        PlatformCatalogImporter(db, json).apply(parse("[$cbcJson]"))
        assertEquals(imported.code, repo.testById("plat-prod-cbc-1")!!.code)
    }

    @Test
    fun config_without_ranges_still_yields_an_enterable_parameter() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, json)
        val bare = """
          {"id":"prod-bare-1","name":"Culture & Sensitivity","selling_price":500.0,"currency":"INR",
           "updated_at":null,"seq":1,
           "lab_config":{"sample_type":"swab","method":null,"tat_hours":null,
             "reference_ranges":[],"consumables":[],"fulfillment":"in_house","outsource":null}}
        """.trimIndent()
        PlatformCatalogImporter(db, json).apply(parse("[$bare]"))
        val t = repo.testById("plat-prod-bare-1")!!
        assertEquals(1, t.parameters.size, "no ranges → one qualitative Result parameter")
        assertEquals("result", t.parameters[0].key)

        val pat = repo.upsertPatient(Patient(id = "pat-b", name = "Test", sex = "O"))
        val order = repo.createLabOrder(pat.id, testIds = listOf(t.id)).getOrThrow()
        val res = repo.enterResult(order.id, t.id, "result", "No growth after 48h").getOrThrow()
        assertNull(res.flag, "qualitative entry with no range stays unflagged")
    }
}
