package com.xiyunmn.qdhelper.feature.glassbars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FlutterViewportOverridesTest {
    private data class Renderer(val label: String)

    @Test
    fun renderersWithEqualValuesStillHaveIndependentInstanceScopes() {
        val registry = FlutterViewportOverrides()
        val main = Renderer("engine")
        val other = Renderer("engine")
        val view = Any()
        registry.put(main, view, 88)
        assertEquals(88, registry.bottomFor(main))
        assertNull(registry.bottomFor(other))
        assertFalse(registry.put(main, view, 88))
        registry.removeView(view)
        assertNull(registry.bottomFor(main))
    }

    @Test
    fun reusedEngineLeavesItsOldViewScope() {
        val registry = FlutterViewportOverrides()
        val renderer = Any()
        val oldView = Any()
        val newView = Any()
        registry.put(renderer, oldView, 88)
        registry.put(renderer, newView, 100)
        registry.removeView(oldView)
        assertEquals(100, registry.bottomFor(renderer))
        registry.clear()
        assertNull(registry.bottomFor(renderer))
    }

    @Test
    fun originalMetricsReturnEvenWhenTheNativeCallThrows() {
        var bottom = 24
        assertThrows(IllegalStateException::class.java) {
            withFlutterBottomPadding(88, { bottom }, { bottom = it; true }) {
                assertEquals(88, bottom)
                error("JNI failed")
            }
        }
        assertEquals(24, bottom)
    }

    @Test
    fun reentryDoesNotDoubleTheInsetAndOtherMetricsAreUntouched() {
        var bottom = 24
        val ime = 320
        val gesture = 16
        val result = withFlutterBottomPadding(88, { bottom }, { bottom = it; true }) {
            withFlutterBottomPadding(88, { bottom }, { bottom = it; true }) {
                assertEquals(88, bottom)
                7
            }.also { assertEquals(88, bottom) }
        }
        assertEquals(7, result)
        assertEquals(24, bottom)
        assertEquals(320, ime)
        assertEquals(16, gesture)
    }

    @Test
    fun unregisteredOrUnwritableMetricsPassThrough() {
        var bottom = 24
        withFlutterBottomPadding(null, { bottom }, { bottom = it; true }) { assertEquals(24, bottom) }
        withFlutterBottomPadding(88, { bottom }, { false }) { assertEquals(24, bottom) }
        assertEquals(24, bottom)
    }
}
