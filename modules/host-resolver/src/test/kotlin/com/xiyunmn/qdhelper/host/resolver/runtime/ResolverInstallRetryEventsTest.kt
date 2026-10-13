package com.xiyunmn.qdhelper.host.resolver.runtime

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.target.ReaderTargetKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolverInstallRetryEventsTest {
    @Test
    fun publishesOnlyNonEmptyResolutionCompletionsAndCanBeCleared() {
        val received = mutableListOf<ResolverInstallRetryEvent>()
        ResolverInstallRetryEvents.configure(received::add)
        try {
            ResolverInstallRetryEvents.publish(
                ResolverInstallRetryEvent(
                    processName = "com.qidian.QDReader",
                    owners = setOf(OwnerId("reader")),
                    targetIds = setOf(ReaderTargetKeys.chapterEndAppend.id),
                ),
            )
            ResolverInstallRetryEvents.publish(
                ResolverInstallRetryEvent(
                    processName = "com.qidian.QDReader",
                    owners = emptySet(),
                    targetIds = emptySet(),
                ),
            )

            assertEquals(1, received.size)
            assertEquals(ReaderTargetKeys.chapterEndAppend.id, received.single().targetIds.single())

            ResolverInstallRetryEvents.clear()
            ResolverInstallRetryEvents.publish(received.single())
            assertEquals(1, received.size)
        } finally {
            ResolverInstallRetryEvents.clear()
        }
    }
}
