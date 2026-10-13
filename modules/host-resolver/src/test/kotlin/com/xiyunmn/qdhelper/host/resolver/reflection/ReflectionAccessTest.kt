package com.xiyunmn.qdhelper.host.resolver.reflection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionAccessTest {
    @Test
    fun nameAndCountLookupRejectsAmbiguousOverloads() {
        assertNull(ReflectionAccess.uniqueMethod(Overloaded::class.java, "value", 1))
        assertEquals(
            String::class.java,
            ReflectionAccess.compatibleMethod(Overloaded::class.java, "value", arrayOf("x"))?.parameterTypes?.single(),
        )
        assertEquals(
            java.lang.Integer.TYPE,
            ReflectionAccess.compatibleMethod(Overloaded::class.java, "value", arrayOf(1))?.parameterTypes?.single(),
        )
    }

    @Test
    fun memberReadDistinguishesMissingFromLegalFalsyValues() {
        val target = FalsyValues()

        assertEquals(MemberRead.Found(0), ReflectionAccess.readField(target, "zero"))
        assertEquals(MemberRead.Found(false), ReflectionAccess.readField(target, "disabled"))
        assertEquals(MemberRead.Found(""), ReflectionAccess.readField(target, "empty"))
        assertSame(MemberRead.Missing, ReflectionAccess.readField(target, "absent"))
    }

    @Test
    fun classCacheUsesLoaderIdentityAndCanBeCleared() {
        ReflectionAccess.clear()
        val first = EqualLoader()
        val second = EqualLoader()

        assertTrue(first == second)
        ReflectionAccess.findClass(first, "java.lang.String")
        ReflectionAccess.findClass(second, "java.lang.String")
        ReflectionAccess.clear()
    }

    private class Overloaded {
        @Suppress("unused") fun value(input: String): String = input
        @Suppress("unused") fun value(input: Int): Int = input
    }

    private class FalsyValues {
        @Suppress("unused") private val zero = 0
        @Suppress("unused") private val disabled = false
        @Suppress("unused") private val empty = ""
    }

    private class EqualLoader : ClassLoader(ReflectionAccessTest::class.java.classLoader) {
        override fun equals(other: Any?): Boolean = other is EqualLoader
        override fun hashCode(): Int = 1
    }
}
