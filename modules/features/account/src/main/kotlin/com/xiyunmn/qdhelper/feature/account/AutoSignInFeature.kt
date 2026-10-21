package com.xiyunmn.qdhelper.feature.account

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.currentHostUserId
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.AccountTargetKeys
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object AutoSignInFeature : InstallableFeature {
    override val name: String = "AutoSignInFeature"

    private val membershipUnavailableReported = AtomicBoolean(false)
    private val pendingAttempts = ConcurrentHashMap.newKeySet<String>()
    private val activeSubscriptions = ConcurrentHashMap.newKeySet<Any>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }
    private val lifecycleSubscriptions = mutableListOf<AutoCloseable>()

    @Volatile
    private var activeClassLoader: ClassLoader? = null

    init {
        FeatureResources.register("AutoSignInFeature") {
            mainHandler.removeCallbacksAndMessages(null)
            pendingAttempts.clear()
            membershipUnavailableReported.set(false)
            activeSubscriptions.forEach(::disposeSubscription)
            activeSubscriptions.clear()
            synchronized(lifecycleSubscriptions) {
                lifecycleSubscriptions.forEach { runCatching(it::close) }
                lifecycleSubscriptions.clear()
            }
            activeClassLoader = null
            FeatureHooks.i("Auto sign-in hot reload state cleared")
        }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        activeClassLoader = classLoader
        subscribeActivityCallbacks()
        mainHandler.post {
            if (activeClassLoader !== classLoader) return@post
            ActivityLifecycleCallbacks.currentActivities().forEach { activity ->
                if (activity.javaClass.name == QDReaderHost.Classes.MAIN_GROUP_ACTIVITY && activity.hasWindowFocus()) {
                    tryAutoSignIn(activity, classLoader, phase)
                }
            }
        }
        FeatureHooks.i("Auto sign-in phase finished: phase=$phase, process=$processName, lifecycleSubscriptions=${lifecycleSubscriptions.size}")
    }

    private fun subscribeActivityCallbacks() = synchronized(lifecycleSubscriptions) {
        if (lifecycleSubscriptions.isNotEmpty()) return@synchronized
        ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("account"),
            id = "qdhelper.account.auto_sign_in_on_resume",
            resumed = { activity ->
                if (activity.javaClass.name == QDReaderHost.Classes.MAIN_GROUP_ACTIVITY) {
                    activeClassLoader?.let { tryAutoSignIn(activity, it, "onResume") }
                }
            },
        )?.let(lifecycleSubscriptions::add)
        ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("account"),
            id = "qdhelper.account.auto_sign_in_on_focus",
            windowFocusChanged = { activity, hasFocus ->
                if (hasFocus && activity.javaClass.name == QDReaderHost.Classes.MAIN_GROUP_ACTIVITY) {
                    activeClassLoader?.let { tryAutoSignIn(activity, it, "onWindowFocusChanged") }
                }
            },
        )?.let(lifecycleSubscriptions::add)
    }

    private fun tryAutoSignIn(activity: Activity?, classLoader: ClassLoader, reason: String) {
        activity ?: return
        val context = HostSessionStore.contextOrNull() ?: activity.applicationContext ?: activity
        if (!SettingsRuntime.isAutoSignInEnabled(context) || activity.isFinishing || activity.isDestroyed) {
            return
        }
        val userId = currentUserId(classLoader)
        if (userId <= 0L) {
            FeatureHooks.i("Auto sign-in skipped: not logged in, reason=$reason")
            return
        }
        val today = currentDay()
        if (SettingsRuntime.hasAutoSignInAttempted(context, userId, today)) {
            return
        }
        val attemptKey = "$userId:$today"
        if (!pendingAttempts.add(attemptKey)) {
            return
        }
        mainHandler.postDelayed({
            runCatching {
                launchCheckInRequest(
                    activity = activity,
                    context = context,
                    classLoader = classLoader,
                    userId = userId,
                    today = today,
                    reason = reason,
                    attemptKey = attemptKey,
                )
            }.onFailure { throwable ->
                pendingAttempts.remove(attemptKey)
                FeatureHooks.e("Auto sign-in check-in request failed to launch", throwable)
                showToast(activity, context, "自动签到发起失败，请稍后手动重试", Toast.LENGTH_LONG)
            }
        }, 1200L)
    }

    private fun launchCheckInRequest(
        activity: Activity,
        context: Context,
        classLoader: ClassLoader,
        userId: Long,
        today: String,
        reason: String,
        attemptKey: String,
    ) {
        if (!isCurrentAttempt(context, classLoader, userId, today) || activity.isFinishing || activity.isDestroyed) {
            pendingAttempts.remove(attemptKey)
            return
        }
        val instanceMethod = resolveHostMethod(
            classLoader,
            AccountTargetKeys.checkInManagerInstance,
        )
            ?: error("UserCheckInManager instance method not found")
        val manager = FeatureHooks.invoke(instanceMethod, null)
            ?: error("UserCheckInManager instance is null")
        val membershipEligible = runCatching {
            resolveHostMethod(classLoader, AccountTargetKeys.membershipAutoSignIn)?.let { method ->
                FeatureHooks.invoke(method, manager) as? Boolean
            }
        }.getOrNull()
        if (membershipEligible == null && membershipUnavailableReported.compareAndSet(false, true)) {
            FeatureHooks.w("Auto sign-in membership eligibility unavailable; continuing normal check-in")
        }
        if (membershipEligible == true) {
            pendingAttempts.remove(attemptKey)
            FeatureHooks.i("Auto sign-in skipped: host membership auto-sign-in eligible")
            return
        }
        if (isHostAlreadyCheckedIn(manager)) {
            markAttempt(context, userId, today, "host-already-checked-in")
            pendingAttempts.remove(attemptKey)
            FeatureHooks.i("Auto sign-in skipped because host already checked in today: user=${userId.masked()}")
            showToast(activity, context, "今日已签到")
            return
        }

        val observable = resolveHostMethod(
            classLoader,
            AccountTargetKeys.checkInRequest,
        )
            ?: error("check-in observable method not found")
        val requestObservable = FeatureHooks.invoke(observable, manager, activity)
            ?: error("check-in observable is null")
        val request = composeNetworkSchedulerIfAvailable(requestObservable, classLoader)
        val subscribed = subscribeObservable(
            observable = request,
            classLoader = classLoader,
            onNext = onNext@ { response ->
                if (!isCurrentAttempt(context, classLoader, userId, today)) {
                    pendingAttempts.remove(attemptKey)
                    return@onNext
                }
                val summary = summarizeServerResponse(response)
                if (summary.isSuccessOrAlreadyCheckedIn) {
                    syncHostCheckInState(manager)
                    markAttempt(context, userId, today, "network-success class=${summary.responseClass} code=${summary.code}")
                    FeatureHooks.i(
                        "Auto sign-in check-in succeeded: reason=$reason, user=${userId.masked()}, " +
                            "class=${summary.responseClass}, code=${summary.code}, " +
                            "message=${summary.message.orEmpty().take(48)}",
                    )
                    showToast(activity, context, "自动签到成功", Toast.LENGTH_LONG)
                } else {
                    val message = summary.message.orEmpty().ifBlank { "服务端未返回成功状态" }
                    FeatureHooks.w(
                        "Auto sign-in check-in returned non-success: class=${summary.responseClass}, code=${summary.code}, " +
                            "message=${summary.message.orEmpty().take(80)}",
                    )
                    showToast(activity, context, "自动签到失败：$message", Toast.LENGTH_LONG)
                }
                pendingAttempts.remove(attemptKey)
            },
            onError = onError@ { throwable ->
                if (!isCurrentAttempt(context, classLoader, userId, today)) {
                    pendingAttempts.remove(attemptKey)
                    return@onError
                }
                val message = throwable.message.orEmpty()
                if (isAlreadyCheckedInMessage(message)) {
                    syncHostCheckInState(manager)
                    markAttempt(context, userId, today, "network-already-checked-in")
                    FeatureHooks.i("Auto sign-in check-in already completed today: user=${userId.masked()}")
                    showToast(activity, context, "今日已签到")
                } else {
                    val cleanMessage = message.ifBlank { "网络异常，请稍后手动重试" }
                    FeatureHooks.w("Auto sign-in check-in request failed: ${throwable.javaClass.name}: $message")
                    showToast(activity, context, "自动签到失败：$cleanMessage", Toast.LENGTH_LONG)
                }
                pendingAttempts.remove(attemptKey)
            },
        )
        if (!subscribed) {
            pendingAttempts.remove(attemptKey)
            FeatureHooks.w("Auto sign-in request not launched: compatible Rx subscribe method unavailable")
            showToast(activity, context, "自动签到发起失败，请稍后手动重试", Toast.LENGTH_LONG)
            return
        }
        mainHandler.postDelayed({ pendingAttempts.remove(attemptKey) }, 30_000L)
        FeatureHooks.i("Auto sign-in real check-in request launched: reason=$reason, user=${userId.masked()}")
        showToast(activity, context, "自动签到请求已发起")
    }

    private fun composeNetworkSchedulerIfAvailable(observable: Any, classLoader: ClassLoader): Any {
        return runCatching {
            val transformerClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.RETROFIT_SCHEDULER, classLoader)
                ?: return@runCatching observable
            val factory = FeatureHooks.findDeclaredMethodOrNull(transformerClass, "q")
                ?: return@runCatching observable
            val transformer = FeatureHooks.invoke(factory, null) ?: return@runCatching observable
            val compose = FeatureHooks.declaredMethodsNamed(observable.javaClass, "compose")
                .singleOrNull { method ->
                    method.parameterTypes.size == 1 && method.parameterTypes[0].isInstance(transformer)
                } ?: return@runCatching observable
            FeatureHooks.invoke(compose, observable, transformer) ?: observable
        }.getOrElse { observable }
    }

    private fun subscribeObservable(
        observable: Any,
        classLoader: ClassLoader,
        onNext: (Any?) -> Unit,
        onError: (Throwable) -> Unit,
    ): Boolean {
        // The RxJava Consumer interface letter drifts across host versions. Derive it structurally
        // from the observable's own subscribe(onNext, onError)
        // overload — the two identical single-"accept"-method interface params — so it survives drift.
        val subscribeMethod = resolveSubscribeConsumerMethod(observable)
            ?: run {
                FeatureHooks.w("Auto sign-in subscribe skipped: no subscribe(Consumer, Consumer) overload found")
                return false
            }
        val consumerClass = subscribeMethod.parameterTypes[0]
        val disposableRef = AtomicReference<Any?>()
        val terminal = AtomicBoolean(false)
        val successConsumer = consumerProxy(classLoader, consumerClass) { value ->
            runCatching { onNext(value) }
                .also { terminal.set(true); disposableRef.get()?.let(activeSubscriptions::remove) }
                .getOrThrow()
        }
        val errorConsumer = consumerProxy(classLoader, consumerClass) { value ->
            runCatching {
                onError(value as? Throwable ?: RuntimeException(value?.toString().orEmpty()))
            }.also {
                terminal.set(true); disposableRef.get()?.let(activeSubscriptions::remove)
            }.getOrThrow()
        }
        val disposable = FeatureHooks.invoke(subscribeMethod, observable, successConsumer, errorConsumer)
            ?: return false
        disposableRef.set(disposable)
        if (!terminal.get()) {
            activeSubscriptions += disposable
        }
        return true
    }

    private fun disposeSubscription(subscription: Any) {
        runCatching {
            FeatureHooks.declaredMethodsNamed(subscription.javaClass, "dispose")
                .singleOrNull { method -> method.parameterTypes.isEmpty() }
                ?.let { method -> FeatureHooks.invoke(method, subscription) }
        }.onFailure { throwable ->
            FeatureHooks.w("Auto sign-in subscription dispose failed: ${throwable.javaClass.name}")
        }
    }

    // Locate the RxJava subscribe(Consumer, Consumer) overload without hardcoding the obfuscated
    // Consumer interface name. The onNext/onError overload takes two identical single-method
    // ("accept") functional-interface params, which is stable across host versions.
    private fun resolveSubscribeConsumerMethod(observable: Any): Method? {
        return RxConsumerSubscriptionMethodPolicy.select(
            methods = FeatureHooks.methodsInHierarchy(observable.javaClass),
        ) { consumerClass ->
            FeatureHooks.declaredMethodsNamed(consumerClass, "accept")
                .any { candidate -> candidate.parameterTypes.size == 1 }
        }
    }

    private fun consumerProxy(
        classLoader: ClassLoader,
        consumerClass: Class<*>,
        onAccept: (Any?) -> Unit,
    ): Any {
        return Proxy.newProxyInstance(classLoader, arrayOf(consumerClass)) { proxy, method, args ->
            when (method.name) {
                "accept" -> {
                    onAccept(args?.getOrNull(0))
                    null
                }
                "toString" -> "QDHelperAutoSignInConsumer"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun isHostAlreadyCheckedIn(manager: Any): Boolean {
        return runCatching {
            val method = resolveHostMethod(
                manager.javaClass.classLoader ?: error("manager-classloader-not-found"),
                AccountTargetKeys.alreadyCheckedIn,
            )
                ?: return@runCatching false
            FeatureHooks.invoke(method, manager) as? Boolean == true
        }.getOrDefault(false)
    }

    private fun syncHostCheckInState(manager: Any) {
        runCatching {
            (resolveHostMethod(
                manager.javaClass.classLoader ?: error("manager-classloader-not-found"),
                AccountTargetKeys.syncCheckedIn,
            ) ?: error("sync-method-not-found")).let { method ->
                FeatureHooks.invoke(method, manager, true, true)
            }
        }.onFailure { throwable ->
            FeatureHooks.w("Auto sign-in failed to sync host check-in state: ${throwable.javaClass.name}")
        }
    }

    private fun markAttempt(context: Context, userId: Long, today: String, source: String) {
        if (!SettingsRuntime.markAutoSignInAttempt(context, userId, today)) {
            FeatureHooks.w("Auto sign-in $source but failed to persist attempt marker")
        }
    }

    private fun showToast(
        activity: Activity,
        fallbackContext: Context,
        message: String,
        duration: Int = Toast.LENGTH_SHORT,
    ) {
        mainHandler.post {
            val liveActivity = activity.takeUnless { it.isFinishing || it.isDestroyed }
            val context = liveActivity ?: fallbackContext
            val show = Runnable {
                runCatching {
                    Toast.makeText(context, message, duration).show()
                }.onFailure { throwable ->
                    FeatureHooks.w("Auto sign-in toast failed: ${throwable.javaClass.name}")
                }
            }
            if (liveActivity == null) {
                show.run()
            } else {
                liveActivity.window?.decorView?.post(show) ?: show.run()
            }
        }
    }

    private fun summarizeServerResponse(response: Any?): CheckInResponseSummary {
        val responseClass = response?.javaClass?.name
        val code = readIntField(response, "code")
        val message = readStringField(response, "message")
        val methodSuccess = readBooleanMethod(response, "isSuccess")
        val checkInDataSuccess = responseClass?.contains(".checkin.") == true && code == null
        return CheckInResponseSummary(
            responseClass = responseClass,
            code = code,
            message = message,
            isSuccessOrAlreadyCheckedIn = methodSuccess == true ||
                code == 0 ||
                checkInDataSuccess ||
                isAlreadyCheckedInMessage(message.orEmpty()),
        )
    }

    private fun readIntField(target: Any?, name: String): Int? {
        target ?: return null
        readNoArgMethod(target, "get${name.replaceFirstChar { it.uppercaseChar() }}")?.let { value ->
            return when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }
        return (FeatureHooks.readDeclaredFieldValue(target, name) as? Number)?.toInt()
    }

    private fun readStringField(target: Any?, name: String): String? {
        target ?: return null
        readNoArgMethod(target, "get${name.replaceFirstChar { it.uppercaseChar() }}")?.let { value ->
            return value.toString()
        }
        return FeatureHooks.readDeclaredFieldValue(target, name)?.toString()
    }

    private fun readBooleanMethod(target: Any?, methodName: String): Boolean? {
        return readNoArgMethod(target, methodName) as? Boolean
    }

    private fun readNoArgMethod(target: Any?, methodName: String): Any? {
        target ?: return null
        return runCatching {
            val method = FeatureHooks.declaredMethodsNamed(target.javaClass, methodName)
                .singleOrNull { candidate -> candidate.parameterTypes.isEmpty() }
                ?: return@runCatching null
            FeatureHooks.invoke(method, target)
        }.getOrNull()
    }

    private fun isAlreadyCheckedInMessage(message: String): Boolean {
        return message.contains("已签") || message.contains("已经签到") || message.contains("今日已")
    }

    private fun currentUserId(classLoader: ClassLoader): Long =
        currentHostUserId(HostSessionStore.contextOrNull(), classLoader) ?: 0L

    private fun currentDay(): String = synchronized(dateFormat) { dateFormat.format(Date()) }

    private fun isCurrentAttempt(context: Context, classLoader: ClassLoader, userId: Long, day: String): Boolean =
        activeClassLoader === classLoader && SettingsRuntime.isAutoSignInEnabled(context) &&
            currentUserId(classLoader) == userId && currentDay() == day

    private fun resolveHostMethod(
        classLoader: ClassLoader,
        key: TargetKey.MethodTarget,
    ): Method? {
        return HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = key,
        )
    }

    private fun Long.masked(): String {
        val text = toString()
        return if (text.length <= 4) "****" else "****" + text.takeLast(4)
    }

    private data class CheckInResponseSummary(
        val responseClass: String?,
        val code: Int?,
        val message: String?,
        val isSuccessOrAlreadyCheckedIn: Boolean,
    )
}
