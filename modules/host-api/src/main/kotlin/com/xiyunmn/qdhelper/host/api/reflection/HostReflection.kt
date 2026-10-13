package com.xiyunmn.qdhelper.host.api.reflection

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Constructor

sealed interface HostAccess<out T> {
    data class Value<T>(val value: T) : HostAccess<T>
    data object Missing : HostAccess<Nothing>
    data class Ambiguous(val candidateCount: Int) : HostAccess<Nothing>
    data class Failed(val errorType: String) : HostAccess<Nothing>
}

interface HostReflectionGateway {
    fun findClass(classLoader: ClassLoader, name: String): Class<*>?
    fun declaredMethod(owner: Class<*>, name: String, parameterTypes: Array<out Class<*>>): Method?
    fun methodsNamed(owner: Class<*>, name: String): List<Method>
    fun uniqueMethod(owner: Class<*>, name: String, parameterCount: Int): Method?
    fun allMethods(owner: Class<*>): List<Method>
    fun allFields(owner: Class<*>): List<Field>
    fun nestedClasses(owner: Class<*>): List<Class<*>>
    fun methodsDeclaredBy(owner: Class<*>): List<Method>
    fun fieldsDeclaredBy(owner: Class<*>): List<Field>
    fun constructor(owner: Class<*>, parameterTypes: Array<out Class<*>>): Constructor<*>?
    fun invokeNoArg(target: Any, name: String): HostAccess<Any?>
    fun invokeCompatible(target: Any, name: String, args: Array<out Any?>): HostAccess<Any?>
    fun invoke(method: Method, receiver: Any?, args: Array<out Any?>): HostAccess<Any?>
    fun readField(target: Any, name: String): HostAccess<Any?>
    fun read(field: Field, receiver: Any?): HostAccess<Any?>
    fun write(field: Field, receiver: Any?, value: Any?): Boolean
    fun newInstance(constructor: Constructor<*>, args: Array<out Any?>): HostAccess<Any?>
    fun field(owner: Class<*>, name: String): Field?
}

object HostReflection {
    @Volatile
    private var gateway: HostReflectionGateway? = null

    fun configure(value: HostReflectionGateway) {
        gateway = value
    }

    /** Returns whether the runtime reflection gateway has been installed. */
    fun isConfigured(): Boolean = gateway != null

    internal fun gateway(): HostReflectionGateway? = gateway
}

fun ClassLoader.findHostClass(name: String): Class<*>? = HostReflection.gateway()?.findClass(this, name)

fun Class<*>.declaredMethodOrNull(name: String, vararg parameterTypes: Class<*>): Method? =
    HostReflection.gateway()?.declaredMethod(this, name, parameterTypes)

fun Class<*>.declaredMethodsNamed(name: String): List<Method> =
    HostReflection.gateway()?.methodsNamed(this, name).orEmpty()

fun Class<*>.methodByNameOrNull(name: String, parameterCount: Int): Method? =
    HostReflection.gateway()?.uniqueMethod(this, name, parameterCount)

fun Class<*>.hostMethods(): List<Method> = HostReflection.gateway()?.allMethods(this).orEmpty()

fun Class<*>.hostFields(): List<Field> = HostReflection.gateway()?.allFields(this).orEmpty()

fun Class<*>.hostNestedClasses(): List<Class<*>> = HostReflection.gateway()?.nestedClasses(this).orEmpty()

fun Class<*>.hostDeclaredMethods(): List<Method> = HostReflection.gateway()?.methodsDeclaredBy(this).orEmpty()

fun Class<*>.hostDeclaredFields(): List<Field> = HostReflection.gateway()?.fieldsDeclaredBy(this).orEmpty()

fun Class<*>.constructorOrNull(vararg parameterTypes: Class<*>): Constructor<*>? =
    HostReflection.gateway()?.constructor(this, parameterTypes)

fun Any.callNoArgResult(name: String): HostAccess<Any?> =
    HostReflection.gateway()?.invokeNoArg(this, name) ?: HostAccess.Missing

fun Any.callNoArgMethod(name: String): Any? = (callNoArgResult(name) as? HostAccess.Value)?.value

fun Any.callMethod(name: String, vararg args: Any?): Any? =
    (HostReflection.gateway()?.invokeCompatible(this, name, args) as? HostAccess.Value)?.value

fun Method.callHostResult(receiver: Any?, vararg args: Any?): HostAccess<Any?> =
    HostReflection.gateway()?.invoke(this, receiver, args) ?: HostAccess.Missing

fun Method.callHost(receiver: Any?, vararg args: Any?): Any? =
    (callHostResult(receiver, *args) as? HostAccess.Value)?.value

fun Constructor<*>.newHostInstance(vararg args: Any?): Any? =
    (HostReflection.gateway()?.newInstance(this, args) as? HostAccess.Value)?.value

fun Any.fieldResult(name: String): HostAccess<Any?> =
    HostReflection.gateway()?.readField(this, name) ?: HostAccess.Missing

fun Any.declaredFieldValue(name: String): Any? = (fieldResult(name) as? HostAccess.Value)?.value

fun Field.readHost(receiver: Any?): Any? =
    (HostReflection.gateway()?.read(this, receiver) as? HostAccess.Value)?.value

fun Field.writeHost(receiver: Any?, value: Any?): Boolean =
    HostReflection.gateway()?.write(this, receiver, value) == true

fun Class<*>.fieldOrNull(name: String): Field? = HostReflection.gateway()?.field(this, name)

fun Any.stringField(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    (declaredFieldValue(name) as? String)?.takeIf(String::isNotBlank)
}

fun Any.longField(vararg names: String): Long = names.firstNotNullOfOrNull { name ->
    when (val value = declaredFieldValue(name)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
} ?: 0L

fun Any.intField(vararg names: String): Int = names.firstNotNullOfOrNull { name ->
    when (val value = declaredFieldValue(name)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
} ?: 0

fun Any.booleanField(vararg names: String): Boolean = names.firstNotNullOfOrNull { name ->
    when (val value = declaredFieldValue(name)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> null
    }
} ?: false
