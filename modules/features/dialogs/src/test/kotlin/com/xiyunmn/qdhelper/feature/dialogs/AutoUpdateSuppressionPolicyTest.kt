package com.xiyunmn.qdhelper.feature.dialogs

import android.content.Context
import com.qidian.QDReader.ui.activity.AboutActivity
import com.qidian.QDReader.ui.activity.MainGroupActivity
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import com.xiyunmn.qdhelper.host.api.reflection.HostReflection
import com.xiyunmn.qdhelper.host.api.reflection.HostReflectionGateway
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUpdateSuppressionPolicyTest {
    @Test
    fun callbackAnchorRemainsAvailableWhenClassHasMultipleEntries() {
        val anchor = AutoUpdateSuppressionPolicy.callbackAnchor(
            AutoUpdateFixture::class.java.declaredMethods.toList(),
            listOf(Callback::class.java),
        )

        assertNotNull(anchor)
        assertTrue(anchor!!.parameterTypes.contains(Callback::class.java))
    }

    @Test
    fun blockableMethodsCoverShiftedCallbacksBooleanHelpersAndCachedForceGate() {
        val methods = AutoUpdateSuppressionPolicy.blockableMethods(
            AutoUpdateFixture::class.java.declaredMethods.toList(),
            Callback::class.java,
        )

        assertTrue(methods.any { it.name == "second" })
        assertTrue(methods.any { it.name == "shifted" })
        assertTrue(methods.any { it.name == "downloaded" })
        assertTrue(methods.any { it.name == "cachedForce" })
        assertEquals(2, methods.count { it.name == "overloaded" })
        assertFalse(methods.any { it.name == "unrelated" })
    }

    @Test
    fun callbackLookupAndBlockedResultsRespectMethodContracts() {
        val callback = object : Callback {}
        val shifted = AutoUpdateFixture::class.java.getDeclaredMethod(
            "shifted",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Callback::class.java,
        )
        val downloaded = AutoUpdateFixture::class.java.getDeclaredMethod("downloaded", Callback::class.java)
        val second = AutoUpdateFixture::class.java.getDeclaredMethod(
            "second",
            String::class.java,
            Callback::class.java,
        )

        assertEquals(
            callback,
            AutoUpdateSuppressionPolicy.callbackArgument(
                shifted,
                Callback::class.java,
                listOf("context", true, 1L, callback),
            ),
        )
        assertEquals(false, AutoUpdateSuppressionPolicy.blockedResult(downloaded))
        assertNull(AutoUpdateSuppressionPolicy.blockedResult(second))
    }

    @Test
    fun aboutActivityIsTheOnlyManualUpdateExemption() {
        assertTrue(
            AutoUpdateSuppressionPolicy.isAboutManualClassNames(
                listOf("com.qidian.QDReader.ui.activity.AboutActivity"),
            ),
        )
        assertFalse(
            AutoUpdateSuppressionPolicy.isAboutManualClassNames(
                listOf("com.qidian.QDReader.ui.activity.MainGroupActivity"),
            ),
        )
    }

    @Test
    fun callbackHeldContextPreservesOnlyTheAboutPageManualCheck() {
        HostReflection.configure(TestReflectionGateway)
        val aboutContext = allocateWithoutConstructor(AboutActivity::class.java)
        val aboutContexts = AutoUpdateSuppressionPolicy.contexts(
            thisObject = null,
            args = emptyList(),
            callback = ContextCallback(aboutContext),
        )
        assertTrue(AutoUpdateSuppressionPolicy.isAboutManualContext(aboutContexts))

        val mainContext = allocateWithoutConstructor(MainGroupActivity::class.java)
        val mainContexts = AutoUpdateSuppressionPolicy.contexts(
            thisObject = null,
            args = emptyList(),
            callback = ContextCallback(mainContext),
        )
        assertFalse(AutoUpdateSuppressionPolicy.isAboutManualContext(mainContexts))
    }

    @Test
    fun hookKeysDistinguishSameNameAndArityOverloads() {
        val overloads = AutoUpdateFixture::class.java.declaredMethods.filter { it.name == "overloaded" }

        assertEquals(2, overloads.size)
        assertNotEquals(
            AutoUpdateSuppressionPolicy.hookKey(overloads[0]),
            AutoUpdateSuppressionPolicy.hookKey(overloads[1]),
        )
    }

    private interface Callback

    private class ContextCallback(@Suppress("unused") private val context: Context)

    private fun <T : Any> allocateWithoutConstructor(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val singleton = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val instance = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(singleton, type)
        return requireNotNull(type.cast(instance))
    }

    private object TestReflectionGateway : HostReflectionGateway {
        override fun findClass(classLoader: ClassLoader, name: String): Class<*>? = null
        override fun declaredMethod(owner: Class<*>, name: String, parameterTypes: Array<out Class<*>>): Method? = null
        override fun methodsNamed(owner: Class<*>, name: String): List<Method> = emptyList()
        override fun uniqueMethod(owner: Class<*>, name: String, parameterCount: Int): Method? = null
        override fun allMethods(owner: Class<*>): List<Method> = emptyList()
        override fun allFields(owner: Class<*>): List<Field> = emptyList()
        override fun nestedClasses(owner: Class<*>): List<Class<*>> = emptyList()
        override fun methodsDeclaredBy(owner: Class<*>): List<Method> = owner.declaredMethods.toList()
        override fun fieldsDeclaredBy(owner: Class<*>): List<Field> = owner.declaredFields.toList()
        override fun constructor(owner: Class<*>, parameterTypes: Array<out Class<*>>): Constructor<*>? = null
        override fun invokeNoArg(target: Any, name: String): HostAccess<Any?> = HostAccess.Missing
        override fun invokeCompatible(target: Any, name: String, args: Array<out Any?>): HostAccess<Any?> = HostAccess.Missing
        override fun invoke(method: Method, receiver: Any?, args: Array<out Any?>): HostAccess<Any?> = HostAccess.Missing
        override fun readField(target: Any, name: String): HostAccess<Any?> = HostAccess.Missing
        override fun read(field: Field, receiver: Any?): HostAccess<Any?> = runCatching {
            field.isAccessible = true
            HostAccess.Value(field.get(receiver))
        }.getOrElse { HostAccess.Failed(it.javaClass.name) }
        override fun write(field: Field, receiver: Any?, value: Any?): Boolean = false
        override fun newInstance(constructor: Constructor<*>, args: Array<out Any?>): HostAccess<Any?> = HostAccess.Missing
        override fun field(owner: Class<*>, name: String): Field? = null
    }

    private class AutoUpdateFixture {
        companion object {
            @JvmStatic
            fun second(context: String, callback: Callback) = Unit

            @JvmStatic
            fun shifted(context: String, enabled: Boolean, handler: Long, callback: Callback): Boolean = false

            @JvmStatic
            fun downloaded(callback: Callback): Boolean = true

            @JvmStatic
            fun cachedForce(context: Context): Boolean = true

            @JvmStatic
            fun overloaded(callback: Callback, value: Int) = Unit

            @JvmStatic
            fun overloaded(callback: Callback, value: String) = Unit

            @JvmStatic
            fun unrelated(context: String): Boolean = true
        }
    }
}
