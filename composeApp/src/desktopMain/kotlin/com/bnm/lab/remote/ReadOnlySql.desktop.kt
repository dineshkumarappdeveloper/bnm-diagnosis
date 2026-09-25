package com.bnm.lab.remote

import com.bnm.lab.db.CHAT_DB_NAME
import com.bnm.lab.db.appDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.DriverManager
import java.sql.Types
import java.util.Properties

/**
 * A separate JDBC connection to the app's SQLite file, opened READ-ONLY
 * (`open_mode=1` = SQLITE_OPEN_READONLY — the driver refuses every write with
 * "attempt to write a readonly database"), `busy_timeout=5000` so it waits
 * politely behind the bench's writes, and a 30 s statement timeout so a
 * runaway query dies instead of holding a lock. Opened per query and closed
 * after it: a support session is rare and short, a lingering connection is
 * one more thing to leak.
 *
 * NOTE: this class does not consult [DbQueryGuard] — RemoteTools does, before
 * calling in. Its own only defence is the read-only open mode, which
 * DbQueryGuardTest exercises directly.
 */
class JdbcReadOnlySql(private val dbFile: File) : ReadOnlySql {

    override suspend fun query(sql: String, maxRows: Int): SqlRows = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            setProperty("open_mode", "1")          // SQLITE_OPEN_READONLY
            setProperty("busy_timeout", "5000")
        }
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}", props).use { conn ->
            conn.isReadOnly = true
            conn.createStatement().use { st ->
                st.queryTimeout = QUERY_TIMEOUT_S
                st.maxRows = maxRows + 1                  // one extra tells us "truncated"
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
            }
        }
    }

    private companion object {
        const val QUERY_TIMEOUT_S = 30
    }
}

actual fun platformReadOnlySql(): ReadOnlySql? = JdbcReadOnlySql(File(appDataDir(), CHAT_DB_NAME))
