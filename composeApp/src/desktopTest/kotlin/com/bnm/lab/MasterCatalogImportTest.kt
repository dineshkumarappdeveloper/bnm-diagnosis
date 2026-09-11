package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.api.MasterCatalogTest
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.RefRange
import com.bnm.lab.lab.TestParameter
import com.bnm.lab.sync.MasterCatalogImporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L4 — the GLOBAL master test catalog → the local test catalog, against a REAL
 * in-memory SQLDelight DB. This is the OFFLINE edition's only route to the
 * national test set, so what it must never do matters as much as what it does:
 * never overwrite a price or a reference range the lab has adopted.
 *
 * Payloads are decoded from the literal wire JSON `admin-lab /master-catalog`
 * emits, so the @SerialName mapping is under test too.
 */
class MasterCatalogImportTest {

    private val json = ApiClient.json

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return AppDatabase(driver)
    }

    private fun parse(payload: String): List<MasterCatalogTest> =
        json.decodeFromString(ListSerializer(MasterCatalogTest.serializer()), payload)

    /**
     * Two real rows as the endpoint sends them. AEC carries a numeric adult
     * band; the peripheral smear is DESCRIPTIVE — a parameter with neither unit
     * nor range, which is exactly the shape that gets silently dropped by a
     * pipeline that groups reference ranges by analyte name.
     */
    private val wire = """
      [
        {"code":"AECA","name":"Absolute Eosinophil Count (AEC)","category":"Haematology",
         "sample_type":"blood","method":null,"tat_hours":6.0,"sort_order":12,
         "parameters":[{"key":"absolute_eosinophil_count","name":"Absolute Eosinophil Count",
           "unit":"/µL","decimals":1,
           "ranges":[{"sex":null,"ageMinY":15.0,"ageMaxY":120.0,"low":40.0,"high":440.0,
                      "criticalLow":null,"criticalHigh":null,"text":null}]}]},
        {"code":"PSMR","name":"Peripheral Smear","category":"Haematology",
         "sample_type":"blood","method":"Leishman stain","tat_hours":null,"sort_order":13,
         "parameters":[{"key":"impression","name":"Impression","unit":null,"decimals":0,
                        "ranges":[]}]}
      ]
    """.trimIndent()

    @Test
    fun `pull inserts master tests with parameters intact`() = runBlocking {
        val db = freshDb()
        val outcome = MasterCatalogImporter(db, json).apply(parse(wire))
        assertEquals(2, outcome.added)
        assertEquals(0, outcome.skipped)

        val repo = LabRepository(db, json)
        val aec = repo.testByCode("AECA")
        assertNotNull(aec)
        assertEquals("Absolute Eosinophil Count (AEC)", aec.name)
        assertEquals("Haematology", aec.category)
        assertEquals("blood", aec.sampleType)
        assertEquals(6.0, aec.tatHours)
        // The master catalog carries no price on purpose — the lab sets its own.
        assertEquals(0.0, aec.price)
        val p = aec.parameters.single()
        assertEquals("/µL", p.unit)
        assertEquals(40.0, p.ranges.single().low)
        assertEquals(440.0, p.ranges.single().high)
        assertEquals(15.0, p.ranges.single().ageMinY)
    }

    @Test
    fun `a rangeless analyte survives as a real parameter`() = runBlocking {
        val db = freshDb()
        MasterCatalogImporter(db, json).apply(parse(wire))
        val smear = LabRepository(db, json).testByCode("PSMR")
        assertNotNull(smear)
        // It must still be a parameter — result entry has nothing to show otherwise.
        val p = smear.parameters.single()
        assertEquals("Impression", p.name)
        assertNull(p.unit)
        assertTrue(p.ranges.isEmpty())
    }

    @Test
    fun `an existing code is never overwritten — price and ranges survive`() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, json)
        // The lab already has AECA: its own price, and a range it adopted after
        // checking its own analyser insert.
        repo.upsertTest(
            LabTest(
                id = "mine-aec", code = "AECA", name = "My AEC", price = 250.0,
                parameters = listOf(
                    TestParameter("aec", "AEC", unit = "cells/µL", decimals = 0,
                        ranges = listOf(RefRange(low = 50.0, high = 500.0))),
                ),
            ),
        )
        val outcome = MasterCatalogImporter(db, json).apply(parse(wire))
        assertEquals(1, outcome.added)    // only the smear
        assertEquals(1, outcome.skipped)  // AECA left alone

        val kept = repo.testByCode("AECA")
        assertNotNull(kept)
        assertEquals("mine-aec", kept.id)
        assertEquals("My AEC", kept.name)
        assertEquals(250.0, kept.price)
        assertEquals(500.0, kept.parameters.single().ranges.single().high)
    }

    @Test
    fun `re-pulling is idempotent`() = runBlocking {
        val db = freshDb()
        val importer = MasterCatalogImporter(db, json)
        assertEquals(2, importer.apply(parse(wire)).added)
        val second = importer.apply(parse(wire))
        assertEquals(0, second.added)
        assertEquals(2, second.skipped)
        assertEquals(2L, LabRepository(db, json).countTests())
    }

    @Test
    fun `master tests carry no platform origin so the connected sweep never deactivates them`() = runBlocking {
        val db = freshDb()
        MasterCatalogImporter(db, json).apply(parse(wire))
        // The platform sweep reads platform_product_id IS NOT NULL. A master
        // test picked up there would be deactivated the first time a lab moved
        // to the connected edition, because no product will ever match it.
        assertTrue(db.testCatalogQueries.listPlatformTests().executeAsList().isEmpty())
    }

    @Test
    fun `a renamed code does not let the import clobber the lab's row`() = runBlocking {
        val db = freshDb()
        val repo = LabRepository(db, json)
        MasterCatalogImporter(db, json).apply(parse(wire))
        // Lab renames the pulled test's code; its id stays "master-aeca".
        val pulled = repo.testByCode("AECA")!!
        repo.upsertTest(pulled.copy(code = "AEC2", name = "Renamed", price = 175.0))
        // AECA is free again, but its id is taken — upsert is INSERT OR REPLACE,
        // so an unguarded import would overwrite the renamed row.
        val outcome = MasterCatalogImporter(db, json).apply(parse(wire))
        assertEquals(0, outcome.added)
        val renamed = repo.testByCode("AEC2")
        assertNotNull(renamed)
        assertEquals("Renamed", renamed.name)
        assertEquals(175.0, renamed.price)
    }
}
