package com.xiyunmn.qdhelper.host.resolver.reflection

import com.xiyunmn.qdhelper.host.api.reflection.HostReflectionGateway
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

object HostReflectionGatewayAdapter : HostReflectionGateway {
    override fun findClass(classLoader: ClassLoader, name: String): Class<*>? =
        ReflectionAccess.findClass(classLoader, name)

    override fun declaredMethod(owner: Class<*>, name: String, parameterTypes: Array<out Class<*>>): Method? =
        ReflectionAccess.declaredMethod(owner, name, parameterTypes)

    override fun methodsNamed(owner: Class<*>, name: String): List<Method> =
        ReflectionAccess.methodsNamed(owner, name)

    override fun uniqueMethod(owner: Class<*>, name: String, parameterCount: Int): Method? =
        ReflectionAccess.uniqueMethod(owner, name, parameterCount)

    override fun allMethods(owner: Class<*>): List<Method> = ReflectionAccess.methodsInHierarchy(owner)

    override fun allFields(owner: Class<*>): List<Field> = ReflectionAccess.fieldsInHierarchy(owner)

    override fun nestedClasses(owner: Class<*>): List<Class<*>> = ReflectionAccess.classesDeclaredBy(owner).toList()

    override fun methodsDeclaredBy(owner: Class<*>): List<Method> =
        ReflectionAccess.methodsDeclaredBy(owner).map(ReflectionAccess::accessible)

    override fun fieldsDeclaredBy(owner: Class<*>): List<Field> =
        ReflectionAccess.fieldsDeclaredBy(owner).map(ReflectionAccess::accessible)

    override fun constructor(owner: Class<*>, parameterTypes: Array<out Class<*>>): Constructor<*>? =
        ReflectionAccess.constructor(owner, parameterTypes)

    override fun invokeNoArg(target: Any, name: String): HostAccess<Any?> =
        ReflectionAccess.invokeNoArg(target, name).toHostAccess()

    override fun invokeCompatible(target: Any, name: String, args: Array<out Any?>): HostAccess<Any?> =
        ReflectionAccess.invokeCompatible(target, name, args).toHostAccess()

    override fun invoke(method: Method, receiver: Any?, args: Array<out Any?>): HostAccess<Any?> =
        ReflectionAccess.invoke(method, receiver, args).toHostAccess()

    override fun readField(target: Any, name: String): HostAccess<Any?> =
        ReflectionAccess.readField(target, name).toHostAccess()

    override fun read(field: Field, receiver: Any?): HostAccess<Any?> =
        ReflectionAccess.read(field, receiver).toHostAccess()

    override fun write(field: Field, receiver: Any?, value: Any?): Boolean =
        ReflectionAccess.write(field, receiver, value)

    override fun newInstance(constructor: Constructor<*>, args: Array<out Any?>): HostAccess<Any?> =
        ReflectionAccess.newInstance(constructor, args).toHostAccess()

    override fun field(owner: Class<*>, name: String): Field? = ReflectionAccess.field(owner, name)
}

private fun MemberRead.toHostAccess(): HostAccess<Any?> = when (this) {
    is MemberRead.Found -> HostAccess.Value(value)
    MemberRead.Missing -> HostAccess.Missing
    is MemberRead.Failed -> HostAccess.Failed(cause.javaClass.name)
}
