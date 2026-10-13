package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import android.app.Activity
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import com.xiyunmn.qdhelper.host.resolver.reflection.ReflectionAccess
import java.lang.reflect.Modifier

internal fun isPublicAbstractOverride(method: Method): Boolean {
    if (!Modifier.isPublic(method.modifiers) || Modifier.isStatic(method.modifiers) ||
        method.parameterTypes.isNotEmpty() || method.returnType != Void.TYPE
    ) {
        return false
    }
    var parent: Class<*>? = method.declaringClass.superclass
    while (parent != null) {
        val overridesAbstract = ReflectionAccess.methodsDeclaredBy(parent).any { parentMethod ->
            Modifier.isPublic(parentMethod.modifiers) &&
                Modifier.isAbstract(parentMethod.modifiers) &&
                parentMethod.name == method.name &&
                parentMethod.parameterTypes.isEmpty() &&
                parentMethod.returnType == Void.TYPE
        }
        if (overridesAbstract) return true
        parent = parent.superclass
    }
    return false
}

internal fun namedMethod(
    id: String,
    purpose: String,
    searchPackage: String,
    methodName: String,
    paramCount: Int? = null,
    returnType: Class<*>? = null,
    paramTypes: List<String?>? = null,
    returnTypeName: String? = null,
    ownerName: String? = null,
    matchName: Boolean = true,
    verifyExtra: (Method) -> Boolean = { true },
) = DexKitMethodQuery(
    id = id,
    purpose = purpose,
    query = {
        searchPackages(searchPackage)
        matcher {
            if (ownerName != null) {
                declaredClass(ownerName)
            }
            if (matchName) {
                name(methodName)
            }
            if (paramCount != null) {
                paramCount(paramCount)
            }
            if (paramTypes != null) {
                paramTypes(*paramTypes.toTypedArray())
            }
            if (returnType != null) {
                returnType(returnType)
            }
            if (returnTypeName != null) {
                returnType(returnTypeName)
            }
        }
    },
    verify = { method ->
        (!matchName || method.name == methodName) &&
            (ownerName == null || method.declaringClass.name == ownerName) &&
            (paramCount == null || method.parameterTypes.size == paramCount) &&
            (paramTypes == null || method.parameterTypes.size == paramTypes.size &&
                method.parameterTypes.zip(paramTypes).all { (actual, expected) ->
                    expected == null || actual.name == expected
                }) &&
            (returnType == null || method.returnType == returnType) &&
            (returnTypeName == null || method.returnType.name == returnTypeName) &&
            verifyExtra(method)
    },
)

internal fun singleListSetterQuery(
    id: String,
    purpose: String,
    className: String,
) = DexKitMethodQuery(
    id = id,
    purpose = purpose,
    query = {
        searchPackages(className.substringBeforeLast('.'))
        matcher {
            declaredClass(className)
            paramCount(1)
            returnType(Void.TYPE)
        }
    },
    verify = { method ->
        method.declaringClass.name == className &&
            Modifier.isPublic(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            !method.isBridge &&
            !method.isSynthetic &&
            method.parameterTypes.size == 1 &&
            List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            method.returnType == Void.TYPE
    },
)

internal fun anchoredStringGetterQuery(
    id: String,
    purpose: String,
    searchPackage: String,
    anchor: String,
    ownerName: String,
    requireStatic: Boolean = true,
) = DexKitMethodQuery(
    id = id,
    purpose = purpose,
    query = {
        searchPackages(searchPackage)
        matcher {
            // Apply the existing owner contract before materializing reflection candidates.
            declaredClass(ownerName)
            modifiers(if (requireStatic) Modifier.PUBLIC or Modifier.STATIC else Modifier.PUBLIC)
            paramCount(0)
            returnType(String::class.java)
            addUsingString(anchor, StringMatchType.Equals)
        }
    },
    verify = { method ->
        method.declaringClass.name == ownerName &&
            Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) == requireStatic &&
            method.parameterTypes.isEmpty() &&
            method.returnType == String::class.java
    },
)

internal fun semanticStaticStringGetterQuery(
    id: String,
    purpose: String,
    searchPackage: String,
    anchor: String,
    ownerName: String,
) = DexKitMethodQuery(
    id = id,
    purpose = purpose,
    query = {
        searchPackages(searchPackage)
        matcher {
            declaredClass(ownerName)
            modifiers(Modifier.PUBLIC or Modifier.STATIC)
            paramCount(0)
            returnType(String::class.java)
            addUsingString(anchor, StringMatchType.Contains)
        }
    },
    verify = { method ->
        method.declaringClass.name == ownerName &&
            Modifier.isPublic(method.modifiers) &&
            Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.isEmpty() &&
            method.returnType == String::class.java
    },
)

internal fun commonDeviceStringSetterQuery(
    id: String,
    purpose: String,
    anchor: String,
) = DexKitMethodQuery(
    id = id,
    purpose = purpose,
    query = {
        searchPackages("com.qidian.common.lib")
        matcher {
            declaredClass(HostSymbols.Classes.COMMON_LIB_DEVICE)
            modifiers(Modifier.PUBLIC or Modifier.STATIC)
            paramTypes(String::class.java.name)
            returnType(Void.TYPE)
            addUsingString(anchor, StringMatchType.Contains)
        }
    },
    verify = HostMethodContracts::isCommonDeviceStringSetter,
)

internal fun appConfigStageQuery(
    id: String,
    purpose: String,
    anchor: String,
) = DexKitMethodQuery(
    id = id,
    purpose = purpose,
    query = {
        searchPackages("com.qidian.QDReader.component.config")
        matcher {
            paramCount(1)
            returnType(Void.TYPE)
            addUsingString(anchor, StringMatchType.Equals)
        }
    },
    verify = HostMethodContracts::isAppConfigStage,
)

internal fun h5RewardBridgeQuery(
    id: String,
    purpose: String,
    paramCount: Int,
    verifyExtra: (Method) -> Boolean,
) = DexKitMethodQuery(
    id = id,
    purpose = purpose,
    query = {
        searchPackages("com.qidian.QDReader.webview.plugins")
        matcher { paramCount(paramCount) }
    },
    verify = { method ->
        method.declaringClass.hasSuperclass(HostSymbols.Classes.WEBVIEW_PLUGIN) &&
            method.parameterTypes.size == paramCount &&
            verifyExtra(method)
    },
)

internal fun retrofitPathQuery(
    id: String,
    purpose: String,
    path: String,
) = DexKitMethodQuery(
    id = id,
    purpose = purpose,
    query = {
        matcher {
            addAnyOf {
                addAnnotation {
                    type("retrofit2.http.GET")
                    addElement {
                        name("value")
                        stringValue(path, StringMatchType.Contains)
                    }
                }
            }
            addAnyOf {
                addAnnotation {
                    type("retrofit2.http.POST")
                    addElement {
                        name("value")
                        stringValue(path, StringMatchType.Contains)
                    }
                }
            }
        }
    },
    verify = { method -> method.hasRetrofitPath(path) },
)

internal fun retrofitExactPathQuery(
    id: String,
    purpose: String,
    path: String,
    verifyExtra: (Method) -> Boolean = { true },
) = DexKitMethodQuery(
    id = id,
    purpose = purpose,
    query = {
        matcher {
            addAnyOf {
                addAnnotation {
                    type("retrofit2.http.GET")
                    addElement {
                        name("value")
                        stringValue(path, StringMatchType.Equals)
                    }
                }
            }
            addAnyOf {
                addAnnotation {
                    type("retrofit2.http.POST")
                    addElement {
                        name("value")
                        stringValue(path, StringMatchType.Equals)
                    }
                }
            }
        }
    },
    verify = { method -> method.hasExactRetrofitPath(path) && verifyExtra(method) },
)

/**
 * True for the public splash skip-ad entry `a(Activity, judian)`, false for the synthetic bridge to
 * the private worker `m`. Shape: public non-synthetic, param[0] assignable to Activity, param[1] an
 * inner interface declared on the same class whose single method is `(int, ?, String)->void` (the
 * `judian.search(int, SplashItem, String)` callback). The callback interface is drift-proof because
 * its method arity/param types are contract, not names.
 */
internal fun hasSplashSkipEntryShape(method: Method): Boolean {
    if (method.isSynthetic || !Modifier.isPublic(method.modifiers)) {
        return false
    }
    val params = method.parameterTypes
    if (params.size != 2 || !Activity::class.java.isAssignableFrom(params[0])) {
        return false
    }
    val callback = params[1]
    if (!callback.isInterface) {
        return false
    }
    return ReflectionAccess.methodsDeclaredBy(callback).any { candidate ->
        candidate.returnType == Void.TYPE &&
            candidate.parameterTypes.size == 3 &&
            candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
            candidate.parameterTypes[2] == String::class.java
    }
}

internal fun hasAutoUpdateJudianShape(clazz: Class<*>): Boolean {
    val methods = ReflectionAccess.methodsDeclaredBy(clazz)
    return ReflectionAccess.classesDeclaredBy(clazz).any { callback ->
        callback.isInterface &&
            ReflectionAccess.methodsDeclaredBy(callback).size >= 8 &&
            methods.any { method ->
                Modifier.isStatic(method.modifiers) &&
                    (method.returnType == Void.TYPE || method.returnType == Boolean::class.javaPrimitiveType) &&
                    callback in method.parameterTypes
            }
    }
}

internal fun Class<*>.hasSuperclass(name: String): Boolean {
    var current: Class<*>? = superclass
    while (current != null) {
        if (current.name == name) {
            return true
        }
        current = current.superclass
    }
    return false
}

internal fun Method.hasRetrofitPath(path: String): Boolean {
    return annotations.any { annotation ->
        val annotationName = annotation.annotationClass.java.name
        (annotationName == "retrofit2.http.GET" || annotationName == "retrofit2.http.POST") &&
            runCatching {
                annotation.annotationClass.java.getMethod("value").invoke(annotation)?.toString()
            }.getOrNull()?.contains(path, ignoreCase = true) == true
    }
}

internal fun Method.hasExactRetrofitPath(path: String): Boolean {
    return annotations.any { annotation ->
        val annotationName = annotation.annotationClass.java.name
        (annotationName == "retrofit2.http.GET" || annotationName == "retrofit2.http.POST") &&
            runCatching {
                annotation.annotationClass.java.getMethod("value").invoke(annotation)?.toString()
            }.getOrNull()?.equals(path, ignoreCase = true) == true
    }
}
