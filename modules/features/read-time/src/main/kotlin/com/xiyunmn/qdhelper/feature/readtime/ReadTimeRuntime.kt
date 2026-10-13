package com.xiyunmn.qdhelper.feature.readtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.currentHostUserId
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import java.util.concurrent.Future

/** One runtime owner for the database, delayed queries and their settings subscription. */
internal object ReadTimeRuntime {
    private val lock = Any()
    private var active: ReadTimeSession? = null
    private var closed = false

    init {
        FeatureResources.register("read-time.runtime") {
            val previous = synchronized(lock) {
                closed = true
                active.also { active = null }
            }
            previous?.close()
            ReadTimeReportAuditStore.clearSessionCounters()
        }
    }

    fun session(context: Context, classLoader: ClassLoader = context.classLoader): ReadTimeSession? = synchronized(lock) {
        if (closed) return null
        active?.takeIf { it.classLoader === classLoader }?.let { return it }
        active?.close()
        ReadTimeSession(context.applicationContext, classLoader).also { active = it }
    }

    fun userId(context: Context): Long? = currentHostUserId(context, context.classLoader)
}

internal class ReadTimeSession(
    private val context: Context,
    val classLoader: ClassLoader,
    private val account: () -> Long? = { currentHostUserId(context, classLoader) },
    private val fetch: () -> ReadTimeCloudProbe.Result? = { ReadTimeCloudProbe.fetchTodayWeekSeconds(classLoader) },
    private val wallTime: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    val statistics = ReadTimeLocalStatisticsStore(context)
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val callbackToken = Any()
    private val worker = ManagedExecutor(
        ownerKey = "read-time.queries@${System.identityHashCode(this)}",
        threadName = "QDHelper-ReadTimeProbe",
        onFailure = { FeatureHooks.w("Read time query failed: ${it.javaClass.simpleName}") },
    )
    private var closed = false
    private var generation = 0L
    private val requests = mutableSetOf<Request>()
    private var delayed: Request? = null
    private val lastStartedAt = mutableMapOf<Long, Long>()
    private val settings = ConfigRuntime.observeUserChanges(
        context,
        setOf(SettingIds.KEY_MODULE_ENABLED, SettingIds.KEY_READ_TIME_MULTIPLIER_ENABLED, SettingIds.KEY_READ_TIME_MULTIPLIER_VALUE),
    ) { invalidate() }

    data class QueryResult(
        val userId: Long?,
        val cloud: ReadTimeCloudProbe.Result?,
        val local: ReadTimeLocalStatisticsStore.Totals?,
        val verification: ReadTimeCloudVerification?,
        val cancelled: Boolean = false,
    )

    private data class QueryState(val userId: Long?, val day: String, val moduleEnabled: Boolean, val enabled: Boolean, val raw: String)

    private inner class Request(val state: QueryState, val generation: Long, var callback: ((QueryResult) -> Unit)?) {
        val automatic = callback == null
        val start = Runnable { submit(this) }
        var future: Future<*>? = null
    }

    fun record(event: ReadTimeAuditEvent): Unit = synchronized(lock) {
        if (closed) return
        if (event.daoSuccess) runCatching { statistics.record(event) }.onFailure {
            FeatureHooks.w("Read time local database write failed: ${it.javaClass.simpleName}")
        }
        runCatching { ReadTimeReportAuditStore.record(context, event) }.onFailure {
            FeatureHooks.w("Read time report audit write failed: ${it.javaClass.simpleName}")
        }
    }

    fun scheduleAutomatic(reportedAccounts: Set<Long>) {
        val state = readState()
        cancelStale(state)
        val userId = state.userId?.takeIf { it > 0L && it in reportedAccounts } ?: return
        val configured = ReadTimeMultiplierPolicy.migrateStoredMultiplier(state.raw) ?: return
        if (!state.moduleEnabled || !state.enabled || configured <= 1.0) return
        synchronized(lock) {
            if (closed || requests.any { it.state.userId == userId }) return
            val last = lastStartedAt[userId]
            if (last != null && SystemClock.elapsedRealtime() - last < MIN_INTERVAL_MS) return
            val request = Request(state, generation, null)
            requests += request
            delayed = request
            if (!handler.postDelayed(request.start, callbackToken, DELAY_MS)) finish(request)
        }
    }

    /** Manual queries remove the timer, then queue behind any request already using the host network. */
    fun query(callback: (QueryResult) -> Unit): AutoCloseable {
        val state = readState()
        cancelStale(state)
        synchronized(lock) { delayed }?.let(::cancel)
        if (state.userId == null || state.userId <= 0L || !state.moduleEnabled) {
            if (!synchronized(lock) { closed }) callback(QueryResult(state.userId, null, null, null))
            return AutoCloseable {}
        }
        val request = synchronized(lock) {
            if (closed) {
                callback(QueryResult(state.userId, null, null, null, cancelled = true))
                return AutoCloseable {}
            }
            Request(state, generation, callback).also { requests += it }
        }
        submit(request)
        return AutoCloseable { cancel(request, notify = false) }
    }

    fun clearAudit(userId: Long): Boolean = synchronized(lock) {
        if (closed) return false
        requests.filter { it.state.userId == userId }.forEach(::cancel)
        val auditCleared = ReadTimeReportAuditStore.clear(context, userId)
        val cloudCleared = ReadTimeCloudAuditStore.clear(context, userId)
        auditCleared && cloudCleared
    }

    fun overrideAutomaticLimit(): Boolean = synchronized(lock) {
        if (closed) return false
        val userId = account()?.takeIf { it > 0L } ?: return false
        // Even an unchanged slider value is a new user decision. An older cloud request
        // must not restore its limit after the override finishes.
        requests.filter { it.state.userId == userId }.forEach(::cancel)
        ReadTimeCloudAuditStore.clearAutomaticLimit(context, userId)
    }

    private fun submit(request: Request) {
        synchronized(lock) { if (delayed === request) delayed = null }
        if (!isCurrent(request)) {
            cancel(request)
            return
        }
        val future = worker.submit { runQuery(request) }
        val cancelNow = synchronized(lock) {
            request.future = future
            request !in requests || closed
        }
        if (cancelNow) future.cancel(true)
        if (future.isCancelled) cancel(request)
    }

    private fun runQuery(request: Request) {
        var posted = false
        try {
            if (!isCurrent(request)) return
            val userId = requireNotNull(request.state.userId)
            synchronized(lock) { lastStartedAt[userId] = SystemClock.elapsedRealtime() }
            val cloud = runCatching(fetch).onFailure {
                if (isCurrent(request)) FeatureHooks.w("Read time cloud query unavailable: ${it.javaClass.simpleName}")
            }.getOrNull()
            if (!isCurrent(request)) return
            val verification = synchronized(lock) {
                if (!isCurrent(request)) return
                // Invalidation and clearing cannot overtake the final validity check and writeback.
                cloud?.let { ReadTimeCloudAuditStore.reconcile(context, userId, it, wallTime()) { isCurrent(request) } }
            }
            if (!isCurrent(request)) return
            if (request.automatic) return
            val local = runCatching { statistics.totals(userId, wallTime()) }.getOrNull()
            posted = postResult(request, QueryResult(userId, cloud, local, verification))
        } catch (failure: Exception) {
            if (isCurrent(request)) {
                FeatureHooks.w("Read time query failed: ${failure.javaClass.simpleName}")
                if (!request.automatic) posted = postResult(request, QueryResult(request.state.userId, null, null, null))
            }
        } finally {
            if (!posted) {
                if (request.automatic) finish(request) else cancel(request)
            }
        }
    }

    private fun postResult(request: Request, result: QueryResult): Boolean = handler.postAtTime({
        synchronized(lock) {
            if (isCurrent(request)) {
                val callback = request.callback
                finish(request)
                runCatching { callback?.invoke(result) }.onFailure {
                    FeatureHooks.w("Read time query presentation failed: ${it.javaClass.simpleName}")
                }
            } else {
                cancel(request)
            }
        }
    }, callbackToken, SystemClock.uptimeMillis())

    private fun readState() = QueryState(
        account(),
        ReadTimeLocalStatisticsPolicy.dayKey(wallTime()),
        ConfigRuntime.readBoolean(SettingIds.KEY_MODULE_ENABLED),
        SettingsRuntime.isReadTimeMultiplierEnabled(context),
        SettingsRuntime.readTimeMultiplierRaw(context),
    )

    private fun isCurrent(request: Request): Boolean =
        synchronized(lock) { !closed && request.generation == generation && request in requests } &&
            request.state == readState()

    private fun cancelStale(state: QueryState) {
        synchronized(lock) { requests.filter { it.state != state } }.forEach(::cancel)
    }

    private fun invalidate() {
        val stale = synchronized(lock) {
            generation++
            requests.toList()
        }
        stale.forEach(::cancel)
    }

    private fun cancel(request: Request, notify: Boolean = true) {
        val future = synchronized(lock) {
            val removed = requests.remove(request)
            if (delayed === request) delayed = null
            if (!notify || closed) request.callback = null
            if (removed && notify && !closed && request.callback != null) {
                handler.postAtTime({
                    synchronized(lock) {
                        val callback = request.callback
                        request.callback = null
                        if (!closed) runCatching {
                            callback?.invoke(QueryResult(request.state.userId, null, null, null, cancelled = true))
                        }
                    }
                }, callbackToken, SystemClock.uptimeMillis())
            }
            request.future
        }
        handler.removeCallbacks(request.start)
        future?.cancel(true)
    }

    private fun finish(request: Request) = synchronized(lock) {
        requests.remove(request)
        request.callback = null
        if (delayed === request) delayed = null
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        invalidate()
        handler.removeCallbacksAndMessages(callbackToken)
        settings.close()
        worker.close()
        statistics.close()
        synchronized(lock) { lastStartedAt.clear() }
    }

    private companion object {
        const val DELAY_MS = 15_000L
        const val MIN_INTERVAL_MS = 120_000L
    }
}
