package com.bnm.lab

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.lab.api.ApiClient
import com.bnm.lab.db.AppDatabase
import com.bnm.lab.lab.AccessionSeat
import com.bnm.lab.lab.LabOrder
import com.bnm.lab.lab.LabRepository
import com.bnm.lab.lab.LabStatus
import com.bnm.lab.lab.LabTest
import com.bnm.lab.lab.Patient
import com.bnm.lab.lab.TestParameter
import com.bnm.lab.print.StickerRender
import com.bnm.lab.print.StickerSpec
import com.bnm.lab.sync.orderHoldingAccession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Accession numbers are printed on tubes, so two patients must never share one.
 *
 * Up to 1.2.0 every computer numbered ACC-S1-…: a two-PC offline lab, or an old
 * PC beside its replacement, put the same number on two patients' samples, and a
 * connected lab lost orders when one seat's copy replaced the other's. These
 * tests run the real allocation against an in-memory database, with the seat
 * injected so nothing reads this machine's own licence.
 */
class AccessionSeatTest {

    private val pcA = "0f6b2c3e-1111-4a2b-9c33-5d6e7f8a9b0c"
    private val pcB = "a91d7e20-2222-4c3d-8e44-6f7a8b9c0d1e"
    private val serverSeat = Regex("""S\d+""")

    private fun freshDb(): AppDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let {
        AppDatabase.Schema.create(it)
        AppDatabase(it)
    }

    private fun lab(db: AppDatabase, seat: () -> String) = runBlocking {
        LabRepository(db, ApiClient.json, accessionSeat = seat).also {
            it.upsertTest(LabTest(id = "t-glu", code = "GLU", name = "Glucose", price = 120.0,
                parameters = listOf(TestParameter(key = "glu", name = "Glucose", unit = "mg/dL"))))
        }
    }

    private fun LabRepository.register(name: String): LabOrder = runBlocking {
        val p = upsertPatient(Patient(id = "", name = name, sex = "F"))
        createLabOrder(p.id, testIds = listOf("t-glu")).getOrThrow()
    }

    /** An order another seat (or an older build) issued, as a sync pull lands it. */
    private fun AppDatabase.landOrder(id: String, accession: String) {
        labOrdersQueries.insertOrder(id, accession, "pat-elsewhere", null, null,
            LabStatus.REGISTERED, "routine", null, "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")
    }

    // ── the seat ─────────────────────────────────────────────────────────────

    @Test
    fun `two offline computers of one lab never print the same number`() {
        val seatA = AccessionSeat.resolve(standalone = true, serverSeatNo = null, deviceId = pcA)
        val seatB = AccessionSeat.resolve(standalone = true, serverSeatNo = null, deviceId = pcB)
        assertNotEquals(seatA, seatB)
        assertTrue(AccessionSeat.LEGACY !in setOf(seatA, seatB), "the shared S1 series is history only")

        val first = lab(freshDb()) { seatA }.register("Asha")
        val other = lab(freshDb()) { seatB }.register("Bala")
        assertEquals("ACC-$seatA-00001", first.accessionNo)
        assertEquals("ACC-$seatB-00001", other.accessionNo)
    }

    @Test
    fun `an install seat is stable, readable and can never be a server seat`() {
        assertEquals(AccessionSeat.install(pcA), AccessionSeat.install(pcA), "stable per install")
        // Pinned: changing the derivation moves every computer in the field to a new series.
        assertEquals("EJ2", AccessionSeat.install(pcA))

        val seats = (0 until 1000).map { AccessionSeat.install("install-$it-${it * 7919}") }
        for (s in seats) {
            assertEquals(3, s.length, s)
            assertTrue(s[0] in 'A'..'Z' && s[0] !in "ILOUS", "leads with a readable letter that is not S: $s")
            assertTrue(s.none { it in "ILOU" }, "no characters to misread: $s")
            assertTrue(!serverSeat.matches(s), "an install seat must never equal S<n>: $s")
        }
        // 1000 draws from 21,504 codes leave about 977 distinct (these ids give 969): spread, not clumped.
        assertTrue(seats.toSet().size >= 950, "install seats cluster: ${seats.toSet().size} distinct of 1000")
    }

    @Test
    fun `every seat fits the sticker the lab already bought`() {
        val seats = listOf(AccessionSeat.install(pcA), AccessionSeat.server(1), AccessionSeat.server(AccessionSeat.MAX_SERVER_SEAT))
        for (seat in seats) {
            val accession = "ACC-$seat-00042"
            assertTrue(accession.length <= 13, "$accession outgrows the 13-character barcode budget")
            val minWidth = StickerRender.minWidthMm(accession.length)
            assertTrue(StickerSpec.PRESETS.all { it.widthMm >= minWidth }, "$accession needs $minWidth mm stock")
        }
        // A seat number too long for the sticker falls back rather than printing an unscannable code.
        assertEquals(AccessionSeat.install(pcA),
            AccessionSeat.resolve(standalone = false, serverSeatNo = AccessionSeat.MAX_SERVER_SEAT + 1, deviceId = pcA))
    }

    @Test
    fun `a connected seat numbers S-n only from a seat admin-lab assigned`() {
        assertEquals("S3", AccessionSeat.resolve(standalone = false, serverSeatNo = 3, deviceId = pcA))
        // No server seat yet (today's admin-lab sends none): the install seat, never S1.
        assertEquals(AccessionSeat.install(pcA), AccessionSeat.resolve(standalone = false, serverSeatNo = null, deviceId = pcA))
        assertEquals(AccessionSeat.install(pcA), AccessionSeat.resolve(standalone = false, serverSeatNo = 0, deviceId = pcA))
        // An offline licence numbers from the install it runs on, whatever the server once said.
        assertEquals(AccessionSeat.install(pcA), AccessionSeat.resolve(standalone = true, serverSeatNo = 3, deviceId = pcA))
        // Switching edition without a server seat keeps the series the tubes already carry.
        assertEquals(
            AccessionSeat.resolve(standalone = true, serverSeatNo = null, deviceId = pcA),
            AccessionSeat.resolve(standalone = false, serverSeatNo = null, deviceId = pcA),
        )
    }

    // ── allocation ───────────────────────────────────────────────────────────

    @Test
    fun `moving off the shared S1 series renames nothing and rewinds nothing`() {
        val db = freshDb()
        var seat = AccessionSeat.LEGACY
        val repo = lab(db) { seat }
        val legacy = (1..3).map { repo.register("Pre-upgrade $it") }
        assertEquals(listOf("ACC-S1-00001", "ACC-S1-00002", "ACC-S1-00003"), legacy.map { it.accessionNo })

        // The upgrade: this computer now issues from its install seat.
        seat = AccessionSeat.install(pcA)
        assertEquals("ACC-$seat-", repo.ownAccessionSeries())
        assertEquals("ACC-$seat-00001", repo.register("Post-upgrade").accessionNo)
        for (o in legacy) {
            assertEquals(o.accessionNo, runBlocking { repo.orderById(o.id) }!!.accessionNo, "an issued accession never changes")
        }

        // And should this computer ever be handed S1 again (server seat 1), it continues, never restarts.
        seat = AccessionSeat.server(1)
        assertEquals("ACC-S1-00004", repo.register("Server seat").accessionNo)
    }

    @Test
    fun `a seat never issues a number the database already holds`() {
        val db = freshDb()
        val repo = lab(db) { "S2" }
        assertEquals("ACC-S2-00002", (1..2).map { repo.register("Local $it") }.last().accessionNo)

        // Pulled from the lab's other seats: numbers under S2 that this seat never counted
        // (an old build shared the series), plus look-alike series that must not count.
        db.landOrder("pulled-3", "ACC-S2-00003")
        db.landOrder("pulled-7", "ACC-S2-00007")
        db.landOrder("pulled-s21", "ACC-S21-00050")
        db.landOrder("pulled-s20", "ACC-S20-00099")

        // Before the lift, 00003 failed the UNIQUE accession_no, and failed again on every retry.
        assertEquals("ACC-S2-00008", repo.register("After pull").accessionNo)
        assertEquals("ACC-S3-00001", lab(db) { "S3" }.register("Other seat").accessionNo, "S21/S20 are not S2 or S3")
    }

    // ── sync ─────────────────────────────────────────────────────────────────

    @Test
    fun `a pulled order never replaces a different local order carrying the same number`() {
        val db = freshDb()
        val local = lab(db) { AccessionSeat.LEGACY }.register("Registered here")

        assertEquals(local.id, db.orderHoldingAccession("other-seats-order", local.accessionNo)?.id)
        assertNull(db.orderHoldingAccession(local.id, local.accessionNo), "its own copy updates in place")
        assertNull(db.orderHoldingAccession("other-seats-order", "ACC-S1-00002"), "a free number lands")

        // Why the guard exists: the raw pull upsert deletes the local order to make room.
        db.labOrdersQueries.upsertOrder("other-seats-order", local.accessionNo, "pat-elsewhere", null, null,
            LabStatus.REGISTERED, "routine", null, "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z", null, null, null)
        assertNull(db.labOrdersQueries.byId(local.id).executeAsOneOrNull(), "INSERT OR REPLACE drops the holder")
    }
}
