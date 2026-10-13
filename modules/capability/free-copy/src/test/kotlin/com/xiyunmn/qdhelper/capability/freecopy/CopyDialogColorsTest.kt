package com.xiyunmn.qdhelper.capability.freecopy

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class CopyDialogColorsTest {
    @Test fun hostRedNeverUsesDefaultPurpleButtonText() {
        for (night in listOf(false, true)) {
            val palette = CopyPalette(night, if (night) 0xFF202020.toInt() else -1,
                if (night) 0xFF303030.toInt() else 0xFFF5F5F5.toInt(),
                if (night) -1 else 0xFF202020.toInt(), 0xFF909090.toInt(),
                0xFFCC3E36.toInt(), 0xFF505050.toInt())
            val colors = copyDialogColors(palette)
            assertEquals(Color(palette.brand), colors.primary)
            assertEquals(Color.White, colors.onPrimary)
            assertTrue(contrast(colors.primary, colors.onPrimary) >= 4.5f)
            assertEquals(colors.primary, colors.secondary)
            assertEquals(colors.primary, colors.tertiary)
            assertEquals(Color(palette.card), colors.surfaceContainerHigh)
            assertTrue(contrast(colors.surface, colors.onSurface) >= 7f)
        }
    }

    @Test fun lightHostAccentUsesDarkButtonText() {
        val colors = copyDialogColors(CopyPalette(false, -1, -1, 0xFF202020.toInt(),
            0xFF666666.toInt(), 0xFFFFDD55.toInt(), 0xFFCCCCCC.toInt()))
        assertEquals(Color.Black, colors.onPrimary)
        assertTrue(contrast(colors.primary, colors.onPrimary) >= 4.5f)
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
}
