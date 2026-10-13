package com.xiyunmn.qdhelper.feature.pagecustomization

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import java.util.Collections
import java.util.IdentityHashMap

internal object PageCustomizationHostAccess {
    fun identitySet(): MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    fun invokeStringGetter(source: Any?, methodName: String): String? =
        invokeNoArgValue(source, methodName) as? String

    fun invokeIntMethod(source: Any?, methodName: String): Int? =
        invokeNoArgValue(source, methodName) as? Int

    fun invokeIntMethod(source: Any?, methodName: String, value: Int): Int? {
        source ?: return null
        val method = FeatureHooks.declaredMethodsNamed(source.javaClass, methodName)
            .singleOrNull { candidate ->
                candidate.parameterTypes.size == 1 && candidate.parameterTypes[0] == Integer.TYPE
            } ?: return null
        return FeatureHooks.invoke(method, source, value) as? Int
    }

    fun invokeNoArgValue(source: Any?, methodName: String): Any? {
        source ?: return null
        val method = FeatureHooks.declaredMethodsNamed(source.javaClass, methodName)
            .singleOrNull { candidate -> candidate.parameterTypes.isEmpty() }
            ?: return null
        return FeatureHooks.invoke(method, source)
    }

    fun fieldValue(source: Any?, fieldName: String): Any? =
        source?.let { FeatureHooks.readDeclaredFieldValue(it, fieldName) }
}
