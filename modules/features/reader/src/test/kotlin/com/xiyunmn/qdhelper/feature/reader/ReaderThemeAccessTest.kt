package com.xiyunmn.qdhelper.feature.reader

import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import com.xiyunmn.qdhelper.host.api.reflection.HostReflection
import com.xiyunmn.qdhelper.host.api.reflection.HostReflectionGateway
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ReaderThemeAccessTest {
    @Before
    fun installReflectionGateway() {
        HostReflection.configure(TestReflectionGateway)
    }

    @Test
    fun `activity limited status is changed only inside download call`() {
        val entity = FakeThemeEntity(haveStatus = 0, themeType = 105L)
        val access = access()

        val observed = access.withHaveStatus(FakeDetail(entity), 1) { entity.getHaveStatus() }

        assertEquals(1, observed)
        assertEquals(0, entity.getHaveStatus())
    }

    @Test
    fun `member type is restored when host call throws`() {
        val entity = FakeThemeEntity(haveStatus = 0, themeType = 102L)
        val access = access()

        runCatching {
            access.withNormalThemeType(FakeDetail(entity)) {
                assertEquals(101L, entity.getThemeType())
                error("host failure")
            }
        }

        assertEquals(102L, entity.getThemeType())
    }

    @Test
    fun `already available ordinary themes remain untouched`() {
        val entity = FakeThemeEntity(haveStatus = 1, themeType = 101L)
        val access = access()

        access.withHaveStatus(FakeDetail(entity), 1) { assertEquals(1, entity.getHaveStatus()) }
        access.withNormalThemeType(FakeDetail(entity)) { assertEquals(101L, entity.getThemeType()) }

        assertEquals(1, entity.getHaveStatus())
        assertEquals(101L, entity.getThemeType())
    }

    private fun access(): ReaderThemeAccess = requireNotNull(
        ReaderThemeAccess.resolve(FakeThemeEntity::class.java, FakeDetail::class.java),
    )

    private class FakeDetail(@JvmField val theme: FakeThemeEntity)

    private class FakeThemeEntity(
        private var haveStatus: Int,
        private var themeType: Long,
    ) {
        fun getHaveStatus(): Int = haveStatus
        fun setHaveStatus(value: Int) {
            haveStatus = value
        }

        fun getThemeType(): Long = themeType
        fun setThemeType(value: Long) {
            themeType = value
        }

        fun getThemeId(): Long = 42L
        fun getThemeName(): String = "theme"
        fun getImageUrl(): String = "https://example.com/list.jpg"
        fun getPreviewImage(): String = "https://example.com/preview.jpg"
    }

    private object TestReflectionGateway : HostReflectionGateway {
        override fun findClass(classLoader: ClassLoader, name: String): Class<*>? = null
        override fun declaredMethod(owner: Class<*>, name: String, parameterTypes: Array<out Class<*>>): Method? =
            runCatching { owner.getDeclaredMethod(name, *parameterTypes) }.getOrNull()
        override fun methodsNamed(owner: Class<*>, name: String): List<Method> =
            owner.declaredMethods.filter { it.name == name }
        override fun uniqueMethod(owner: Class<*>, name: String, parameterCount: Int): Method? =
            owner.declaredMethods.singleOrNull { it.name == name && it.parameterCount == parameterCount }
        override fun allMethods(owner: Class<*>): List<Method> = owner.methods.toList()
        override fun allFields(owner: Class<*>): List<Field> = owner.fields.toList()
        override fun nestedClasses(owner: Class<*>): List<Class<*>> = owner.declaredClasses.toList()
        override fun methodsDeclaredBy(owner: Class<*>): List<Method> = owner.declaredMethods.toList()
        override fun fieldsDeclaredBy(owner: Class<*>): List<Field> = owner.declaredFields.toList()
        override fun constructor(owner: Class<*>, parameterTypes: Array<out Class<*>>): Constructor<*>? =
            runCatching { owner.getDeclaredConstructor(*parameterTypes) }.getOrNull()
        override fun invokeNoArg(target: Any, name: String): HostAccess<Any?> = HostAccess.Missing
        override fun invokeCompatible(target: Any, name: String, args: Array<out Any?>): HostAccess<Any?> =
            HostAccess.Missing
        override fun invoke(method: Method, receiver: Any?, args: Array<out Any?>): HostAccess<Any?> = runCatching {
            method.isAccessible = true
            HostAccess.Value(method.invoke(receiver, *args))
        }.getOrElse { HostAccess.Failed(it.javaClass.name) }
        override fun readField(target: Any, name: String): HostAccess<Any?> = HostAccess.Missing
        override fun read(field: Field, receiver: Any?): HostAccess<Any?> = runCatching {
            field.isAccessible = true
            HostAccess.Value(field.get(receiver))
        }.getOrElse { HostAccess.Failed(it.javaClass.name) }
        override fun write(field: Field, receiver: Any?, value: Any?): Boolean = false
        override fun newInstance(constructor: Constructor<*>, args: Array<out Any?>): HostAccess<Any?> =
            HostAccess.Missing
        override fun field(owner: Class<*>, name: String): Field? = null
    }
}
