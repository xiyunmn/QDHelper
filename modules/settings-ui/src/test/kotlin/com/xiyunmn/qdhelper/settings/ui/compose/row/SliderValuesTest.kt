package com.xiyunmn.qdhelper.settings.ui.compose.row

import com.xiyunmn.qdhelper.settings.api.SettingsRow
import org.junit.Assert.assertEquals
import org.junit.Test

class SliderValuesTest {
    private val multiplier = SettingsRow.Slider("test.multiplier", "", "", "test", .1, 2.5, .1, 1.0)
    @Test fun decimalScaleAndBoundsRemainCompatibleWithStoredConfiguration() {
        assertEquals("1.0", normalizedSliderStorage(multiplier, "1"))
        assertEquals("2.5", normalizedSliderStorage(multiplier, "99"))
        assertEquals("0.1", normalizedSliderStorage(multiplier, "-8"))
        assertEquals("1.0", normalizedSliderStorage(multiplier, "NaN"))
        assertEquals("1.0", normalizedSliderStorage(multiplier, "Infinity"))
    }
    @Test fun automaticGlassGapSentinelIsNotRoundedToZero() {
        val gap = multiplier.copy(minValue = -1.0, maxValue = 120.0, step = 1.0, defaultValue = -1.0)
        assertEquals("-1", normalizedSliderStorage(gap, "-1"))
        assertEquals("64", normalizedSliderStorage(gap, "63.6"))
    }
}
