package com.xiyunmn.qdhelper.feature.glassbars

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedPropertyTest {
    @Test
    fun repeatedLayoutsAndChangingOcclusionDoNotAccumulatePadding() {
        var padding = 12
        val property = OwnedProperty({ padding }, { padding = it })
        repeat(100) { property.update { it + 64 } }
        assertEquals(76, padding)
        property.update { it + 112 }
        assertEquals(124, padding)
        property.close()
        assertEquals(12, padding)
        property.close()
        assertEquals(12, padding)
    }

    @Test
    fun nativeWritesAreRebasedAndPreservedByRestore() {
        var padding = 12
        val property = OwnedProperty({ padding }, { padding = it })
        property.update { it + 64 }
        padding = 20
        property.update { it + 64 }
        assertEquals(84, padding)
        property.close()
        assertEquals(20, padding)
    }

    @Test
    fun aNewerHostStateIsNeverOverwrittenOnDetach() {
        var background: String? = "day"
        val property = OwnedProperty({ background }, { background = it })
        property.set(null)
        background = "night"
        property.close()
        assertEquals("night", background)
    }
}
