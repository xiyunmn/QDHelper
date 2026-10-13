package com.xiyunmn.qdhelper.hook.activity

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityResultRegistryTest {
    @Test
    fun rejectsDuplicateRequestCodeAndReleasesRegistration() {
        val registry = ActivityResultRegistry()
        val calls = mutableListOf<String>()
        val first = registry.register(OwnerId("startup"), 41) { _, result, _ ->
            calls += "startup:$result"
            true
        }
        assertTrue(first is ActivityResultRegistry.RegistrationResult.Registered)
        val conflict = registry.register(OwnerId("reader-font"), 41) { _, _, _ -> true }
        assertEquals(
            OwnerId("startup"),
            (conflict as ActivityResultRegistry.RegistrationResult.RequestCodeConflict).existingOwner,
        )
        assertTrue(registry.dispatch(Any(), 41, 7, null))
        assertEquals(listOf("startup:7"), calls)

        (first as ActivityResultRegistry.RegistrationResult.Registered).registration.close()
        assertFalse(registry.dispatch(Any(), 41, 7, null))
    }

    @Test
    fun treatsSameOwnerRepeatAsIdempotentWithoutReplacingTheHandler() {
        val registry = ActivityResultRegistry()
        val calls = mutableListOf<String>()
        val first = registry.register(OwnerId("startup"), 41) { _, _, _ ->
            calls += "first"
            true
        }

        val duplicate = registry.register(OwnerId("startup"), 41) { _, _, _ ->
            calls += "replacement"
            true
        }

        assertEquals(
            ActivityResultRegistry.RegistrationResult.AlreadyRegistered(41, OwnerId("startup")),
            duplicate,
        )
        assertTrue(registry.dispatch(Any(), 41, 7, null))
        assertEquals(listOf("first"), calls)

        (first as ActivityResultRegistry.RegistrationResult.Registered).registration.close()
        assertFalse(registry.dispatch(Any(), 41, 7, null))
    }
}
