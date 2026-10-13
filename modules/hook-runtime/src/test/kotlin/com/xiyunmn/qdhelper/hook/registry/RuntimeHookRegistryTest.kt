package com.xiyunmn.qdhelper.hook.registry

import java.lang.reflect.Executable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHookRegistryTest {
    @After
    fun tearDown() = RuntimeHookRegistry.clearLocal()

    @Test
    fun hotReloadReplacementAndStaleDrainAreStable() {
        val method = Sample::class.java.getDeclaredMethod("target")
        val handle = FakeHookHandle("sample.target", method)
        RuntimeHookRegistry.prepareHotReloadReplacement(listOf(handle))

        assertSame(handle, RuntimeHookRegistry.pendingHotReloadHandle("sample.target", method))
        RuntimeHookRegistry.markHotReloadReplaced("sample.target", method)
        assertTrue(RuntimeHookRegistry.drainStaleHotReloadHandles().isEmpty())
    }

    @Test
    fun duplicateIdentityUsesIdAndExecutableSignature() {
        val firstMethod = Sample::class.java.getDeclaredMethod("target")
        val secondMethod = Sample::class.java.getDeclaredMethod("otherTarget")
        val first = FakeHookHandle("sample.target", firstMethod)
        val duplicate = FakeHookHandle("sample.target", firstMethod)
        val second = FakeHookHandle("sample.target", secondMethod)

        assertEquals(RuntimeHookRegistry.Registration.New, RuntimeHookRegistry.register("sample.target", firstMethod, first))
        assertEquals(RuntimeHookRegistry.Registration.Duplicate, RuntimeHookRegistry.register("sample.target", firstMethod, duplicate))
        assertEquals(RuntimeHookRegistry.Registration.New, RuntimeHookRegistry.register("sample.target", secondMethod, second))
        assertSame(first, RuntimeHookRegistry.activeHandle("sample.target", firstMethod))
        assertEquals(2, RuntimeHookRegistry.size())
    }

    private class Sample {
        fun target() = Unit
        fun otherTarget() = Unit
    }

    private class FakeHookHandle(
        private val hookId: String?,
        private val target: Executable,
    ) : RuntimeHookHandle {
        override val executable: Executable get() = target
        override val id: String? get() = hookId
        override fun unhook() = Unit
    }
}
