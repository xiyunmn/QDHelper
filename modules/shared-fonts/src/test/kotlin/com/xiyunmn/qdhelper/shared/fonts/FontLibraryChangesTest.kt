package com.xiyunmn.qdhelper.shared.fonts

import org.junit.Assert.assertEquals
import org.junit.Test

class FontLibraryChangesTest {
    @Test
    fun oneBrokenListenerDoesNotBlockOtherListeners() {
        var notifications = 0
        val broken = FontLibraryChanges.addListener { error("listener failed") }
        val working = FontLibraryChanges.addListener { notifications += 1 }

        try {
            FontLibraryChanges.notifyChanged()
        } finally {
            broken.close()
            working.close()
        }

        assertEquals(1, notifications)
    }
}
