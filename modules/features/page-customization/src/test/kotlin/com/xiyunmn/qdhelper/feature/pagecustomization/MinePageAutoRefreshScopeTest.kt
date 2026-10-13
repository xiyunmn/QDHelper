package com.xiyunmn.qdhelper.feature.pagecustomization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinePageAutoRefreshScopeTest {
    @Test
    fun visibilityScopedReloadIsSuppressedWhenEnabled() {
        assertFalse(MinePageAutoRefreshScope.shouldSuppressReload(featureEnabled = true))

        MinePageAutoRefreshScope.withinVisibilityCallback {
            assertTrue(MinePageAutoRefreshScope.shouldSuppressReload(featureEnabled = true))
        }

        assertFalse(MinePageAutoRefreshScope.shouldSuppressReload(featureEnabled = true))
    }

    @Test
    fun disabledFeatureNeverSuppressesReload() {
        MinePageAutoRefreshScope.withinVisibilityCallback {
            assertFalse(MinePageAutoRefreshScope.shouldSuppressReload(featureEnabled = false))
        }
    }

    @Test
    fun nestedScopeRestoresOuterState() {
        MinePageAutoRefreshScope.withinVisibilityCallback {
            MinePageAutoRefreshScope.withinVisibilityCallback {
                assertTrue(MinePageAutoRefreshScope.shouldSuppressReload(featureEnabled = true))
            }
            assertTrue(MinePageAutoRefreshScope.shouldSuppressReload(featureEnabled = true))
        }

        assertFalse(MinePageAutoRefreshScope.shouldSuppressReload(featureEnabled = true))
    }

    @Test
    fun exceptionalScopeIsAlwaysCleared() {
        runCatching {
            MinePageAutoRefreshScope.withinVisibilityCallback {
                error("expected")
            }
        }

        assertFalse(MinePageAutoRefreshScope.shouldSuppressReload(featureEnabled = true))
    }

    @Test
    fun programmaticMineRefreshIndicatorIsSuppressedWhenEnabled() {
        assertTrue(
            MinePageAutoRefreshScope.shouldSuppressRefreshIndicator(
                featureEnabled = true,
                requestedRefreshing = true,
                isMineLayout = true,
            ),
        )
    }

    @Test
    fun refreshCompletionAndOtherLayoutsAreNotSuppressed() {
        assertFalse(
            MinePageAutoRefreshScope.shouldSuppressRefreshIndicator(
                featureEnabled = true,
                requestedRefreshing = false,
                isMineLayout = true,
            ),
        )
        assertFalse(
            MinePageAutoRefreshScope.shouldSuppressRefreshIndicator(
                featureEnabled = true,
                requestedRefreshing = true,
                isMineLayout = false,
            ),
        )
    }
}
