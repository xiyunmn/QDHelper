package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class SettingsDragStateTest {
    private fun fixture(): Pair<SettingsDragState, DragGroup> {
        val group = DragGroup("test").apply {
            items = listOf("a", "b", "c").map { DragItem(it, it) }
            commit = { error("Preview must not write configuration") }
        }
        val state = SettingsDragState().apply {
            viewport = Rect(0f, 0f, 360f, 600f)
            // Variable row heights and a real eight-pixel gap.
            register(group, "a", Rect(0f, 20f, 360f, 80f), 64f)
            register(group, "b", Rect(0f, 88f, 360f, 188f), 64f)
            register(group, "c", Rect(0f, 196f, 360f, 276f), 64f)
        }
        return state to group
    }

    @Test fun titleAreaStartsDragButTrailingControlsAndWhitespaceDoNot() {
        val (state, _) = fixture()
        assertTrue(state.canStart(Offset(180f, 40f)))
        assertFalse(state.canStart(Offset(340f, 40f)))
        assertFalse(state.canStart(Offset(180f, 84f)))
        assertFalse(state.canStart(Offset(180f, 350f)))
    }

    @Test fun multiRowPreviewUsesMeasuredSlotsAndCancelRestoresSavedOrder() {
        val (state, group) = fixture()
        assertTrue(state.begin(Offset(180f, 40f)))
        state.move(186f)
        assertEquals(listOf("b", "c", "a"), state.preview(group))
        assertEquals(-68f, state.shift(group, "b"), .01f)
        assertEquals(196f, state.shift(group, "a"), .01f)
        assertEquals(listOf("a", "b", "c"), group.items.map { it.key })
        state.cancel()
        assertEquals(listOf("a", "b", "c"), state.preview(group))
        assertEquals(0f, state.shift(group, "b"), .01f)
    }

    @Test fun reverseDragAndDifferentGroupsRemainIndependent() {
        val (state, group) = fixture()
        val other = DragGroup("other").apply { items = listOf(DragItem("x", "x"), DragItem("y", "y")) }
        state.register(other, "x", Rect(0f, 300f, 360f, 370f), 64f)
        assertTrue(state.begin(Offset(180f, 220f)))
        state.move(-200f)
        assertEquals(listOf("c", "a", "b"), state.preview(group))
        assertEquals(88f, state.shift(group, "b"), .01f)
        assertEquals(listOf("x", "y"), state.preview(other))
        state.remove(group)
        assertFalse(state.active)
    }

    @Test fun invalidAccessibleMovesPreserveTheOrder() {
        assertEquals(listOf("a", "b"), reordered(listOf("a", "b"), 0, -1))
        assertEquals(listOf("b", "a"), reordered(listOf("a", "b"), 0, 1))
    }
}
