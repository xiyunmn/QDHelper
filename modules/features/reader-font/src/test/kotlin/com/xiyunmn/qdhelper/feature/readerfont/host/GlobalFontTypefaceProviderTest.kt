package com.xiyunmn.qdhelper.feature.readerfont.host

import com.xiyunmn.qdhelper.shared.fonts.FontRole
import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalFontTypefaceProviderTest {
    @Test
    fun flutterFamiliesMapToRegularAndEmphasisSelections() {
        assertEquals(FontRole.GLOBAL_REGULAR, flutterFontRole(null))
        assertEquals(FontRole.GLOBAL_REGULAR, flutterFontRole("HYQiH_Light"))
        assertEquals(FontRole.GLOBAL_EMPHASIS, flutterFontRole("HYQiH_Medium_60"))
        assertEquals(FontRole.GLOBAL_EMPHASIS, flutterFontRole("SourceHanSerif_Bold"))
    }
}
