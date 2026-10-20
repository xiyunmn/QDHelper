package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.content.Context
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import java.lang.reflect.Modifier

/** Structural contracts for host infrastructure whose short class names are not stable. */
internal object HostInfrastructureContracts {
    private const val BASE_CONFIG = "com.qidian.QDReader.repository.entity.config.BaseConfigBean"
    private const val JSON = "org.json.JSONObject"

    fun isConfigLoader(type: Class<*>): Boolean {
        if (!type.name.startsWith("com.qidian.QDReader.component.config.") ||
            !Modifier.isAbstract(type.modifiers)
        ) return false
        val fields = ReflectionAccess.fieldsDeclaredBy(type).filter { !Modifier.isStatic(it.modifiers) }
        if (fields.none { it.type == Context::class.java } || fields.none { it.type.name == BASE_CONFIG } ||
            fields.count { it.type == String::class.java } != 1
        ) return false
        return ReflectionAccess.methodsDeclaredBy(type).count {
            Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE && it.parameterTypes.map { parameter -> parameter.name } == listOf(BASE_CONFIG)
        } == 2
    }

    fun isWebViewPlugin(type: Class<*>): Boolean {
        if (!type.name.startsWith("com.qidian.QDReader.framework.webview.")) return false
        val methods = ReflectionAccess.methodsDeclaredBy(type).filter {
            Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE
        }
        return listOf(
            listOf("java.lang.String", JSON, "int"),
            listOf("java.lang.String", JSON),
            listOf("java.lang.String", JSON, JSON),
        ).all { parameters -> methods.count { it.parameterTypes.map { parameter -> parameter.name } == parameters } == 1 }
    }

    fun extendsWebViewPlugin(type: Class<*>): Boolean =
        generateSequence(type.superclass) { it.superclass }.any(::isWebViewPlugin)
}
