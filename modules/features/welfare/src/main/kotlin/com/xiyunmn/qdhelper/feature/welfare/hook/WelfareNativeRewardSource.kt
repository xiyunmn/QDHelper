package com.xiyunmn.qdhelper.feature.welfare.hook

import android.app.Activity
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Replace only the ad source; the host still verifies risk and processes its real reward response. */
internal fun WelfareRewardProbeFeature.installNativeRewardSource(
    module: HookModule,
    classLoader: ClassLoader,
    phase: String,
): Int {
    val source = HostTargetResolver.resolveMethod(HostSessionStore.contextOrNull(), classLoader,
        HostSessionStore.processName, WelfareTargetKeys.nativeAdSource) ?: return 0
    val observable = FeatureHooks.findClassOrNull(HostSymbols.Classes.RX_OBSERVABLE, classLoader) ?: return 0
    if (!source.returnType.isAssignableFrom(observable)) return 0
    val just = FeatureHooks.findDeclaredMethodOrNull(observable, "just", Any::class.java) ?: return 0
    val delay = FeatureHooks.findDeclaredMethodOrNull(observable, "delay",
        Long::class.javaPrimitiveType!!, TimeUnit::class.java) ?: return 0
    val concat = FeatureHooks.findDeclaredMethodOrNull(observable, "concatWith", source.returnType) ?: return 0
    return installAround(module, "welfare.native.rewardSource", source, phase) { chain ->
        val activity = chain.args.getOrNull(2) as? Activity
        if (!isWelfareBrowserActivity(activity)) return@installAround chain.proceed()
        val bypass = acquireFlutterRewardBypassSource("native.rewardSource")
            ?: return@installAround chain.proceed()
        @Suppress("UNCHECKED_CAST")
        val riskReference = chain.args.firstOrNull() as? AtomicReference<Any?>
            ?: return@installAround chain.proceed()
        val risk = chain.args.getOrNull(6) ?: return@installAround chain.proceed()
        val events = runCatching {
            val reward = requireNotNull(FeatureHooks.invoke(just, null, 5))
            val close = requireNotNull(FeatureHooks.invoke(just, null, 8))
            val delayedClose = requireNotNull(FeatureHooks.invoke(delay, close, 260L, TimeUnit.MILLISECONDS))
            requireNotNull(FeatureHooks.invoke(concat, reward, delayedClose))
        }.getOrElse {
            FeatureHooks.w("Welfare native reward source unavailable: " + it.javaClass.simpleName)
            return@installAround chain.proceed()
        }
        // This is the assignment performed by the original selector before choosing GDT/CSJ.
        riskReference.set(risk)
        FeatureHooks.i("Welfare native reward source replaced: source=" + bypass)
        events
    }
}
