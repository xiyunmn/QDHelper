package com.xiyunmn.qdhelper.feature.networkidentity

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal fun findStaticNoArgStringMethodOrReport(
    clazz: Class<*>,
    names: List<String>,
    id: String,
    phase: String,
): Method? = names.asSequence()
    .flatMap { name -> FeatureHooks.declaredMethodsNamed(clazz, name).asSequence() }
    .firstOrNull { method ->
        Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.isEmpty() &&
            method.returnType == String::class.java
    } ?: run {
    FeatureHooks.w(
        "Network/identity missing static String method $id: ${QDReaderHost.classLabel(clazz.name)}, " +
            "names=${names.joinToString("/")}, phase=$phase",
    )
    null
}
