package com.bnm.lab.remote

import com.bnm.lab.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Instant

/**
 * Support history on the lab PC — the `support_sessions` / `support_audit`
 * tables (SupportAudit.sq). Rows outlive a tenant switch on purpose: they say
 * what BNM did on THIS computer, and the owner is entitled to that record
 * whatever licence the machine carries next.
 */
class SqlSupportAuditStore(private val db: AppDatabase) : SupportAuditStore {

    private val q get() = db.supportAuditQueries

    override suspend fun sessionStarted(session: SupportSession): Unit = withContext(Dispatchers.Default) {
        val consent = buildJsonObject {
            put("analyzer_data", JsonPrimitive(session.consent.analyzerData))
            put("records", JsonPrimitive(session.consent.records))
            put("screen", JsonPrimitive(session.consent.screen))
        }
        q.insertSession(
            session.id, iso(session.startedAtMs), session.durationS, Json.encodeToString(consent),
            session.startedBy.staffId, session.startedBy.staffName,
        )
    }

    override suspend fun sessionEnded(sessionId: String, endedAtMs: Long, reason: String): Unit = withContext(Dispatchers.Default) {
        q.endSession(iso(endedAtMs), reason.take(200), sessionId)
    }

    override suspend fun append(row: SupportAuditRow): Unit = withContext(Dispatchers.Default) {
        q.insertAudit(
            row.id, row.sessionId, iso(row.atMs), row.tool, row.summary.take(200),
            row.outcome.name.lowercase(), row.ms, row.startedBy,
        )
    }

    override suspend fun recent(limit: Int): List<SupportAuditRow> = withContext(Dispatchers.Default) {
        q.recentAudit(limit.coerceAtLeast(1).toLong()).executeAsList().map { r ->
            SupportAuditRow(
                id = r.id, sessionId = r.session_id, atMs = ms(r.at), tool = r.tool, summary = r.summary,
                outcome = runCatching { SupportAuditRow.Outcome.valueOf(r.outcome.uppercase()) }
                    .getOrDefault(SupportAuditRow.Outcome.FAILED),
                ms = r.ms, startedBy = r.started_by,
            )
        }
    }

    private fun iso(epochMs: Long): String = Instant.fromEpochMilliseconds(epochMs).toString()
    private fun ms(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0L)
}
