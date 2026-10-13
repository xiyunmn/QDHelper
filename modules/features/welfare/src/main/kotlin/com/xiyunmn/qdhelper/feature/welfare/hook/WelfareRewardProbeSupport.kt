package com.xiyunmn.qdhelper.feature.welfare.hook

import android.app.Activity
import android.content.Context
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookChain
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.welfare.model.finishWatchArgsSummary
import com.xiyunmn.qdhelper.feature.welfare.model.finishWatchBusinessArgsSummary
import com.xiyunmn.qdhelper.feature.welfare.model.isLikelyWelfareBrowserIntent
import com.xiyunmn.qdhelper.feature.welfare.model.maskId
import com.xiyunmn.qdhelper.feature.welfare.model.ownerLabel
import com.xiyunmn.qdhelper.feature.welfare.model.safeTypeSummary
import com.xiyunmn.qdhelper.feature.welfare.model.shortHash
import com.xiyunmn.qdhelper.feature.welfare.model.sizeParams
import com.xiyunmn.qdhelper.feature.welfare.model.typeLabel
import com.xiyunmn.qdhelper.feature.welfare.model.unwrapActivity
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong

internal fun WelfareRewardProbeFeature.installWelfareTaskApiProbe(
    module: HookModule,
    classLoader: ClassLoader,
    phase: String,
): Int {
    val methods = resolvedMethods(
        classLoader = classLoader,
        key = WelfareTargetKeys.taskReadingPageApi,
    )
    methods.filter { Modifier.isAbstract(it.modifiers) }.forEach { method ->
        FeatureHooks.i("Welfare reward probe discovered abstract API getTask: ${method.name}${method.parameterTypes.sizeParams()}")
    }
    return methods.sumOf { method ->
        if (Modifier.isAbstract(method.modifiers)) {
            return@sumOf 0
        }
        installAround(module, "welfare.api.getTask", method, phase) { chain ->
            val advTaskId = chain.args.firstOrNull { it is String } as? String
            logHit(
                id = "welfare.api.getTask",
                method = method,
                detail = "begin advTaskId=${advTaskId.maskId()}, args=${chain.args.safeTypeSummary()}",
            )
            val result = chain.proceed()
            logHit(
                id = "welfare.api.getTask",
                method = method,
                detail = "end result=${result.typeLabel()}",
            )
            result
        }
    }
}

internal fun WelfareRewardProbeFeature.installFinishWatchApiProbe(
    module: HookModule,
    classLoader: ClassLoader,
    phase: String,
): Int {
    val methods = resolvedMethods(
        classLoader = classLoader,
        key = WelfareTargetKeys.finishWatchApi,
    )
    methods.filter { Modifier.isAbstract(it.modifiers) }.forEach { method ->
        FeatureHooks.i("Welfare reward probe discovered abstract API finishWatch: ${method.name}${method.parameterTypes.sizeParams()}")
    }
    return methods.sumOf { method ->
        if (Modifier.isAbstract(method.modifiers)) {
            return@sumOf 0
        }
        installAround(module, "welfare.api.finishWatch", method, phase) { chain ->
            logHit(
                id = "welfare.api.finishWatch",
                method = method,
                detail = "begin ${chain.args.finishWatchArgsSummary()}",
            )
            val result = chain.proceed()
            logHit(
                id = "welfare.api.finishWatch",
                method = method,
                detail = "end result=${result.typeLabel()}",
            )
            result
        }
    }
}

internal fun WelfareRewardProbeFeature.installFinishWatchBusinessProbe(
    module: HookModule,
    classLoader: ClassLoader,
    phase: String,
): Int {
    // The resolver already verifies every supported signature. Rechecking obfuscated names
    // here would silently discard valid methods after a host update.
    val methods = resolvedMethods(
        classLoader = classLoader,
        key = WelfareTargetKeys.finishWatchBusiness,
    )
    return methods.sumOf { method ->
        installAround(module, "welfare.extra.finishWatch.${method.name}", method, phase) { chain ->
            logHit(
                id = "welfare.extra.finishWatch.${method.name}",
                method = method,
                detail = "begin args=${chain.args.finishWatchBusinessArgsSummary()}",
            )
            val result = chain.proceed()
            logHit(
                id = "welfare.extra.finishWatch.${method.name}",
                method = method,
                detail = "end result=${result.typeLabel()}",
            )
            result
        }
    }
}

internal fun WelfareRewardProbeFeature.resolvedMethods(
    classLoader: ClassLoader,
    key: TargetKey.MethodTarget,
    predicate: (Method) -> Boolean,
): List<Method> {
    val resolved = HostTargetResolver.resolveMethods(
        context = HostSessionStore.contextOrNull(),
        classLoader = classLoader,
        processName = HostSessionStore.processName,
        key = key,
    )
    return resolved.filter(predicate).also { accepted ->
        if (accepted.size != resolved.size) FeatureHooks.w(
            "Welfare consumer contract mismatch: target=${key.id.value}, resolved=${resolved.size}, accepted=${accepted.size}",
        )
    }
}

internal fun WelfareRewardProbeFeature.resolvedMethods(
    classLoader: ClassLoader,
    key: TargetKey.MethodTarget,
): List<Method> {
    return HostTargetResolver.resolveMethods(
        context = HostSessionStore.contextOrNull(),
        classLoader = classLoader,
        processName = HostSessionStore.processName,
        key = key,
    )
}

internal fun WelfareRewardProbeFeature.installAround(
    module: HookModule,
    id: String,
    method: Method,
    phase: String,
    around: (HookChain) -> Any?,
): Int {
    val success = FeatureHooks.hookAround(
        module = module,
        executable = method,
        feature = "welfare.rewardProbe.$id:${method.ownerLabel()}.${method.name}",
        id = "qdhelper.$id.${method.shortHash()}",
    ) { chain ->
        if (!isProbeEnabled() && !isAnyRewardBypassEnabled()) {
            chain.proceed()
        } else {
            around(chain)
        }
    }
    if (!success) return 0
    FeatureHooks.i("Welfare reward probe installed $id: ${method.ownerLabel()}.${method.name}, phase=$phase")
    return 1
}

internal fun WelfareRewardProbeFeature.logHit(id: String, method: Method, detail: String) {
    if (!isProbeEnabled()) {
        return
    }
    val key = "$id:${method.toGenericString()}"
    val count = hitCounters.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
    if (count <= FIRST_VERBOSE_HITS || count % SAMPLE_INTERVAL == 0L) {
        FeatureHooks.i("Welfare reward probe hit $id#$count: $detail, thread=${Thread.currentThread().name}")
    }
}

internal fun WelfareRewardProbeFeature.logLifecycleHit(id: String, detail: String) {
    if (!isProbeEnabled()) return
    val count = hitCounters.computeIfAbsent(id) { AtomicLong(0L) }.incrementAndGet()
    if (count <= FIRST_VERBOSE_HITS || count % SAMPLE_INTERVAL == 0L) {
        FeatureHooks.i("Welfare reward probe hit $id#$count: $detail, thread=${Thread.currentThread().name}")
    }
}

internal fun WelfareRewardProbeFeature.isProbeEnabled(): Boolean {
    return SettingsRuntime.isWelfareRewardProbeEnabled(HostSessionStore.contextOrNull())
}

internal fun WelfareRewardProbeFeature.isQqMiniWelfareRewardContext(context: Context?): Boolean {
    val activity = context.unwrapActivity()
    if (activity != null) {
        return isWelfareBrowserActivity(activity)
    }
    return isRecentWelfareBrowserContext()
}

internal fun WelfareRewardProbeFeature.isWelfareBrowserActivity(activity: Activity?): Boolean {
    activity ?: return false
    return activity.javaClass.name == QDReaderHost.Classes.QD_BROWSER_ACTIVITY &&
        activity.intent.isLikelyWelfareBrowserIntent()
}

internal fun WelfareRewardProbeFeature.isRecentWelfareBrowserContext(): Boolean {
    if (foregroundWelfareBrowserActivity() != null) {
        return true
    }
    val elapsedMs = System.currentTimeMillis() - recentWelfareBrowserResumeAtMs.get()
    return elapsedMs in 0..QQMINI_BROWSER_MATCH_WINDOW_MS
}

internal fun WelfareRewardProbeFeature.foregroundWelfareBrowserActivity(): Activity? {
    val activity = currentActivityRef?.get() ?: return null
    return if (isWelfareBrowserActivity(activity)) activity else null
}

internal fun WelfareRewardProbeFeature.currentWelfareBrowserActivity(): Activity? {
    foregroundWelfareBrowserActivity()?.let { return it }
    val activity = recentWelfareBrowserActivityRef?.get() ?: return null
    if (activity.javaClass.name != QDReaderHost.Classes.QD_BROWSER_ACTIVITY) {
        return null
    }
    if (!activity.intent.isLikelyWelfareBrowserIntent()) {
        return null
    }
    if (activity.isFinishing) {
        return null
    }
    if (activity.isDestroyed) {
        return null
    }
    return activity
}

internal fun WelfareRewardProbeFeature.scheduleRewardPopupSuppression(activity: Activity?, reason: String) {
    WelfareRewardPopup.resume(activity ?: currentWelfareBrowserActivity(), reason)
}
internal fun WelfareRewardProbeFeature.isAnyRewardBypassEnabled(): Boolean {
    if (!activeInstallMode.rewardBypassAllowed) {
        return false
    }
    val context = HostSessionStore.contextOrNull()
    return SettingsRuntime.isWelfareRewardAdSkipEnabled(context) ||
        SettingsRuntime.isWelfareComboRewardSkipEnabled(context) ||
        SettingsRuntime.isWelfareFlutterRewardSkipOnceEnabled(context) ||
        SettingsRuntime.isWelfareComboRewardSkipOnceEnabled(context)
}

internal fun WelfareRewardProbeFeature.acquireFlutterRewardBypassSource(reason: String): String? {
    if (!activeInstallMode.rewardBypassAllowed) {
        return null
    }
    val context = HostSessionStore.contextOrNull()
    if (SettingsRuntime.isWelfareRewardAdSkipEnabled(context)) {
        return "feature"
    }
    if (SettingsRuntime.consumeWelfareFlutterRewardSkipOnce(context)) {
        FeatureHooks.i("Welfare flutter debug-once bypass consumed: reason=$reason")
        return "debugOnce"
    }
    return null
}

internal fun WelfareRewardProbeFeature.acquireComboRewardBypassSource(reason: String): String? {
    if (!activeInstallMode.rewardBypassAllowed) {
        return null
    }
    val context = HostSessionStore.contextOrNull()
    if (SettingsRuntime.isWelfareComboRewardSkipEnabled(context)) {
        return "feature"
    }
    if (SettingsRuntime.consumeWelfareComboRewardSkipOnce(context)) {
        FeatureHooks.i("Welfare combo debug-once bypass consumed: reason=$reason")
        return "debugOnce"
    }
    return null
}

internal fun WelfareRewardProbeFeature.acquireH5RewardBridgeBypassSource(reason: String): String? {
    if (!activeInstallMode.rewardBypassAllowed) {
        return null
    }
    val context = HostSessionStore.contextOrNull()
    if (SettingsRuntime.isWelfareRewardAdSkipEnabled(context)) {
        return "feature"
    }
    if (SettingsRuntime.consumeWelfareFlutterRewardSkipOnce(context)) {
        FeatureHooks.i("Welfare H5 reward bridge debug-once bypass consumed: reason=$reason")
        return "debugOnce"
    }
    return null
}

internal const val FIRST_VERBOSE_HITS = 20L
internal const val SAMPLE_INTERVAL = 50L
internal const val QQMINI_BROWSER_MATCH_WINDOW_MS = 30_000L
internal const val TANGRAM_REWARD_AD_CLASS = QDReaderHost.Classes.TANGRAM_REWARD_AD
internal const val H5_REWARD_WEBVIEW_PLUGIN_CLASS = QDReaderHost.Classes.WEBVIEW_PLUGIN_J1
internal const val H5_GAME_WEBVIEW_PLUGIN_CLASS = QDReaderHost.Classes.WEBVIEW_PLUGIN_V1
internal const val REWARD_VIDEO_CONFIG_CLASS = QDReaderHost.Classes.REWARD_VIDEO_CONFIG
