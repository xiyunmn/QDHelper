package com.xiyunmn.qdhelper.host.resolver.runtime

import com.xiyunmn.qdhelper.host.resolver.catalog.ResolutionTiming
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionTimingPolicyTest {
    @Test
    fun onlyRuntimeAndOnActionTargetsMayScanSynchronously() {
        assertTrue(ResolutionTiming.RUNTIME_INSTALLABLE.allowsSynchronousScan())
        assertTrue(ResolutionTiming.ON_ACTION.allowsSynchronousScan())
        assertFalse(ResolutionTiming.EARLY_STARTUP.allowsSynchronousScan())
        assertFalse(ResolutionTiming.PROBE_ONLY.allowsSynchronousScan())
    }

    @Test
    fun onlyRuntimeInstallableTargetsPublishCurrentProcessRetry() {
        assertTrue(ResolutionTiming.RUNTIME_INSTALLABLE.publishesCurrentProcessRetry())
        assertFalse(ResolutionTiming.ON_ACTION.publishesCurrentProcessRetry())
        assertFalse(ResolutionTiming.EARLY_STARTUP.publishesCurrentProcessRetry())
        assertFalse(ResolutionTiming.PROBE_ONLY.publishesCurrentProcessRetry())
    }
}
