package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePrivacyPreloadLifecycleTest {
    @Test
    fun invalidatingUserRejectsOnlyThatUsersExistingTokens() {
        val lifecycle = ProfilePrivacyPreloadLifecycle()
        val firstUser = lifecycle.capture(1L)
        val secondUser = lifecycle.capture(2L)

        lifecycle.invalidateUser(1L)

        assertFalse(lifecycle.isCurrent(firstUser))
        assertTrue(lifecycle.isCurrent(secondUser))
        assertTrue(lifecycle.isCurrent(lifecycle.capture(1L)))
    }

    @Test
    fun invalidatingAllRejectsEveryExistingTokenButAllowsNewWork() {
        val lifecycle = ProfilePrivacyPreloadLifecycle()
        val firstUser = lifecycle.capture(1L)
        lifecycle.invalidateUser(2L)
        val secondUser = lifecycle.capture(2L)

        lifecycle.invalidateAll()
        lifecycle.clearUserGenerations()

        assertFalse(lifecycle.isCurrent(firstUser))
        assertFalse(lifecycle.isCurrent(secondUser))
        assertTrue(lifecycle.isCurrent(lifecycle.capture(1L)))
        assertTrue(lifecycle.isCurrent(lifecycle.capture(2L)))
    }

    @Test
    fun repeatedInvalidationNeverRevalidatesAnOldToken() {
        val lifecycle = ProfilePrivacyPreloadLifecycle()
        val stale = lifecycle.capture(7L)

        repeat(3) {
            lifecycle.invalidateUser(7L)
            assertFalse(lifecycle.isCurrent(stale))
            assertTrue(lifecycle.isCurrent(lifecycle.capture(7L)))
        }
    }

    @Test
    fun clearingUserGenerationsAfterGlobalInvalidationCannotReviveOldTokens() {
        val lifecycle = ProfilePrivacyPreloadLifecycle()
        lifecycle.invalidateUser(9L)
        val stale = lifecycle.capture(9L)

        lifecycle.invalidateAll()
        assertFalse(lifecycle.isCurrent(stale))

        lifecycle.clearUserGenerations()

        assertFalse(lifecycle.isCurrent(stale))
        assertTrue(lifecycle.isCurrent(lifecycle.capture(9L)))
    }
}
