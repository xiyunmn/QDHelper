package com.xiyunmn.qdhelper.feature.readtime

import android.app.Activity
import com.xiyunmn.qdhelper.settings.api.SettingsActionScope
import com.xiyunmn.qdhelper.settings.api.SettingsPageId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadTimeSettingsContributionTest {
    @Test
    fun `enabling multiplier never restarts host automatically`() {
        val scope = RecordingActionScope()

        multiplierToggle().onChanged?.run(scope, true)

        assertEquals(0, scope.restartCount)
        assertTrue(SettingsPageIds.ACCOUNT in scope.refreshedPages)
    }

    @Test
    fun `disabling multiplier remains live`() {
        val scope = RecordingActionScope()

        multiplierToggle().onChanged?.run(scope, false)

        assertEquals(0, scope.restartCount)
    }

    @Test
    fun `multiplier slider exposes bounded tenths`() {
        val slider = ReadTimeSettingsContribution.sections
            .flatMap { it.rows }
            .filterIsInstance<SettingsRow.Slider>()
            .single { it.id == "read_time.multiplier.value" }

        assertEquals(0.1, slider.minValue, 0.0)
        assertEquals(2.5, slider.maxValue, 0.0)
        assertEquals(0.1, slider.step, 0.0)
        assertEquals("设定 2.5x · 生效 2.500x", slider.valueFormatter(2.5))
    }

    private fun multiplierToggle(): SettingsRow.Toggle =
        ReadTimeSettingsContribution.sections
            .flatMap { it.rows }
            .filterIsInstance<SettingsRow.Toggle>()
            .single { it.id == "read_time.multiplier.enabled" }

    private class RecordingActionScope : SettingsActionScope {
        override val activity: Activity
            get() = error("Activity is not used by this action")

        var restartCount: Int = 0
        val refreshedPages = mutableListOf<SettingsPageId>()

        override fun refresh(page: SettingsPageId, focusTitle: String?) {
            refreshedPages += page
        }
        override fun closeExclusiveSwitch(rawKey: String) = Unit
        override fun applyUiSettings() = Unit
        override fun restartHost() {
            restartCount++
        }
    }
}
