package com.xiyunmn.qdhelper.composition

import org.junit.Test
import org.junit.Assert.assertThrows
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.SettingsUiRuntime

class SettingsCompositionTest {
    @Test
    fun completeSettingsPresentationInitializesOnStartupAndHotReload() {
        // Validate the actual business registry and Compose presentation on both entry paths.
        repeat(2) {
            SettingsComposition.configure("com.qidian.QDReader") { null }
            com.xiyunmn.qdhelper.settings.ui.SettingsUiRuntime.validatePresentation(
                SettingsComposition.createContributions("com.qidian.QDReader") { null },
            )
        }
    }

    @Test
    fun presentationRejectsNewOrRemovedBusinessRowsInsteadOfSilentlyHidingThem() {
        val actual = SettingsComposition.createContributions("com.qidian.QDReader") { null }
        val original = actual.first()
        fun replace(rows: List<SettingsRow>): List<SettingsContribution> = actual.map { contribution ->
            if (contribution !== original) contribution else object : SettingsContribution by original {
                override val sections = original.sections.mapIndexed { index, section -> if (index == 0) section.copy(rows = rows) else section }
            }
        }
        val rows = original.sections.first().rows
        assertThrows(IllegalStateException::class.java) { SettingsUiRuntime.validatePresentation(replace(rows + SettingsRow.Hint("test.unplaced", "new setting"))) }
        assertThrows(IllegalStateException::class.java) { SettingsUiRuntime.validatePresentation(replace(rows.drop(1))) }
    }
}
