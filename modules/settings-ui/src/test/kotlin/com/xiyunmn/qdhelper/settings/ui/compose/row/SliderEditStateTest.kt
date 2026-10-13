package com.xiyunmn.qdhelper.settings.ui.compose.row

import com.xiyunmn.qdhelper.settings.api.SettingsRow
import org.junit.Assert.*
import org.junit.Test

class SliderEditStateTest {
    private val row = SettingsRow.Slider("test.multiplier", "", "", "test", .1, 2.5, .1, 1.0)
    private var stored = "1.0"
    private var writes = 0
    private var callbacks = 0
    private var commits = 0

    private fun finish(editor: SliderEditState, revision: Int = 0, enabled: Boolean = true, succeeds: Boolean = true) {
        editor.finish(stored, revision, enabled, save = {
            writes++
            if (succeeds) stored = it
            succeeds
        }, onSaved = { callbacks++; assertEquals(stored.toDouble(), it, 0.0) },
            onCommitted = { commits++; assertEquals(stored.toDouble(), it, 0.0) })
    }

    @Test fun manyPreviewEventsWriteAndNotifyOnlyOnceOnCompletion() {
        val editor = SliderEditState(row, stored, 0)
        repeat(100) { editor.preview((it % 25 + 1) / 10f) }
        assertEquals("2.5", editor.value)
        assertEquals("1.0", stored)
        assertEquals(0, writes)
        assertEquals(0, callbacks)
        finish(editor)
        finish(editor)
        assertEquals("2.5", stored)
        assertEquals(1, writes)
        assertEquals(1, callbacks)
        assertEquals(1, commits)
    }

    @Test fun returningToStoredValueCommitsUserIntentWithoutWritingOrNotifyingAValueChange() {
        val editor = SliderEditState(row, stored, 0)
        editor.preview(2.5f)
        editor.preview(1f)
        finish(editor)
        assertEquals(0, writes)
        assertEquals(0, callbacks)
        assertEquals(1, commits)
        finish(editor)
        assertEquals(1, commits)
    }

    @Test fun consecutiveDiscreteAdjustmentsBeforeRecompositionBothCommit() {
        val editor = SliderEditState(row, stored, 0)
        editor.preview(1.1f)
        finish(editor)
        editor.preview(1.2f)
        finish(editor)
        assertEquals("1.2", stored)
        assertEquals(2, writes)
        assertEquals(2, callbacks)
    }

    @Test fun resetExternalUpdateAndDisabledParentRejectAnOldGesture() {
        for (scenario in 0..2) {
            stored = "1.0"
            val editor = SliderEditState(row, stored, 0)
            editor.preview(2f)
            if (scenario == 1) stored = "1.5"
            finish(editor, revision = if (scenario == 0) 1 else 0, enabled = scenario != 2)
            assertEquals(stored, editor.value)
        }
        assertEquals(0, writes)
        assertEquals(0, callbacks)
        assertEquals(0, commits)
    }

    @Test fun failedWriteRestoresStoredPreviewAndDoesNotRunCallback() {
        val editor = SliderEditState(row, stored, 0)
        editor.preview(1.7f)
        finish(editor, succeeds = false)
        assertEquals("1.0", editor.value)
        assertEquals(1, writes)
        assertEquals(0, callbacks)
        assertEquals(0, commits)
        finish(editor)
        assertEquals(1, writes)
    }
}
