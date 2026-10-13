package com.xiyunmn.qdhelper.feature.dialogs

import android.content.Context
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.LinkedHashMap

/** Structural policy for blocking automatic update work while preserving About-page manual checks. */
internal object AutoUpdateSuppressionPolicy {
    fun callbackAnchor(methods: List<Method>, callbackClasses: Collection<Class<*>>): Method? {
        val callbacks = callbackClasses.toSet()
        return methods.asSequence()
            .filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    supportsBlockedResult(method) &&
                    method.parameterTypes.any(callbacks::contains)
            }
            .sortedBy(Method::toGenericString)
            .firstOrNull()
    }

    fun blockableMethods(methods: List<Method>, callbackClass: Class<*>): List<Method> {
        val unique = LinkedHashMap<String, Method>()
        methods.filter { method ->
            Modifier.isStatic(method.modifiers) &&
                supportsBlockedResult(method) &&
                (callbackClass in method.parameterTypes || isCachedForceUpdateGate(method))
        }
            .forEach { method -> unique.putIfAbsent(method.toGenericString(), method) }
        return unique.values.sortedBy(Method::toGenericString)
    }

    fun callbackArgument(method: Method, callbackClass: Class<*>, args: List<Any?>): Any? {
        val index = method.parameterTypes.indexOfFirst { it == callbackClass }
        return args.getOrNull(index).takeIf(callbackClass::isInstance)
    }

    fun contexts(thisObject: Any?, args: List<Any?>, callback: Any?): List<Context> {
        val contexts = mutableListOf<Context>()
        fun add(value: Any?) {
            val context = value as? Context ?: return
            if (contexts.none { it === context }) contexts += context
        }

        add(thisObject)
        args.forEach(::add)
        add(callback)
        if (callback != null) {
            FeatureHooks.allDeclaredFields(callback.javaClass)
                .filter { field -> Context::class.java.isAssignableFrom(field.type) }
                .forEach { field -> add(FeatureHooks.read(field, callback)) }
        }
        return contexts
    }

    fun isAboutManualContext(contexts: Iterable<Context>): Boolean =
        isAboutManualClassNames(contexts.map { it.javaClass.name })

    internal fun isAboutManualClassNames(classNames: Iterable<String>): Boolean =
        classNames.any { it == QDReaderHost.Classes.ABOUT_ACTIVITY }

    fun blockedResult(method: Method): Any? =
        if (method.returnType == Boolean::class.javaPrimitiveType) false else null

    fun hookKey(method: Method): String {
        val signature = buildList {
            add(method.name)
            method.parameterTypes.forEach { add(it.name) }
            add(method.returnType.name)
        }.joinToString("_")
        return signature.replace(NON_ID_CHARACTER, "_").trim('_')
    }

    private fun supportsBlockedResult(method: Method): Boolean =
        method.returnType == Void.TYPE || method.returnType == Boolean::class.javaPrimitiveType

    private fun isCachedForceUpdateGate(method: Method): Boolean =
        Modifier.isPublic(method.modifiers) &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            method.parameterTypes.size == 1 &&
            Context::class.java.isAssignableFrom(method.parameterTypes[0])

    private val NON_ID_CHARACTER = Regex("[^A-Za-z0-9]+")
}
