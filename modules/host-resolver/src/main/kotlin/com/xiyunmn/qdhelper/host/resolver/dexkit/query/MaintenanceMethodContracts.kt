package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object MaintenanceMethodContracts {
    fun isUserLookupSearchRequest(
        method: Method,
        presenterPackagePrefix: String? = "com.qidian.QDReader.ui.presenter.",
    ): Boolean {
        if (!Modifier.isPublic(method.modifiers) ||
            Modifier.isStatic(method.modifiers) ||
            Modifier.isAbstract(method.modifiers) ||
            method.isSynthetic ||
            method.isBridge ||
            method.returnType != Void.TYPE ||
            !method.parameterTypes.contentEquals(
                arrayOf(String::class.java, ContentValues::class.java, Int::class.javaPrimitiveType),
            ) ||
            presenterPackagePrefix?.let(method.declaringClass.name::startsWith) == false ||
            !Handler.Callback::class.java.isAssignableFrom(method.declaringClass)
        ) {
            return false
        }
        val owner = method.declaringClass
        val hasSearchViewConstructor = owner.declaredConstructors.any { constructor ->
            val params = constructor.parameterTypes
            params.size == 2 &&
                Context::class.java.isAssignableFrom(params[0]) &&
                isUserSearchResultView(params[1])
        }
        val hasResultProcessor = ReflectionAccess.methodsDeclaredBy(owner).any { candidate ->
            Modifier.isPublic(candidate.modifiers) &&
                !Modifier.isStatic(candidate.modifiers) &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.contentEquals(
                    arrayOf(
                        JSONObject::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ),
                )
        }
        return hasSearchViewConstructor && hasResultProcessor
    }

    private fun isUserSearchResultView(type: Class<*>): Boolean {
        if (!type.isInterface) return false
        val methods = ReflectionAccess.methodsDeclaredBy(type)
            .filter { Modifier.isPublic(it.modifiers) && Modifier.isAbstract(it.modifiers) }
        fun count(vararg params: Class<*>): Int = methods.count { candidate ->
            candidate.returnType == Void.TYPE && candidate.parameterTypes.contentEquals(params)
        }
        return methods.any { candidate ->
            candidate.name == "onLoadSuccess" &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.contentEquals(arrayOf(JSONObject::class.java))
        } && methods.any { candidate ->
            candidate.name == "onLoadFailed" &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.contentEquals(arrayOf(String::class.java))
        } && count(JSONObject::class.java) >= 2 &&
            count(String::class.java) >= 1 &&
            count(java.util.List::class.java) >= 3 &&
            count(JSONArray::class.java, JSONArray::class.java) >= 1 &&
            count(Int::class.javaPrimitiveType!!) >= 1 &&
            count(Boolean::class.javaPrimitiveType!!) >= 1
    }
}
