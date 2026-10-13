package com.xiyunmn.qdhelper.feature.api.runtime

import android.content.Context
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object FeatureGateSupport {
    private val hitCounters = ConcurrentHashMap<String, AtomicLong>()

    fun installNoArgNoOp(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
        id: String,
        hookId: String,
        className: String,
        methodName: String,
        shouldBlock: (Context?) -> Boolean,
    ): Int {
        val owner = FeatureHooks.findClassOrNull(className, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(owner, methodName) ?: return 0
        return installVoidNoOp(module, phase, id, hookId, method, shouldBlock)
    }

    fun installVoidNoOp(
        module: HookModule,
        phase: String,
        id: String,
        hookId: String,
        method: Method,
        shouldBlock: (Context?) -> Boolean,
    ): Int {
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "feature.gate.$id:${HostSymbols.classLabel(method.declaringClass.name)}.${method.name}",
            id = hookId,
        ) { chain ->
            if (!shouldBlock(HostSessionStore.contextOrNull())) return@hookAround chain.proceed()
            logHit(id, hookId)
            null
        }
        if (success) {
            FeatureHooks.i("Feature gate installed $id: ${method.name}, phase=$phase")
        }
        return if (success) 1 else 0
    }

    private fun logHit(id: String, hookId: String) {
        val count = hitCounters.computeIfAbsent(hookId) { AtomicLong() }.incrementAndGet()
        if (count <= 20L || count % 50L == 0L) {
            FeatureHooks.i("Feature gate hit $id#$count: action=short-circuit")
        }
    }
}
