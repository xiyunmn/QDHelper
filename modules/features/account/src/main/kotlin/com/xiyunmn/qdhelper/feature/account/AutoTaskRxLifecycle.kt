package com.xiyunmn.qdhelper.feature.account

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Completion/error/disposal, rather than onNext or a wall-clock watchdog, ends async ownership. */
internal object AutoTaskRxLifecycle {
    fun wrap(source: Any, timeoutMillis: Long? = null, finished: () -> Unit): Any {
        val take = FeatureHooks.methodsInHierarchy(source.javaClass).first { method ->
            method.name == "take" && method.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
        }
        val single = requireNotNull(FeatureHooks.invoke(take, source, 1L))
        val timed = if (timeoutMillis == null) single else {
            val timeout = FeatureHooks.methodsInHierarchy(single.javaClass).first { method ->
                method.name == "timeout" && method.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, TimeUnit::class.java))
            }
            requireNotNull(FeatureHooks.invoke(timeout, single, timeoutMillis, TimeUnit.MILLISECONDS))
        }
        val finallyMethod = FeatureHooks.methodsInHierarchy(timed.javaClass).single { method ->
            method.name == "doFinally" && method.parameterTypes.size == 1 && method.parameterTypes[0].isInterface
        }
        val actionClass = finallyMethod.parameterTypes[0]
        val run = actionClass.methods.single { method ->
            Modifier.isAbstract(method.modifiers) && method.parameterTypes.isEmpty() && method.returnType == Void.TYPE
        }
        val once = AtomicBoolean(false)
        val action = Proxy.newProxyInstance(actionClass.classLoader, arrayOf(actionClass)) { proxy, method, args ->
            when {
                method.name == run.name && method.parameterTypes.isEmpty() -> { if (once.compareAndSet(false, true)) finished(); null }
                method.name == "toString" -> "QDHelperAutoTaskFinally"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.getOrNull(0)
                else -> error("unexpected Rx action method")
            }
        }
        return requireNotNull(FeatureHooks.invoke(finallyMethod, timed, action))
    }
}
