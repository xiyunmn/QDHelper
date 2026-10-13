package com.xiyunmn.qdhelper.host.resolver.reflection

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap

sealed interface MemberRead {
    data class Found(val value: Any?) : MemberRead
    data object Missing : MemberRead
    data class Failed(val cause: Throwable) : MemberRead
}

object ReflectionAccess {
    private val classes = ConcurrentHashMap<LoaderClassKey, Class<*>>()
    private val declaredMethods = ConcurrentHashMap<DeclaredMethodKey, Method>()
    private val namedMethods = ConcurrentHashMap<NamedMethodKey, Method>()
    private val fields = ConcurrentHashMap<FieldKey, Field>()

    fun findClass(loader: ClassLoader, name: String): Class<*>? {
        val key = LoaderClassKey(loader, name)
        classes[key]?.let { return it }
        return runCatching { Class.forName(name, false, loader) }.getOrNull()?.also { classes[key] = it }
    }

    fun declaredMethod(owner: Class<*>, name: String, parameterTypes: Array<out Class<*>>): Method? {
        val key = DeclaredMethodKey(owner, name, parameterTypes.toList())
        declaredMethods[key]?.let { return it }
        return runCatching { owner.getDeclaredMethod(name, *parameterTypes).makeAccessible() }
            .getOrNull()
            ?.also { declaredMethods[key] = it }
    }

    fun uniqueMethod(owner: Class<*>, name: String, parameterCount: Int): Method? {
        val key = NamedMethodKey(owner, name, List(parameterCount) { null })
        namedMethods[key]?.let { return it }
        val candidates = hierarchyMethods(owner).filter { it.name == name && it.parameterCount == parameterCount }
        return candidates.singleOrNull()?.makeAccessible()?.also { namedMethods[key] = it }
    }

    fun methodsNamed(owner: Class<*>, name: String): List<Method> =
        hierarchyMethods(owner).filter { it.name == name }.map { it.makeAccessible() }

    fun compatibleMethod(owner: Class<*>, name: String, args: Array<out Any?>): Method? {
        val argumentTypes = args.map { it?.javaClass }
        val key = NamedMethodKey(owner, name, argumentTypes)
        namedMethods[key]?.let { return it }
        val candidates = hierarchyMethods(owner)
            .filter { it.name == name && it.parameterCount == args.size }
            .filter { method -> method.parameterTypes.indices.all { compatible(method.parameterTypes[it], args[it]) } }
        val best = candidates
            .map { it to specificityScore(it, args) }
            .sortedByDescending { it.second }
        val selected = best.firstOrNull()?.takeIf { first -> best.count { it.second == first.second } == 1 }?.first
        return selected?.makeAccessible()?.also { namedMethods[key] = it }
    }

    fun field(owner: Class<*>, name: String): Field? {
        val key = FieldKey(owner, name)
        fields[key]?.let { return it }
        var current: Class<*>? = owner
        while (current != null) {
            val result = runCatching { current.getDeclaredField(name).makeAccessible() }.getOrNull()
            if (result != null) {
                fields[key] = result
                return result
            }
            current = current.superclass
        }
        return null
    }

    fun methodsDeclaredBy(owner: Class<*>): Array<Method> = owner.declaredMethods

    fun fieldsDeclaredBy(owner: Class<*>): Array<Field> = owner.declaredFields

    fun classesDeclaredBy(owner: Class<*>): Array<Class<*>> = owner.declaredClasses

    fun methodsInHierarchy(owner: Class<*>): List<Method> = hierarchyMethods(owner).map { it.makeAccessible() }

    fun fieldsInHierarchy(owner: Class<*>): List<Field> {
        val result = mutableListOf<Field>()
        var current: Class<*>? = owner
        while (current != null) {
            result += current.declaredFields.map { it.makeAccessible() }
            current = current.superclass
        }
        return result
    }

    fun constructor(owner: Class<*>, parameterTypes: Array<out Class<*>>): Constructor<*>? =
        runCatching { owner.getDeclaredConstructor(*parameterTypes).makeAccessible() }.getOrNull()

    fun accessible(method: Method): Method = method.makeAccessible()

    fun accessible(field: Field): Field = field.makeAccessible()

    fun readField(target: Any, name: String): MemberRead {
        val member = field(target.javaClass, name) ?: return MemberRead.Missing
        return runCatching<MemberRead> { MemberRead.Found(member.get(target)) }
            .getOrElse { MemberRead.Failed(it) }
    }

    fun read(field: Field, receiver: Any?): MemberRead =
        runCatching<MemberRead> { MemberRead.Found(field.makeAccessible().get(receiver)) }
            .getOrElse { MemberRead.Failed(it) }

    fun write(field: Field, receiver: Any?, value: Any?): Boolean =
        runCatching { field.makeAccessible().set(receiver, value) }.isSuccess

    fun invokeNoArg(target: Any, name: String): MemberRead {
        val method = uniqueMethod(target.javaClass, name, 0) ?: return MemberRead.Missing
        return invoke(method, target, emptyArray())
    }

    fun invokeCompatible(target: Any, name: String, args: Array<out Any?>): MemberRead {
        val method = compatibleMethod(target.javaClass, name, args) ?: return MemberRead.Missing
        return invoke(method, target, args)
    }

    fun clear() {
        classes.clear()
        declaredMethods.clear()
        namedMethods.clear()
        fields.clear()
    }

    fun invoke(method: Method, receiver: Any?, args: Array<out Any?>): MemberRead =
        runCatching<MemberRead> { MemberRead.Found(method.makeAccessible().invoke(receiver, *args)) }
            .getOrElse { MemberRead.Failed(it) }

    fun newInstance(constructor: Constructor<*>, args: Array<out Any?>): MemberRead =
        runCatching<MemberRead> { MemberRead.Found(constructor.makeAccessible().newInstance(*args)) }
            .getOrElse { MemberRead.Failed(it) }

    private fun hierarchyMethods(owner: Class<*>): List<Method> {
        val methods = mutableListOf<Method>()
        var current: Class<*>? = owner
        while (current != null) {
            methods += current.declaredMethods
            current = current.superclass
        }
        val unique = linkedMapOf<Triple<String, List<Class<*>>, Class<*>>, Method>()
        methods.forEach { method ->
            unique.putIfAbsent(Triple(method.name, method.parameterTypes.toList(), method.returnType), method)
        }
        return unique.values.toList()
    }

    private fun compatible(parameter: Class<*>, argument: Any?): Boolean {
        if (argument == null) return !parameter.isPrimitive
        return boxed(parameter).isAssignableFrom(argument.javaClass)
    }

    private fun specificityScore(method: Method, args: Array<out Any?>): Int =
        method.parameterTypes.indices.sumOf { index ->
            val argument = args[index] ?: return@sumOf 0
            val parameter = boxed(method.parameterTypes[index])
            when {
                parameter == argument.javaClass -> 4
                parameter.isAssignableFrom(argument.javaClass) -> 2
                else -> 0
            }
        }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Void.TYPE -> java.lang.Void::class.java
        else -> type
    }

    private fun <T : java.lang.reflect.AccessibleObject> T.makeAccessible(): T = apply { isAccessible = true }

    private class LoaderClassKey(
        private val loader: ClassLoader,
        private val name: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is LoaderClassKey && loader === other.loader && name == other.name
        override fun hashCode(): Int = 31 * System.identityHashCode(loader) + name.hashCode()
    }

    private data class DeclaredMethodKey(
        val owner: Class<*>,
        val name: String,
        val parameterTypes: List<Class<*>>,
    )

    private data class NamedMethodKey(
        val owner: Class<*>,
        val name: String,
        val argumentTypes: List<Class<*>?>,
    )

    private data class FieldKey(val owner: Class<*>, val name: String)
}
