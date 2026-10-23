package com.xiyunmn.qdhelper.feature.dialogs

import android.content.Context
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/** SDK Java boundary: initialization remains available for later user-initiated checks. */
internal object ShiplyUpdateGate {
    private val manualCallbacks = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val manualPresentation = ThreadLocal<Boolean>()
    private val hits = AtomicInteger()

    init {
        FeatureResources.register("dialogs.shiply-update") {
            manualCallbacks.clear()
            manualPresentation.remove()
            hits.set(0)
        }
    }

    fun install(module: HookModule, loader: ClassLoader): Int {
        val manager = FeatureHooks.findClassOrNull("com.tencent.upgrade.core.UpgradeManager", loader) ?: return 0
        val callback = FeatureHooks.findClassOrNull("com.tencent.upgrade.callback.UpgradeStrategyRequestCallback", loader) ?: return 0
        val strategy = FeatureHooks.findClassOrNull("com.tencent.upgrade.bean.UpgradeStrategy", loader) ?: return 0
        val requests = FeatureHooks.allDeclaredMethods(manager).filter { method ->
            val p = method.parameterTypes
            method.name == "checkUpgrade" && !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                p.size in 3..5 && p.last() == callback && p[p.size - 2] == Map::class.java &&
                p.dropLast(2).all { it == Boolean::class.javaPrimitiveType }
        }
        val callbacks = listOf(
            "com.tencent.upgrade.core.DefaultUpgradeStrategyRequestCallback",
            "com.tencent.upgrade.core.UpgradeReqCallbackForUserManualCheck",
        ).mapNotNull { FeatureHooks.findClassOrNull(it, loader) }
        val presentations = callbacks.mapNotNull { type ->
            FeatureHooks.allDeclaredMethods(type).singleOrNull {
                it.name == "tryPopUpgradeDialog" && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(strategy))
            }
        }
        val activity = FeatureHooks.findClassOrNull("com.tencent.upgrade.ui.UpgradeDialogActivity", loader)
        val launch = activity?.let { type -> FeatureHooks.allDeclaredMethods(type).singleOrNull {
            it.name == "launch" && Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, strategy))
        } }
        if (requests.isEmpty() || presentations.size != 2 || launch == null) {
            FeatureHooks.w("Shiply update contract unavailable: requests=${requests.size}, presenters=${presentations.size}, launch=${launch != null}")
            return 0
        }
        var count = 0
        val presentationCount = presentations.count { method ->
            FeatureHooks.hookAround(module, method, "dialog.update.shiply.presentation", "qdhelper.shiply.presentation.${method.declaringClass.name}") { chain ->
                val explicit = chain.thisObject?.javaClass?.name == "com.tencent.upgrade.core.UpgradeReqCallbackForUserManualCheck"
                val manual = explicit || manualCallbacks[chain.thisObject] == true
                if (blocked() && !manual) { hit("presentation"); return@hookAround null }
                val previous = manualPresentation.get()
                manualPresentation.set(manual)
                try { chain.proceed() } finally {
                    if (previous == null) manualPresentation.remove() else manualPresentation.set(previous)
                }
            }
        }
        count += presentationCount
        // Do not install a final gate if the asynchronous manual provenance cannot be preserved.
        if (presentationCount == presentations.size && FeatureHooks.hookAround(
                module, launch, "dialog.update.shiply.launch", "qdhelper.shiply.launch",
            ) { chain ->
                if (blocked() && manualPresentation.get() != true) { hit("launch"); null } else chain.proceed()
            }) count++
        requests.forEach { method ->
            if (FeatureHooks.hookAround(module, method, "dialog.update.shiply.request", "qdhelper.shiply.request.${method.parameterTypes.size}") { chain ->
                // The host uses false during startup and true for its explicit check action.
                // SDK calls this forceRequestRemoteStrategy; it is not an account entitlement.
                val manual = chain.getArg(0) == true ||
                    chain.args.lastOrNull()?.javaClass?.name == "com.tencent.upgrade.core.UpgradeReqCallbackForUserManualCheck"
                chain.args.lastOrNull()?.let { manualCallbacks[it] = manual }
                if (blocked() && !manual) { hit("request"); null } else chain.proceed()
            }) count++
        }
        FeatureHooks.i("Shiply update gates installed: count=$count, requests=${requests.size}, manual=callback-scoped")
        return count
    }

    private fun blocked() = SettingsRuntime.isUpdateCheckSuppressed(HostSessionStore.contextOrNull())

    private fun hit(stage: String) {
        if (hits.incrementAndGet() <= 12) FeatureHooks.i("Auto-update blocked: source=shiply, stage=$stage")
    }
}
