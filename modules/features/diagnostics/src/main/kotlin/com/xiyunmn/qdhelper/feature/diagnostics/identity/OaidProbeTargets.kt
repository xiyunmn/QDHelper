package com.xiyunmn.qdhelper.feature.diagnostics.identity

import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object OaidProbeTargets {
    val getterNames = setOf("getOAID", "getVAID", "getAAID")

    fun concreteStringGetters(methods: Iterable<Method>): List<Method> = methods
        .filter { method ->
            method.name in getterNames &&
                method.parameterTypes.isEmpty() &&
                method.returnType == String::class.java &&
                !Modifier.isAbstract(method.modifiers) &&
                !Modifier.isStatic(method.modifiers)
        }
        .sortedBy(Method::getName)
}
