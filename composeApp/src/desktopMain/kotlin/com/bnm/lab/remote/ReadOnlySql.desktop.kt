package com.bnm.lab.remote

import com.bnm.lab.db.CHAT_DB_NAME
import com.bnm.lab.db.appDataDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.Statement
import java.sql.Types
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Where the blocking query actually runs — deliberately NOT a child of the
 * call that asked for it. `RemoteSupportService` awaits `tools/call` inline in
 * the session job, so the owner's "End" (or expiry) cancels the caller; a
 * watchdog launched inside that job would be cancelled with it, the interrupt
 * would never fire, and `executeQuery` — which notices neither coroutine
 * cancellation nor a thread interrupt — would spin on a core with its
 * connection open for the life of the app. [JdbcReadOnlySql.query] interrupts
 * explicitly on its way out instead.
 */
private val queryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * A separate JDBC connection to the app's SQLite file, opened READ-ONLY
 * (`open_mode=1` = SQLITE_OPEN_READONLY — the driver refuses every write with
 * "attempt to write a readonly database"), `busy_timeout=5000` so it waits
 * politely behind the bench's writes, and a real time limit on the query so a
 * runaway statement dies instead of pinning a core on the lab PC. Opened per
 * query and closed after it: a support session is rare and short, a lingering
 * connection is one more thing to leak.
 *
 * The time limit is a watchdog that calls `Statement.cancel()` — sqlite-jdbc
 * implements it with `sqlite3_interrupt()`, so the running step aborts with
 * SQLITE_INTERRUPT and `executeQuery`/`next` throw. `Statement.queryTimeout`
 * is NOT that: in sqlite-jdbc it only sets the busy timeout around the call
 * (lock waiting), never a bound on execution, so `WITH RECURSIVE … SELECT
 * count(*)` would otherwise run until the app is closed. The same interrupt is
 * what ends the query when the CALLER is cancelled — see [queryScope].
 *
 * NOTE: this class does not consult [DbQueryGuard] — RemoteTools does, before
 * calling in. Its own only defence is the read-only open mode, which
 * DbQueryGuardTest exercises directly.
 */
class JdbcReadOnlySql(
    private val dbFile: File,
    /** How long one query may run before it is interrupted. Tests shorten it. */
    private val queryTimeoutMs: Long = QUERY_TIMEOUT_MS,
) : ReadOnlySql {

    /**
     * Queries still on a JDBC thread. Only a test reads it: that a cancelled
     * call leaves nothing running behind it is invisible from anywhere else.
     */
    internal val inFlight = AtomicInteger(0)

    override suspend fun query(sql: String, maxRows: Int): SqlRows {
        // The statement the query is on, so the deadline OR a cancelled caller
        // can interrupt it. Both arrive from another thread.
        val running = AtomicReference<Statement?>(null)
        val work = queryScope.async { run(sql, maxRows, running) }
        try {
            return work.await()
        } catch (cancel: CancellationException) {
            // "End" on the banner, or expiry, while the query is still going:
            // the last chance to stop sqlite, since nothing else is watching
            // this call any more.
            runCatching { running.get()?.cancel() }
            throw cancel
        }
    }

    private suspend fun run(sql: String, maxRows: Int, running: AtomicReference<Statement?>): SqlRows = coroutineScope {
        val props = Properties().apply {
            setProperty("open_mode", "1")          // SQLITE_OPEN_READONLY
            setProperty("busy_timeout", "5000")
        }
        inFlight.incrementAndGet()
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}", props).use { conn ->
                conn.isReadOnly = true
                conn.createStatement().use { st ->
                    st.maxRows = maxRows + 1                  // one extra tells us "truncated"
                    running.set(st)
                    val timedOut = AtomicBoolean(false)
                    val watchdog = launch {
                        delay(queryTimeoutMs)
                        timedOut.set(true)
                        runCatching { st.cancel() }           // sqlite3_interrupt — aborts the running step
                    }
                    try {
                        st.executeQuery(sql).use { rs ->
                            val meta = rs.metaData
                            val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                            val rows = ArrayList<List<String?>>()
                            var truncated = false
                            while (rs.next()) {
                                if (rows.size >= maxRows) { truncated = true; break }
                                rows += (1..meta.columnCount).map { i ->
                                    when (meta.getColumnType(i)) {
                                        Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY ->
                                            rs.getBytes(i)?.let { "<blob ${it.size} bytes>" }
                                        else -> rs.getString(i)
                                    }
                                }
                            }
                            SqlRows(columns, rows, truncated)
                        }
                    } catch (e: SQLException) {
                        if (timedOut.get()) {
                            throw SQLTimeoutException("The query was stopped after ${queryTimeoutMs / 1_000} s — narrow it down (a WHERE clause, a LIMIT).")
                        }
                        throw e
                    } finally {
                        watchdog.cancel()
                        running.set(null)
                    }
                }
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private companion object {
        const val QUERY_TIMEOUT_MS = 30_000L
    }
}

actual fun platformReadOnlySql(): ReadOnlySql? = JdbcReadOnlySql(File(appDataDir(), CHAT_DB_NAME))
