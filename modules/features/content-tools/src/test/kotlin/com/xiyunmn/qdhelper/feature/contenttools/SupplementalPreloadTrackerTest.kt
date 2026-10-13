package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementalPreloadTrackerTest {
    @Test
    fun failedAttemptCanRetryButCompletedAttemptCannot() {
        val tracker = SupplementalPreloadTracker<String>()

        val first = checkNotNull(tracker.tryStart("audio"))
        assertTrue(tracker.tryStart("audio") == null)
        assertTrue(tracker.finish(first, success = false))
        val retry = checkNotNull(tracker.tryStart("audio"))
        assertTrue(tracker.finish(retry, success = true))
        assertTrue(tracker.tryStart("audio") == null)
    }

    @Test
    fun clearWhereReleasesOnlyMatchingKeys() {
        val tracker = SupplementalPreloadTracker<String>()
        val first = checkNotNull(tracker.tryStart("user:1"))
        val second = checkNotNull(tracker.tryStart("user:2"))
        tracker.finish(first, success = true)
        tracker.finish(second, success = true)

        tracker.clearWhere { it.endsWith(":1") }

        assertTrue(tracker.tryStart("user:1") != null)
        assertTrue(tracker.tryStart("user:2") == null)
    }

    @Test
    fun staleAttemptCannotReleaseReplacementAfterClear() {
        val tracker = SupplementalPreloadTracker<String>()
        val stale = checkNotNull(tracker.tryStart("capsule"))
        tracker.clear("capsule")
        val replacement = checkNotNull(tracker.tryStart("capsule"))

        assertFalse(tracker.finish(stale, success = false))
        assertTrue(tracker.tryStart("capsule") == null)
        assertTrue(tracker.finish(replacement, success = true))
    }
}
