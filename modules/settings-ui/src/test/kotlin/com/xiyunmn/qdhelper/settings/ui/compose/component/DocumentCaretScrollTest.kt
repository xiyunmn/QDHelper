package com.xiyunmn.qdhelper.settings.ui.compose.component

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentCaretScrollTest {
    @Test fun keyboardOpeningRevealsLowerCaretWithMargin() {
        assertEquals(564, documentCaretScrollTarget(720f, 740f, 200, 400, 1000, 24))
    }

    @Test fun alreadyVisibleCaretDoesNotMoveTheDocument() {
        assertEquals(400, documentCaretScrollTarget(450f, 470f, 200, 400, 1000, 24))
    }

    @Test fun selectionAboveViewportScrollsUp() {
        assertEquals(276, documentCaretScrollTarget(300f, 320f, 200, 400, 1000, 24))
    }

    @Test fun documentEndsAndSmallViewportsStayWithinScrollBounds() {
        assertEquals(0, documentCaretScrollTarget(0f, 20f, 200, 100, 1000, 24))
        assertEquals(800, documentCaretScrollTarget(980f, 1000f, 200, 400, 800, 24))
        assertEquals(280, documentCaretScrollTarget(300f, 320f, 40, 100, 280, 24))
    }
}
