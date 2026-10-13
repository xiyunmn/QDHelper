package com.xiyunmn.qdhelper.feature.account

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.ReadTimeTargetKeys
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves the current book from the canonical reader Activity, regardless of how reading started,
 * then waits until the host has reported ten minutes of reading for that book. Only then does it
 * issue one server detail query to confirm capacity and eligibility.
 */
object AutoInvestFeature : InstallableFeature {
    override val name: String = "AutoInvestFeature"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val state = AutoInvestStateTracker()
    private val lifecycleSubscriptions = mutableListOf<AutoCloseable>()
    private val worker = ManagedExecutor(
        ownerKey = "feature.account.auto-invest",
        threadName = "QDHelper-AutoInvest",
        onFailure = { throwable ->
            FeatureHooks.e("Auto-invest worker failed", throwable)
        },
    )
    private val runtimeLock = Any()

    @Volatile
    private var activeClassLoader: ClassLoader? = null

    @Volatile
    private var hostBridge: AutoInvestHostBridge? = null

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    init {
        FeatureResources.register("AutoInvestFeature") {
            mainHandler.removeCallbacksAndMessages(null)
            state.clear()
            synchronized(lifecycleSubscriptions) {
                lifecycleSubscriptions.forEach { runCatching(it::close) }
                lifecycleSubscriptions.clear()
            }
            synchronized(runtimeLock) {
                hostBridge?.close()
                hostBridge = null
                activeClassLoader = null
            }
            worker.close()
            currentActivityRef = null
            FeatureHooks.i("Auto-invest hot reload state cleared")
        }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!HostSymbols.isMainProcess(processName)) return
        prepareRuntime(classLoader)
        subscribeActivityLifecycle()
        val installed = installReportedReadObservation(module, classLoader, phase)
        FeatureHooks.i(
            "Auto-invest phase finished: phase=$phase, process=$processName, reportedReadObservation=$installed",
        )
    }

    private fun prepareRuntime(classLoader: ClassLoader) = synchronized(runtimeLock) {
        if (activeClassLoader === classLoader && hostBridge != null) return@synchronized
        hostBridge?.close()
        hostBridge = AutoInvestHostBridge(classLoader)
        activeClassLoader = classLoader
        state.clear()
    }

    private fun subscribeActivityLifecycle() = synchronized(lifecycleSubscriptions) {
        if (lifecycleSubscriptions.isNotEmpty()) return@synchronized
        ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("account"),
            id = "qdhelper.account.auto_invest_current_activity",
            resumed = { activity -> onActivityVisible(activity) },
            windowFocusChanged = { activity, hasFocus ->
                if (hasFocus) onActivityVisible(activity)
            },
        )?.let(lifecycleSubscriptions::add)
    }

    private fun onActivityVisible(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        if (activity.javaClass.name != HostSymbols.Classes.QD_READER_ACTIVITY) return
        val context = HostSessionStore.contextOrNull() ?: activity.applicationContext ?: activity
        if (!SettingsRuntime.isAutoInvestEnabled(context)) {
            state.clear()
        }
    }

    private fun installReportedReadObservation(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Boolean {
        val markReported = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = ReadTimeTargetKeys.statisticsMarkReported,
        ) ?: run {
            FeatureHooks.w("Auto-invest reported-read observation unresolved: phase=$phase")
            return false
        }
        val reportClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.READ_TIME_REPORT_ENTITY, classLoader)
            ?: return false
        val entityClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.READ_TIME_ENTITY, classLoader)
            ?: return false
        val reportBookIdField = FeatureHooks.findFieldOrNull(reportClass, "bookId") ?: return false
        val readTimeField = FeatureHooks.findFieldOrNull(entityClass, "ReadTime") ?: return false
        val dailyQuery = FeatureHooks.allDeclaredMethods(markReported.declaringClass).asSequence()
            .filter { method -> isDailyBookReadQueryShape(method, entityClass) }
            .distinct()
            .singleOrNull() ?: run {
            FeatureHooks.w("Auto-invest daily book read query unresolved: phase=$phase")
            return false
        }
        return FeatureHooks.hookAfter(
            module = module,
            executable = markReported,
            feature = "account.autoInvest:reported reading observation",
            id = "qdhelper.account.auto_invest.read_time_reported",
        ) { chain, result ->
            if (result != true) return@hookAfter
            val context = HostSessionStore.contextOrNull() ?: return@hookAfter
            if (!SettingsRuntime.isAutoInvestEnabled(context)) {
                state.clear()
                return@hookAfter
            }
            val bridge = hostBridge ?: return@hookAfter
            val userId = bridge.currentUserId()
            val activity = liveForegroundActivity()
                ?.takeIf { it.javaClass.name == HostSymbols.Classes.QD_READER_ACTIVITY }
                ?: return@hookAfter
            val bookId = bridge.readerBookId(activity).takeIf { it > 0L } ?: return@hookAfter
            val reportedBooks = (chain.args.firstOrNull() as? List<*>)
                ?.asSequence()
                ?.filterNotNull()
                ?.mapNotNull { report ->
                    (FeatureHooks.read(reportBookIdField, report) as? Number)?.toLong()
                }
                ?.toSet()
                .orEmpty()
            if (bookId !in reportedBooks) return@hookAfter
            val readMillis = queryReportedReadMillis(
                dao = chain.thisObject ?: return@hookAfter,
                query = dailyQuery,
                readTimeField = readTimeField,
                userId = userId,
                bookId = bookId,
            ) ?: return@hookAfter
            val reservation = state.observeReportedRead(userId, bookId, readMillis) ?: return@hookAfter
            queryDetails(reservation, bridge, context)
        }
    }

    private fun queryReportedReadMillis(
        dao: Any,
        query: Method,
        readTimeField: java.lang.reflect.Field,
        userId: Long,
        bookId: Long,
    ): Long? = runCatching {
        val rows = FeatureHooks.invoke(
            query,
            dao,
            AutoInvestPolicy.localDayKey(System.currentTimeMillis()),
            bookId,
            userId,
            true,
        ) as? List<*> ?: return null
        AutoInvestPolicy.sumReportedReadMillis(
            rows.mapNotNull { row ->
                row?.let { (FeatureHooks.read(readTimeField, it) as? Number)?.toLong() }
            },
        )
    }.onFailure { throwable ->
        FeatureHooks.w("Auto-invest local read-time query failed: ${throwable.javaClass.name}")
    }.getOrNull()

    private fun queryDetails(
        reservation: AutoInvestQueryReservation,
        bridge: AutoInvestHostBridge,
        context: android.content.Context,
    ) {
        val accepted = worker.execute {
            if (!isStillActive(reservation.userId, bridge, context)) {
                state.onDetailFailure(
                    reservation.userId,
                    reservation.bookId,
                )
                return@execute
            }
            val result = runCatching { bridge.fetchDetails(context, reservation.bookId) }
                .onFailure { throwable ->
                    state.onDetailFailure(
                        reservation.userId,
                        reservation.bookId,
                    )
                    FeatureHooks.w(
                        "Auto-invest detail query failed: book=${reservation.bookId.masked()}, " +
                            "error=${throwable.javaClass.name}",
                    )
                }.getOrNull() ?: return@execute
            when (result) {
                is AutoInvestDetailFetchResult.Rejected -> {
                    state.onDetailFailure(
                        reservation.userId,
                        reservation.bookId,
                    )
                    FeatureHooks.i(
                        "Auto-invest detail rejected: book=${reservation.bookId.masked()}, " +
                            "code=${result.code}, reason=${reservation.reason}",
                    )
                }
                is AutoInvestDetailFetchResult.Success -> {
                    val eligibility = state.onDetails(
                        reservation.userId,
                        reservation.bookId,
                        result.details,
                    ) ?: return@execute
                    FeatureHooks.i(
                        "Auto-invest eligibility: book=${reservation.bookId.masked()}, " +
                            "decision=$eligibility, status=${result.details.bookInvestStatus}, " +
                            "userStatus=${result.details.userInvestStatus}, rest=${result.details.restCount}, " +
                            "todayRead=${result.details.todayReadSeconds ?: -1}",
                    )
                    if (eligibility == AutoInvestEligibility.READY) {
                        performInvestment(reservation.userId, reservation.bookId, bridge, context, null)
                    }
                }
            }
        }
        if (!accepted) state.onDetailFailure(reservation.userId, reservation.bookId)
    }

    private fun performInvestment(
        userId: Long,
        bookId: Long,
        bridge: AutoInvestHostBridge,
        context: android.content.Context,
        verification: AutoInvestVerification?,
    ) {
        if (!state.isInvestmentReserved(userId, bookId) || !isStillActive(userId, bridge, context)) return
        val activity = liveForegroundActivity()
        if (activity == null) {
            state.onInvestmentDeferred(userId, bookId)
            FeatureHooks.i("Auto-invest deferred without foreground activity: book=${bookId.masked()}")
            return
        }
        val result = runCatching { bridge.requestInvestment(bookId, verification) }
            .onFailure { throwable ->
                state.onInvestmentFailed(userId, bookId, uncertain = true)
                FeatureHooks.e("Auto-invest request state uncertain: book=${bookId.masked()}", throwable)
                showToast(activity, "自动投资请求状态未知，请稍后在投资页确认", Toast.LENGTH_LONG)
            }.getOrNull() ?: return
        when (result) {
            is AutoInvestRequestResult.Success -> {
                state.onInvestmentSucceeded(userId, bookId)
                FeatureHooks.i("Auto-invest succeeded: book=${bookId.masked()}")
                showToast(activity, result.message?.takeIf(String::isNotBlank) ?: "自动投资成功")
            }
            is AutoInvestRequestResult.Rejected -> {
                state.onInvestmentFailed(userId, bookId, uncertain = false)
                FeatureHooks.w("Auto-invest rejected: book=${bookId.masked()}, code=${result.code}")
                val message = result.message.orEmpty().take(60).ifBlank { "服务端未接受请求" }
                showToast(activity, "自动投资未完成：$message", Toast.LENGTH_LONG)
            }
            is AutoInvestRequestResult.RiskRequired -> {
                if (verification != null) {
                    state.onInvestmentAborted(userId, bookId)
                    FeatureHooks.w("Auto-invest repeated risk challenge stopped: book=${bookId.masked()}")
                    showToast(activity, "自动投资验证未通过，请稍后手动确认", Toast.LENGTH_LONG)
                    return
                }
                launchRiskVerification(userId, bookId, bridge, context, activity, result.entry)
            }
        }
    }

    private fun launchRiskVerification(
        userId: Long,
        bookId: Long,
        bridge: AutoInvestHostBridge,
        context: android.content.Context,
        activity: Activity,
        riskEntry: Any,
    ) {
        mainHandler.post {
            val liveActivity = activity.takeIf {
                !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() &&
                    state.isInvestmentReserved(userId, bookId)
            }
            if (liveActivity == null) {
                state.onInvestmentDeferred(userId, bookId)
                return@post
            }
            val launched = runCatching {
                bridge.launchRiskVerification(
                    activity = liveActivity,
                    riskEntry = riskEntry,
                    onSuccess = { proof ->
                        if (hostBridge === bridge && state.isInvestmentReserved(userId, bookId)) {
                            val accepted = worker.execute {
                                performInvestment(userId, bookId, bridge, context, proof)
                            }
                            if (!accepted) state.onInvestmentAborted(userId, bookId)
                        }
                    },
                    onError = { throwable ->
                        state.onInvestmentAborted(userId, bookId)
                        FeatureHooks.w(
                            "Auto-invest risk verification stopped: book=${bookId.masked()}, " +
                                "error=${throwable.javaClass.name}",
                        )
                        showToast(liveActivity, "自动投资验证未完成")
                    },
                )
            }.onFailure { throwable ->
                FeatureHooks.w(
                    "Auto-invest risk verification launch failed: book=${bookId.masked()}, " +
                        "error=${throwable.javaClass.name}",
                )
            }.getOrDefault(false)
            if (!launched) {
                state.onInvestmentAborted(userId, bookId)
                FeatureHooks.w("Auto-invest risk UI unavailable: book=${bookId.masked()}")
                showToast(liveActivity, "自动投资验证组件不可用，请手动确认")
            }
        }
    }

    private fun isStillActive(
        userId: Long,
        bridge: AutoInvestHostBridge,
        context: android.content.Context,
    ): Boolean = SettingsRuntime.isAutoInvestEnabled(context) &&
        hostBridge === bridge && bridge.currentUserId() == userId

    private fun liveForegroundActivity(): Activity? = currentActivityRef?.get()?.takeIf { activity ->
        !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()
    }

    private fun isDailyBookReadQueryShape(method: Method, entityClass: Class<*>): Boolean {
        val parameters = method.parameterTypes
        if (!Modifier.isPublic(method.modifiers) || Modifier.isStatic(method.modifiers) ||
            method.isSynthetic || method.isBridge || parameters.size != 4 ||
            parameters[0] != String::class.java ||
            parameters[1] != Long::class.javaPrimitiveType ||
            parameters[2] != Long::class.javaPrimitiveType ||
            parameters[3] != Boolean::class.javaPrimitiveType ||
            !List::class.java.isAssignableFrom(method.returnType)
        ) {
            return false
        }
        val generic = method.genericReturnType as? java.lang.reflect.ParameterizedType ?: return false
        return generic.actualTypeArguments.singleOrNull() == entityClass
    }

    private fun showToast(activity: Activity, message: String, duration: Int = Toast.LENGTH_SHORT) {
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            runCatching { Toast.makeText(activity, message, duration).show() }
                .onFailure { FeatureHooks.w("Auto-invest toast failed: ${it.javaClass.name}") }
        }
    }

    private fun Long.masked(): String {
        val text = toString()
        return if (text.length <= 4) "****" else "****" + text.takeLast(4)
    }
}
