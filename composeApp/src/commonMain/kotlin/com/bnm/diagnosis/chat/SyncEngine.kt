package com.bnm.diagnosis.chat

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * ONE sync for every offline module. Modules register a sync task by key;
 * [syncAll] runs them all in parallel, [sync] runs a named subset.
 *
 * Registry-based on purpose: a new module (appointments, subscriptions, support,
 * wallet, …) becomes syncable with a single [register] call in App.kt — no
 * changes here. Each task only pulls rows changed since that module's own server
 * `seq` cursor, so an idle full sync is nearly free.
 *
 * Push-ready: an FCM "tickle" `{ businessId, entities }` maps straight onto
 * [sync] (or [SyncBus]) — the cursors pull the data, the push never carries it,
 * so a dropped/coalesced push self-heals on the next foreground / reconnect sync.
 */
class SyncEngine {

    /**
     * A unit of syncable data — one per module/entity.
     * @param eager  included in a full [syncAll]. Set false for heavy / rarely
     *               viewed modules that should sync only when explicitly named.
     */
    class SyncTask(
        val key: String,
        val label: String,
        val eager: Boolean,
        // Returns Result so the engine can SEE a failed delta (the repo methods
        // return Result.failure rather than throwing) and retry it. A lambda that
        // returns Unit would hide failures and the retry would never fire.
        val sync: suspend (businessId: String) -> Result<Unit>,
    )

    /** Progress of an in-flight sync (null when idle) — drives the first-sync banner. */
    data class Progress(val done: Int, val total: Int, val label: String)

    private val tasks = LinkedHashMap<String, SyncTask>()
    private val mutex = Mutex()

    /** Register a syncable module. Idempotent by key (last registration wins). */
    fun register(
        key: String,
        label: String = key.replaceFirstChar { it.uppercase() },
        eager: Boolean = true,
        sync: suspend (businessId: String) -> Result<Unit>,
    ) {
        tasks[key] = SyncTask(key, label, eager, sync)
    }

    /** All registered task keys (the vocabulary a push payload can reference). */
    val keys: Set<String> get() = tasks.keys.toSet()

    private val _isSyncing = MutableStateFlow(false)
    /** True while a sync is in flight — drives the single spinner in the top bar. */
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _lastSyncedAt = MutableStateFlow<String?>(null)
    /** ISO timestamp of the last successful sync. */
    val lastSyncedAt: StateFlow<String?> = _lastSyncedAt

    private val _progress = MutableStateFlow<Progress?>(null)
    /** Per-task progress while a sync runs (null when idle). */
    val progress: StateFlow<Progress?> = _progress

    /** Full sync — every eager task. Top-bar Refresh, app foreground, reconnect. */
    suspend fun syncAll(businessId: String): Result<Unit> =
        run(businessId, tasks.values.filter { it.eager })

    /**
     * Targeted sync — only the named tasks. The FCM push tickle entry point.
     * An empty set, or one containing "all", falls back to [syncAll]. Unknown
     * keys are ignored (forward-compatible with new push payloads).
     */
    suspend fun sync(businessId: String, keys: Set<String>): Result<Unit> {
        if (keys.isEmpty() || "all" in keys) return syncAll(businessId)
        return run(businessId, tasks.values.filter { it.key in keys })
    }

    private suspend fun run(businessId: String, toRun: Collection<SyncTask>): Result<Unit> {
        if (toRun.isEmpty()) return Result.success(Unit)
        // Serialize syncs so two triggers (Refresh + push) don't stampede; the
        // mutex makes the second wait rather than no-op, so a push right after a
        // manual sync still picks up anything that changed in between.
        return mutex.withLock {
            _isSyncing.value = true
            val total = toRun.size
            val progressLock = Mutex()
            var completed = 0
            _progress.value = Progress(0, total, "Syncing…")
            // Cap concurrency: a cold first-sync fires every module at once, and a
            // stampede of edge-function cold-starts dropped tasks (company data
            // missing until a manual sync). 3-at-a-time warms functions gently.
            val gate = Semaphore(3)
            try {
                coroutineScope {
                    toRun.map { task ->
                        async {
                            gate.withPermit {
                                // Resilient: retry transient failures (cold starts,
                                // network blips) so a module is never silently dropped.
                                // task.sync returns Result (it doesn't throw), and a
                                // thrown error is folded in via getOrElse.
                                var attempt = 0
                                while (true) {
                                    val ok = runCatching { task.sync(businessId) }
                                        .getOrElse { Result.failure(it) }.isSuccess
                                    if (ok || ++attempt >= 4) break
                                    delay(800L * attempt)
                                }
                            }
                            // Report progress as each task finishes (parallel-safe).
                            progressLock.withLock {
                                completed++
                                _progress.value = Progress(completed, total, task.label)
                            }
                        }
                    }.awaitAll()
                }
                _lastSyncedAt.value = kotlin.time.Clock.System.now().toString()
                Result.success(Unit)
            } finally {
                _isSyncing.value = false
                _progress.value = null
            }
        }
    }
}

/** App-wide [SyncEngine], provided from App.kt so any screen can trigger one sync. */
val LocalSyncEngine = staticCompositionLocalOf<SyncEngine> {
    error("LocalSyncEngine not provided")
}

/**
 * Process-wide bus the (future) FCM push handler emits to. App.kt collects it and
 * calls [SyncEngine.sync]. This decouples the platform push service — which runs
 * outside Compose and has no [LocalSyncEngine] — from the engine.
 *
 * When push lands: the FCM service calls `SyncBus.tickle(businessId, entities)`
 * with a TINY payload (ids only, never data); the collector pulls the deltas.
 */
object SyncBus {
    data class Tickle(val businessId: String, val entities: Set<String>)

    private val _requests = MutableSharedFlow<Tickle>(extraBufferCapacity = 32)
    val requests: SharedFlow<Tickle> = _requests.asSharedFlow()

    /** "Business X changed entities Y — pull them." Safe to call from any thread. */
    fun tickle(businessId: String, entities: Set<String>) {
        _requests.tryEmit(Tickle(businessId, entities))
    }
}
