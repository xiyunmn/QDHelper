package com.xiyunmn.qdhelper.host.api.target

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTargetResolverTest {
    private val classLoader = requireNotNull(javaClass.classLoader)

    @Test
    fun unconfiguredResolversFailClosed() {
        assertNull(
            HostTargetResolver.resolveMethod(
                context = null,
                classLoader = classLoader,
                processName = "fixture",
                key = ReaderTargetKeys.chapterEndAppend,
            ),
        )
        assertNull(
            HostTargetResolver.resolveEarlyMethod(
                context = null,
                classLoader = classLoader,
                processName = "fixture",
                key = StartupTargetKeys.splashActivityInitDefault,
            ),
        )
        assertTrue(
            HostTargetResolver.resolveMethods(
                context = null,
                classLoader = classLoader,
                processName = "fixture",
                key = CircleTargetKeys.detailBindMethods,
            ).isEmpty(),
        )
    }
}
