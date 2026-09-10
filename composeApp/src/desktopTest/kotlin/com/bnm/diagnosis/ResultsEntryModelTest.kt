package com.bnm.diagnosis

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bnm.diagnosis.api.ApiClient
import com.bnm.diagnosis.db.AppDatabase
import com.bnm.diagnosis.lab.LabOrderTest
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabResult
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.RefRange
import com.bnm.diagnosis.lab.ResultGraph
import com.bnm.diagnosis.lab.TestParameter
import com.bnm.diagnosis.screens.lab.analyzerAlerts
import com.bnm.diagnosis.screens.lab.buildEntryGroups
import com.bnm.diagnosis.screens.lab.initialTestId
import com.bnm.diagnosis.screens.lab.nextEmptyIndex
import com.bnm.diagnosis.screens.lab.provenanceOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The results-entry rail: one group per test with counts, who filled it, the
 * analyzer's alerts, the Enter-key order, and the live results flow the screen
 * refreshes from when a machine writes while the order is open.
 */
class ResultsEntryModelTest {

    private fun res(test: String, key: String, value: String?, by: String? = null, at: String? = null) =
        LabResult(id = "$test|$key", orderId = "o", testId = test, parameterKey = key, value = value, enteredBy = by, enteredAt = at)

    private fun line(test: String, name: String) = LabOrderTest(id = "ot-$test", orderId = "o", testId = test, testName = name)

    private val tests = listOf(line("cbc", "Complete Blood Count"), line("esr", "ESR"), line("kft", "Kidney Function Test"))
    private val keys = listOf("cbc|hb", "cbc|rbc", "cbc|wbc", "esr|esr", "kft|urea", "kft|creat")
    private val results = mapOf(
        "cbc|hb" to res("cbc", "hb", "13.5", "Mindray BC-5130", "2026-09-10T04:45:00Z"),
        "cbc|rbc" to res("cbc", "rbc", "4.51", "Mindray BC-5130", "2026-09-10T04:45:00Z"),
        "cbc|wbc" to res("cbc", "wbc", "9550", "S. Kumar", "2026-09-10T05:00:00Z"),      // hand-corrected after the run
        "esr|esr" to res("esr", "esr", "12", "S. Kumar", "2026-09-10T04:50:00Z"),
        "kft|urea" to res("kft", "urea", null),
        "kft|creat" to res("kft", "creat", null),
    )
    private val graphs = listOf(
        ResultGraph("o", "cbc", "wbc", emptyList(), meta = mapOf("alerts" to "Multiple alerts", "flags" to "PLT:H;PCT:H")),
        ResultGraph("o", "cbc", "plt", emptyList(), meta = mapOf("alerts" to "Multiple alerts")),
    )

    private fun groups(instruments: Set<String> = setOf("Mindray BC-5130"), g: List<ResultGraph> = graphs) =
        buildEntryGroups(tests, mapOf("cbc" to "CBC"), keys.map { it.substringBefore('|') }, keys, results, instruments, g)

    @Test
    fun `one group per test with its rows, counts, provenance and alerts`() {
        val g = groups()
        assertEquals(listOf("cbc", "esr", "kft"), g.map { it.testId })

        val cbc = g[0]
        assertEquals(0 to 2, cbc.first to cbc.last)
        assertEquals(3 to 3, cbc.entered to cbc.total)
        assertTrue(cbc.done && cbc.analyzer)
        assertEquals("CBC", cbc.code)
        val p = cbc.provenance!!
        assertEquals("Mindray BC-5130", p.who, "the name on most rows wins")
        assertEquals(1, p.others, "the hand correction counts as one more person")
        assertTrue(p.analyzer)
        assertEquals("2026-09-10T05:00:00Z", p.at, "the latest stamp, whoever made it")
        assertEquals("Mindray BC-5130 +1 · T", p.short { "T" })
        assertEquals("Filled by Mindray BC-5130 and 1 more · T", p.long { "T" })
        assertEquals(listOf("Multiple alerts", "PLT H", "PCT H"), cbc.alerts, "alerts first, flags readable, no duplicates")

        val esr = g[1]
        assertEquals(3 to 3, esr.first to esr.last)
        assertTrue(esr.done && !esr.analyzer)
        assertEquals("Entered by S. Kumar · T", esr.provenance!!.long { "T" })
        assertEquals("", esr.code)

        val kft = g[2]
        assertEquals(4 to 5, kft.first to kft.last)
        assertEquals(0, kft.entered)
        assertFalse(kft.started)
        assertNull(kft.provenance)
        assertTrue(kft.alerts.isEmpty())

        assertEquals("kft", initialTestId(g), "open on the first test still missing a value")
    }

    @Test
    fun `an analyzer that left graphs still reads as the analyzer after a rename`() {
        val g = groups(instruments = emptySet())
        assertTrue(g[0].analyzer, "graphs prove a machine touched the CBC")
        assertFalse(g[0].provenance!!.analyzer, "but the name no longer matches an instrument")
        assertFalse(g[1].analyzer)
        assertTrue(groups(g = emptyList())[0].analyzer, "name match alone is enough too")
    }

    @Test
    fun `a test with no grid rows is left out, and an unknown row still groups`() {
        val g = buildEntryGroups(tests, emptyMap(), listOf("cbc", "kft"), listOf("cbc|hb", "kft|urea"), results, emptySet(), emptyList())
        assertEquals(listOf("cbc", "kft"), g.map { it.testId })
        assertEquals(1, g[0].total)
        assertNull(initialTestId(emptyList()))
    }

    @Test
    fun `Enter stays on the test in hand, then moves to the next test with a gap`() {
        val g = groups()
        val size = 6
        // Gaps at hb (0), wbc (2), urea (4), creat (5).
        val blank = { i: Int -> i in setOf(0, 2, 4, 5) }
        assertEquals(2, nextEmptyIndex(g, 1, size, blank), "forward within the CBC first")
        assertEquals(0, nextEmptyIndex(g, 2, size, blank), "an earlier gap in the same test before leaving it")
        // Only the KFT has gaps: from the CBC, skip the finished ESR.
        val kftOnly = { i: Int -> i in setOf(4, 5) }
        assertEquals(4, nextEmptyIndex(g, 2, size, kftOnly))
        assertEquals(5, nextEmptyIndex(g, 4, size, kftOnly))
        // Wrap: the only gap is behind us, in an earlier test.
        assertEquals(0, nextEmptyIndex(g, 5, size) { it == 0 })
        // Nothing empty anywhere: just step down, and drop focus at the end.
        assertEquals(3, nextEmptyIndex(g, 2, size) { false })
        assertNull(nextEmptyIndex(g, 5, size) { false })
        // A row outside every group (never happens, but must not throw).
        assertEquals(1, nextEmptyIndex(emptyList(), 0, size) { it == 1 })
    }

    @Test
    fun `provenance ties go to the latest stamp, blank names are ignored`() {
        val p = provenanceOf(
            listOf(
                res("t", "a", "1", "A", "2026-09-10T01:00:00Z"),
                res("t", "b", "1", "B", "2026-09-10T02:00:00Z"),
                res("t", "c", "1", " ", "2026-09-10T03:00:00Z"),
            ),
            setOf("b"),
        )!!
        assertEquals("B", p.who)
        assertTrue(p.analyzer, "instrument names match case-insensitively")
        assertEquals(1, p.others)
        assertEquals("2026-09-10T03:00:00Z", p.at)
        assertNull(provenanceOf(listOf(res("t", "a", "1", null)), emptySet()))
        assertTrue(analyzerAlerts(listOf(ResultGraph("o", "t", "wbc", emptyList()))).isEmpty())
    }

    @Test
    fun `the results flow emits when an analyzer writes, and instrument names are known`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val db = AppDatabase(driver)
        val repo = LabRepository(db, ApiClient.json)
        repo.upsertTest(LabTest(id = "t-glu", code = "GLU", name = "Glucose", price = 50.0,
            parameters = listOf(TestParameter(key = "glu", name = "Glucose", unit = "mg/dL", decimals = 0,
                ranges = listOf(RefRange(low = 70.0, high = 100.0))))))
        val patient = repo.upsertPatient(Patient(id = "pat-flow", name = "Flow Patient", sex = "M", ageYears = 40))
        val order = repo.createLabOrder(patient.id, testIds = listOf("t-glu")).getOrThrow()

        val seen = Channel<List<LabResult>>(Channel.UNLIMITED)
        val job = launch { repo.resultsForOrderFlow(order.id).collect { seen.send(it) } }
        val initial = withTimeout(5_000) { seen.receive() }
        assertTrue(initial.single().value.isNullOrBlank(), "the pre-created empty row")

        repo.enterResult(order.id, "t-glu", "glu", "90", enteredBy = "BC-5130").getOrThrow()
        val after = withTimeout(5_000) { seen.receive() }
        assertEquals("90", after.single().value)
        assertEquals("BC-5130", after.single().enteredBy)
        job.cancel()

        val now = "2026-09-10T05:00:00Z"
        db.instrumentsQueries.upsertInstrument("i1", " Mindray BC-5130 ", "mindray_hl7", "tcp", null, 115200L, 5500L, 1L, null, now, now)
        assertEquals(setOf("Mindray BC-5130"), repo.instrumentNames())
    }
}
