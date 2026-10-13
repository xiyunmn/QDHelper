package com.xiyunmn.qdhelper.feature.welfare.hook

import android.app.Activity
import android.content.Context
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.welfare.action.buildImmediateRewardEventObservable
import com.xiyunmn.qdhelper.feature.welfare.action.buildRewardBridgeKey
import com.xiyunmn.qdhelper.feature.welfare.action.dispatchH5RewardStatusSequence
import com.xiyunmn.qdhelper.feature.welfare.action.dispatchQqMiniRewardCallbacks
import com.xiyunmn.qdhelper.feature.welfare.action.dispatchTangramRewardCallbacks
import com.xiyunmn.qdhelper.feature.welfare.action.sendFlutterSuccess
import com.xiyunmn.qdhelper.feature.welfare.action.writeObjectField
import com.xiyunmn.qdhelper.feature.welfare.model.browserIntentSummary
import com.xiyunmn.qdhelper.feature.welfare.model.contextLabel
import com.xiyunmn.qdhelper.feature.welfare.model.flutterArgsSummary
import com.xiyunmn.qdhelper.feature.welfare.model.flutterMethodName
import com.xiyunmn.qdhelper.feature.welfare.model.isLikelyWelfareBrowserIntent
import com.xiyunmn.qdhelper.feature.welfare.model.maskId
import com.xiyunmn.qdhelper.feature.welfare.model.readBooleanField
import com.xiyunmn.qdhelper.feature.welfare.model.readNoArgMethod
import com.xiyunmn.qdhelper.feature.welfare.model.readObjectField
import com.xiyunmn.qdhelper.feature.welfare.model.rewardVideoConfigSummary
import com.xiyunmn.qdhelper.feature.welfare.model.safeScalarSummary
import com.xiyunmn.qdhelper.feature.welfare.model.typeLabel
import com.xiyunmn.qdhelper.feature.welfare.model.WELFARE_FLUTTER_METHODS
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

object WelfareRewardProbeFeature : InstallableFeature {
    override val name: String = "WelfareRewardProbeFeature"

    internal val hitCounters = ConcurrentHashMap<String, AtomicLong>()
    private val lifecycleSubscriptions = mutableListOf<AutoCloseable>()
    internal val recentWelfareBrowserResumeAtMs = AtomicLong(0L)
    @Volatile
    internal var activeInstallMode = WelfareRewardInstallMode.PROBE_ONLY
    @Volatile
    internal var recentWelfareBrowserActivityRef: WeakReference<Activity>? = null
    @Volatile
    internal var currentActivityRef: WeakReference<Activity>? = null

    init {
        FeatureResources.register("welfare-reward-runtime") {
            synchronized(lifecycleSubscriptions) {
                lifecycleSubscriptions.forEach { runCatching(it::close) }
                lifecycleSubscriptions.clear()
            }
            recentWelfareBrowserActivityRef = null
            currentActivityRef = null
        }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        installInternal(
            module = module,
            classLoader = classLoader,
            processName = processName,
            phase = phase,
            mode = WelfareRewardInstallMode.PROBE_ONLY,
        )
    }

    internal fun installInternal(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
        mode: WelfareRewardInstallMode,
    ) {
        activeInstallMode = mode
        val isMainProcess = QDReaderHost.isMainProcess(processName)
        val isMiniProcess = QDReaderHost.isMiniProcess(processName)
        if (!isMainProcess && !isMiniProcess) {
            return
        }

        var installedHooks = 0
        if (isMainProcess) {
            installedHooks += WelfareRewardPopup.install(module, classLoader)
            installedHooks += installBrowserActivityProbe()
            installedHooks += installWelfareMethodCallProbe(module, classLoader, phase)
            installedHooks += installShowGdtAdProbe(module, classLoader, phase)
            installedHooks += installGdtForQdProbe(module, classLoader, phase)
            installedHooks += installTangramRewardAdProbe(module, classLoader, phase)
            installedHooks += installH5RewardBridgeProbe(module, classLoader, phase)
            installedHooks += installWelfareTaskApiProbe(module, classLoader, phase)
            installedHooks += installFinishWatchApiProbe(module, classLoader, phase)
            installedHooks += installFinishWatchBusinessProbe(module, classLoader, phase)
            installedHooks += WelfareClaimRecovery.install(module, classLoader)
        }
        if (isMainProcess || isMiniProcess) {
            installedHooks += installQqMiniRewardVideoProbe(module, classLoader, phase)
        }
        // FreeWelfareRewardBugFixHelper is useful for static research, but hooking it
        // during packageReady can stall the feature install queue on current builds.

        FeatureHooks.i(
            "Welfare reward ${mode.logLabel} phase finished: " +
                "phase=$phase, process=$processName, installedHooks=$installedHooks",
        )
    }

    private fun installBrowserActivityProbe(): Int = synchronized(lifecycleSubscriptions) {
        if (lifecycleSubscriptions.isNotEmpty()) return@synchronized lifecycleSubscriptions.size
        ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("welfare"),
            id = "welfare.browser.activity.onCreate",
            created = { activity ->
                if (activity.javaClass.name == QDReaderHost.Classes.QD_BROWSER_ACTIVITY) {
                    logLifecycleHit(
                        "welfare.browser.activity.onCreate",
                        "activity=QDBrowserActivity, ${activity.intent.browserIntentSummary()}",
                    )
                }
            },
        )?.let(lifecycleSubscriptions::add)
        ActivityLifecycleCallbacks.subscribe(
            owner = OwnerId("welfare"),
            id = "welfare.browser.activity.onResume",
            resumed = { activity ->
                currentActivityRef = WeakReference(activity)
                if (activity.javaClass.name == QDReaderHost.Classes.QD_BROWSER_ACTIVITY) {
                    if (activity.intent.isLikelyWelfareBrowserIntent()) {
                        recentWelfareBrowserResumeAtMs.set(System.currentTimeMillis())
                        recentWelfareBrowserActivityRef = WeakReference(activity)
                    }
                    logLifecycleHit("welfare.browser.activity.onResume", "activity=QDBrowserActivity")
                }
                if (activity.javaClass.name == QDReaderHost.Classes.TANGRAM_REWARD_VIDEO_ACTIVITY) {
                    logLifecycleHit(
                        "welfare.rewardVideo.activity.onResume",
                        "activity=TangramRewardVideoActivity",
                    )
                }
            },
        )?.let(lifecycleSubscriptions::add)
        lifecycleSubscriptions.size
    }

    private fun installWelfareMethodCallProbe(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val methods = resolvedMethods(
            classLoader = classLoader,
            key = WelfareTargetKeys.flutterMethodCall,
        ) { method ->
            method.parameterTypes.size == 2 &&
                method.parameterTypes.getOrNull(0)?.name == "io.flutter.plugin.common.MethodCall" &&
                method.parameterTypes.getOrNull(1)?.name == "io.flutter.plugin.common.MethodChannel\$Result"
        }
        return methods.sumOf { method ->
            installAround(module, "welfare.flutter.methodCall", method, phase) { chain ->
                val methodCall = chain.args.getOrNull(0)
                val flutterMethod = methodCall.flutterMethodName()
                val shouldLog = flutterMethod in WELFARE_FLUTTER_METHODS
                val beforeRewardState = chain.thisObject.readBooleanField("gdtRewardSec")
                val gotoBypassSource = if (flutterMethod == "gotoGdtRewardVideo") {
                    acquireFlutterRewardBypassSource("flutter.gotoGdtRewardVideo")
                } else {
                    null
                }
                if (gotoBypassSource != null) {
                    chain.thisObject.writeObjectField("gdtRewardSec", true)
                    FeatureHooks.i(
                        "Welfare flutter reward bypass applied at gotoGdtRewardVideo: " +
                            "source=$gotoBypassSource, args=${methodCall.flutterArgsSummary(flutterMethod)}, gdtRewardSec=true",
                    )
                    return@installAround null
                }
                val askBypassSource = if (flutterMethod == "askGdtRewardVideoResult") {
                    acquireFlutterRewardBypassSource("flutter.askGdtRewardVideoResult")
                } else {
                    null
                }
                if (askBypassSource != null) {
                    chain.thisObject.writeObjectField("gdtRewardSec", false)
                    chain.args.getOrNull(1).sendFlutterSuccess(1)
                    FeatureHooks.i(
                        "Welfare flutter reward bypass applied at askGdtRewardVideoResult: " +
                            "source=$askBypassSource, result=1",
                    )
                    return@installAround null
                }
                if (shouldLog) {
                    logHit(
                        id = "welfare.flutter.methodCall",
                        method = method,
                        detail = "begin method=$flutterMethod, args=${methodCall.flutterArgsSummary(flutterMethod)}, " +
                            "gdtRewardSecBefore=$beforeRewardState",
                    )
                }
                val result = chain.proceed()
                if (shouldLog) {
                    val afterRewardState = chain.thisObject.readBooleanField("gdtRewardSec")
                    logHit(
                        id = "welfare.flutter.methodCall",
                        method = method,
                        detail = "end method=$flutterMethod, gdtRewardSecAfter=$afterRewardState, result=void",
                    )
                }
                result
            }
        }
    }

    private fun installShowGdtAdProbe(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val methods = resolvedMethods(
            classLoader = classLoader,
            key = WelfareTargetKeys.flutterShowGdtAd,
        ) { method ->
            method.parameterTypes.size == 3 &&
                method.parameterTypes.getOrNull(0)?.let(Activity::class.java::isAssignableFrom) == true &&
                method.parameterTypes.getOrNull(1) == String::class.java &&
                method.parameterTypes.getOrNull(2) == String::class.java
        }
        return methods.sumOf { method ->
            installAround(module, "welfare.flutter.showGdtAD", method, phase) { chain ->
                val appId = chain.args.getOrNull(1) as? String
                val posId = chain.args.getOrNull(2) as? String
                val bypassSource = acquireFlutterRewardBypassSource("flutter.showGdtAD")
                if (bypassSource != null) {
                    chain.thisObject.writeObjectField("gdtRewardSec", true)
                    FeatureHooks.i(
                        "Welfare flutter reward bypass applied at showGdtAD: " +
                            "source=$bypassSource, appId=${appId.maskId()}, posId=${posId.maskId()}, gdtRewardSec=true",
                    )
                    return@installAround null
                }
                val hadDisposable = chain.thisObject?.readObjectField("mShowRewardVideoDisposable") != null
                logHit(
                    id = "welfare.flutter.showGdtAD",
                    method = method,
                    detail = "begin appId=${appId.maskId()}, posId=${posId.maskId()}, hadDisposable=$hadDisposable",
                )
                val result = chain.proceed()
                val hasDisposable = chain.thisObject?.readObjectField("mShowRewardVideoDisposable") != null
                logHit(
                    id = "welfare.flutter.showGdtAD",
                    method = method,
                    detail = "end appId=${appId.maskId()}, posId=${posId.maskId()}, hasDisposable=$hasDisposable",
                )
                result
            }
        }
    }

    private fun installGdtForQdProbe(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        // The resolver anchors on the eight-argument shape with RewardVideoConfig at index 3.
        val methods = resolvedMethods(
            classLoader = classLoader,
            key = WelfareTargetKeys.gdtRewardObservable,
        ) { method ->
            method.parameterTypes.size == 8 &&
                method.parameterTypes[3].name == REWARD_VIDEO_CONFIG_CLASS
        }
        return methods.sumOf { method ->
            installAround(module, "welfare.gdtForQd.reward", method, phase) { chain ->
                val appId = chain.args.getOrNull(1) as? String
                val adCodeId = chain.args.getOrNull(2) as? String
                val bypassSource = acquireComboRewardBypassSource("gdtForQd.reward")
                if (bypassSource != null) {
                    FeatureHooks.i(
                        "Welfare combo reward bypass applied at GDTForQD reward observable: " +
                            "source=$bypassSource, appId=${appId.maskId()}, adCodeId=${adCodeId.maskId()}, return=rewardThenClose",
                    )
                    return@installAround chain.thisObject.buildImmediateRewardEventObservable()
                        ?: run {
                            FeatureHooks.w("Welfare reward skip once fallback: failed to build reward event observable")
                            chain.proceed()
                        }
                }
                val muted = chain.args.getOrNull(4) as? Boolean
                val hasUserStatus = chain.args.getOrNull(5) != null
                val hasPosArray = chain.args.getOrNull(6) != null
                val dynamicData = chain.args.getOrNull(7) as? String
                logHit(
                    id = "welfare.gdtForQd.reward",
                    method = method,
                    detail = "begin appId=${appId.maskId()}, adCodeId=${adCodeId.maskId()}, muted=$muted, " +
                        "hasUserStatus=$hasUserStatus, hasPosArray=$hasPosArray, dynamicDataLen=${dynamicData?.length ?: 0}",
                )
                val result = chain.proceed()
                logHit(
                    id = "welfare.gdtForQd.reward",
                    method = method,
                    detail = "end result=${result.typeLabel()}",
                )
                result
            }
        }
    }

    private fun installQqMiniRewardVideoProbe(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        var installedHooks = 0
        installedHooks += resolvedMethods(
            classLoader = classLoader,
            key = WelfareTargetKeys.qqMiniLoadAd,
        ) { method ->
            method.parameterTypes.toList() == listOf(Context::class.java)
        }
            .sumOf { method ->
                installAround(module, "welfare.qqmini.reward.loadAD", method, phase) { chain ->
                    val context = chain.args.getOrNull(0) as? Context
                    val welfareContext = isQqMiniWelfareRewardContext(context)
                    logHit(
                        id = "welfare.qqmini.reward.loadAD",
                        method = method,
                        detail = "begin context=${context.contextLabel()}, welfareContext=$welfareContext",
                    )
                    val result = chain.proceed()
                    logHit(
                        id = "welfare.qqmini.reward.loadAD",
                        method = method,
                        detail = "end result=void",
                    )
                    result
                }
            }
        installedHooks += resolvedMethods(
            classLoader = classLoader,
            key = WelfareTargetKeys.qqMiniShowAd,
        ) { method ->
            method.parameterTypes.toList() == listOf(Context::class.java, String::class.java)
        }
            .sumOf { method ->
                installAround(module, "welfare.qqmini.reward.showAD", method, phase) { chain ->
                    val context = chain.args.getOrNull(0) as? Context
                    val compId = chain.args.getOrNull(1) as? String
                    val welfareContext = isQqMiniWelfareRewardContext(context)
                    val bypassSource = acquireFlutterRewardBypassSource("qqmini.reward.showAD")
                    if (bypassSource != null) {
                        chain.thisObject.writeObjectField("mShowCompId", compId)
                        chain.thisObject.dispatchQqMiniRewardCallbacks(compId)
                        FeatureHooks.i(
                            "Welfare qqmini reward bypass applied at showAD: " +
                                "source=$bypassSource, context=${context.contextLabel()}, welfareContext=$welfareContext, " +
                                "compId=${compId.maskId()}, rewardThenClose=true",
                        )
                        return@installAround null
                    }
                    logHit(
                        id = "welfare.qqmini.reward.showAD",
                        method = method,
                        detail = "begin context=${context.contextLabel()}, welfareContext=$welfareContext, compId=${compId.maskId()}",
                    )
                    val result = chain.proceed()
                    logHit(
                        id = "welfare.qqmini.reward.showAD",
                        method = method,
                        detail = "end result=void",
                    )
                    result
                }
            }
        return installedHooks
    }

    private fun installTangramRewardAdProbe(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val methods = resolvedMethods(
            classLoader = classLoader,
            key = WelfareTargetKeys.tangramRewardShowAd,
        ) { method ->
            method.parameterTypes.isEmpty()
        }
        return methods.sumOf { method ->
            installAround(module, "welfare.tangram.reward.showAD", method, phase) { chain ->
                val welfareContext = isRecentWelfareBrowserContext()
                val hasPassThroughData = chain.thisObject.readNoArgMethod("getPassThroughData") != null
                val bypassSource = if (welfareContext) {
                    acquireFlutterRewardBypassSource("tangram.reward.showAD")
                } else {
                    null
                }
                if (bypassSource != null) {
                    chain.thisObject.dispatchTangramRewardCallbacks()
                    FeatureHooks.i(
                        "Welfare tangram reward bypass applied at showAD: " +
                            "source=$bypassSource, welfareContext=$welfareContext, " +
                            "hasPassThroughData=$hasPassThroughData, rewardThenClose=true",
                    )
                    return@installAround null
                }
                logHit(
                    id = "welfare.tangram.reward.showAD",
                    method = method,
                    detail = "begin welfareContext=$welfareContext, hasPassThroughData=$hasPassThroughData",
                )
                val result = chain.proceed()
                logHit(
                    id = "welfare.tangram.reward.showAD",
                    method = method,
                    detail = "end result=void",
                )
                result
            }
        }
    }

    private fun installH5RewardBridgeProbe(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        var installedHooks = 0

        // Resolve the WebViewPlugin method by its void(int, String, RewardVideoConfig, String, String)
        // shape with RewardVideoConfig at index 2.
        resolvedMethods(
            classLoader = classLoader,
            key = WelfareTargetKeys.h5RewardConfigMid,
        ) { method ->
            method.parameterTypes.size == 5 &&
                method.parameterTypes[2].name == REWARD_VIDEO_CONFIG_CLASS
        }.forEach { method ->
                    installedHooks += installAround(module, "welfare.h5.rewardBridge.configMid", method, phase) { chain ->
                        val callbackId = (chain.args.getOrNull(0) as? Number)?.toInt() ?: -1
                        val scene = chain.args.getOrNull(1) as? String
                        val rewardVideoConfig = chain.args.getOrNull(2)
                        val supportTaskType = chain.args.getOrNull(3) as? String
                        val trackToken = chain.args.getOrNull(4) as? String
                        val welfareContext = isRecentWelfareBrowserContext()
                        val configSummary = rewardVideoConfig.rewardVideoConfigSummary()
                        val skipSource = if (welfareContext) {
                            acquireH5RewardBridgeBypassSource("h5.configMid")
                        } else {
                            null
                        }
                        if (skipSource != null) {
                            val key = buildRewardBridgeKey(rewardVideoConfig)
                            chain.thisObject.dispatchH5RewardStatusSequence(
                                callbackId = callbackId,
                                statusField = "status",
                                extraFields = mapOf("key" to key),
                            )
                            scheduleRewardPopupSuppression(currentWelfareBrowserActivity(), "h5.configMid")
                            FeatureHooks.i(
                                "Welfare H5 reward bridge skip once consumed at reward configMid: " +
                                    "source=$skipSource, callbackId=$callbackId, scene=${scene.maskId()}, " +
                                    "supportTaskTypeLen=${supportTaskType?.length ?: 0}, trackToken=${trackToken.maskId()}, " +
                                    "config=$configSummary",
                            )
                            return@installAround null
                        }
                        logHit(
                            id = "welfare.h5.rewardBridge.configMid",
                            method = method,
                            detail = "begin callbackId=$callbackId, welfareContext=$welfareContext, scene=${scene.maskId()}, " +
                                "supportTaskTypeLen=${supportTaskType?.length ?: 0}, trackToken=${trackToken.maskId()}, " +
                                "config=$configSummary",
                        )
                        val result = chain.proceed()
                        logHit(
                            id = "welfare.h5.rewardBridge.configMid",
                            method = method,
                            detail = "end callbackId=$callbackId, result=void",
                        )
                        result
                    }
                }

        // Anchor on void(int, String, String, String, RewardVideoConfig) with the config last.
        resolvedMethods(
            classLoader = classLoader,
            key = WelfareTargetKeys.h5RewardConfigLast,
        ) { method ->
            method.parameterTypes.size == 5 &&
                method.parameterTypes.lastOrNull()?.name == REWARD_VIDEO_CONFIG_CLASS
        }.forEach { method ->
                    installedHooks += installAround(module, "welfare.h5.rewardBridge.configLast", method, phase) { chain ->
                        val callbackId = (chain.args.getOrNull(0) as? Number)?.toInt() ?: -1
                        val scene = chain.args.getOrNull(1) as? String
                        val eventToken = chain.args.getOrNull(2) as? String
                        val trackToken = chain.args.getOrNull(3) as? String
                        val rewardVideoConfig = chain.args.getOrNull(4)
                        val welfareContext = isRecentWelfareBrowserContext()
                        logHit(
                            id = "welfare.h5.rewardBridge.configLast",
                            method = method,
                            detail = "begin callbackId=$callbackId, welfareContext=$welfareContext, scene=${scene.maskId()}, " +
                                "eventToken=${eventToken.maskId()}, trackToken=${trackToken.maskId()}, " +
                                "config=${rewardVideoConfig.rewardVideoConfigSummary()}",
                        )
                        val result = chain.proceed()
                        logHit(
                            id = "welfare.h5.rewardBridge.configLast",
                            method = method,
                            detail = "end callbackId=$callbackId, result=void",
                        )
                        result
                    }
                }

        // Anchor on int(Activity, JSONObject, int) without depending on the method letter.
        resolvedMethods(
            classLoader = classLoader,
            key = WelfareTargetKeys.h5RewardGame,
        ) { method ->
            method.parameterTypes.size == 3 &&
                method.parameterTypes[0].name == Activity::class.java.name
        }.forEach { method ->
                    installedHooks += installAround(module, "welfare.h5.rewardBridge.game", method, phase) { chain ->
                        val activity = chain.args.getOrNull(0) as? Activity
                        val request = chain.args.getOrNull(1) as? JSONObject
                        val callbackId = (chain.args.getOrNull(2) as? Number)?.toInt() ?: -1
                        val welfareContext = isWelfareBrowserActivity(activity) || isRecentWelfareBrowserContext()
                        val posId = request?.optString("posId")
                        val adType = request?.optInt("adType")
                        val skipSource = if (welfareContext) {
                            acquireH5RewardBridgeBypassSource("h5.game")
                        } else {
                            null
                        }
                        if (skipSource != null) {
                            chain.thisObject.dispatchH5RewardStatusSequence(
                                callbackId = callbackId,
                                statusField = "status",
                            )
                            scheduleRewardPopupSuppression(activity ?: currentWelfareBrowserActivity(), "h5.game")
                            FeatureHooks.i(
                                "Welfare H5 reward bridge skip once consumed at game bridge: " +
                                    "source=$skipSource, callbackId=$callbackId, welfareContext=$welfareContext, " +
                                    "adType=$adType, posId=${posId.maskId()}",
                            )
                            return@installAround -1
                        }
                        logHit(
                            id = "welfare.h5.rewardBridge.game",
                            method = method,
                            detail = "begin callbackId=$callbackId, welfareContext=$welfareContext, adType=$adType, " +
                                "posId=${posId.maskId()}",
                        )
                        val result = chain.proceed()
                        logHit(
                            id = "welfare.h5.rewardBridge.game",
                            method = method,
                            detail = "end callbackId=$callbackId, result=${result.safeScalarSummary()}",
                        )
                        result
                    }
                }

        return installedHooks
    }

}
