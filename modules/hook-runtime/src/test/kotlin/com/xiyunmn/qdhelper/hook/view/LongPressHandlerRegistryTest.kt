package com.xiyunmn.qdhelper.hook.view

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressHandlerRegistryTest {
    @Test
    fun dispatchesByPriorityAndStopsWhenConsumed() {
        val registry = LongPressHandlerRegistry()
        val target = Any()
        val calls = mutableListOf<String>()
        registry.register(target, LongPressRegistration(OwnerId("low"), "copy", 10)) { _, _ ->
            calls += "low"
            LongPressResult.Return(true)
        }
        registry.register(target, LongPressRegistration(OwnerId("high"), "image", 100)) { _, _ ->
            calls += "high"
            LongPressResult.Pass
        }

        assertEquals(LongPressResult.Return(true), registry.dispatch(target) { false })
        assertEquals(listOf("high", "low"), calls)
    }

    @Test
    fun isolatesFailureAndRegistrationCanRestoreEmptyState() {
        val failures = mutableListOf<String>()
        val registry = LongPressHandlerRegistry { registration, _ -> failures += registration.id }
        val target = Any()
        val registered = registry.register(
            target,
            LongPressRegistration(OwnerId("broken"), "broken", 20),
        ) { _, _ -> error("boom") } as LongPressRegistrationResult.Registered

        assertEquals(LongPressResult.Pass, registry.dispatch(target) { false })
        assertEquals(listOf("broken"), failures)
        assertTrue(registry.hasRegistrations(target))
        registered.registration.close()
        assertFalse(registry.hasRegistrations(target))
    }
}
