package com.xiyunmn.qdhelper.shared.fonts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class FontLibraryOrderTest {
    @Test
    fun existingFontKeepsItsOriginalPosition() {
        val first = File("first.ttf").absolutePath
        val selected = File("selected.ttf").absolutePath
        val last = File("last.ttf").absolutePath

        assertEquals(
            listOf(first, selected, last),
            appendFontWithoutReordering(listOf(first, selected, last), selected),
        )
    }

    @Test
    fun newlyImportedFontIsAppended() {
        val first = File("first.ttf").absolutePath
        val second = File("second.ttf").absolutePath

        assertEquals(listOf(first, second), appendFontWithoutReordering(listOf(first), second))
    }
}
