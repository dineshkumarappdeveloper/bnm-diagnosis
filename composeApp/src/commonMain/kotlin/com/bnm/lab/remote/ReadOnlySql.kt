package com.bnm.lab.remote

/**
 * The read-only view of the local database the `db.query` support tool runs
 * on. The platform opens its OWN connection in read-only mode (SQLite
 * `open_mode=1`), separate from the app's SQLDelight connection, with a busy
 * timeout and a per-query time limit — so a slow support query can neither
 * write nor block the bench. [DbQueryGuard] decides WHAT may run; this only
 * runs it. Null where there is no such connection (Android, iOS).
 */
interface ReadOnlySql {
    /** Runs [sql] and returns up to [maxRows] rows; [SqlRows.truncated] says whether more existed. Throws on SQL errors. */
    suspend fun query(sql: String, maxRows: Int): SqlRows
}

data class SqlRows(
    val columns: List<String>,
    /** Cell values as text (null for SQL NULL); blobs as `<blob N bytes>`. */
    val rows: List<List<String?>>,
    val truncated: Boolean,
)

expect fun platformReadOnlySql(): ReadOnlySql?
