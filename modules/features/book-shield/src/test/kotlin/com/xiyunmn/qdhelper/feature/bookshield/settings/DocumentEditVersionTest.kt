package com.xiyunmn.qdhelper.feature.bookshield.settings

import org.junit.Assert.*
import org.junit.Test

class DocumentEditVersionTest {
    @Test fun saveCompletionAfterReopeningCannotReplaceNewInputEvenIfTextWasChangedBack() {
        val version = DocumentEditVersion()
        version.edited()
        val saving = version.capture()
        version.edited() // new editor, new input
        version.edited() // undo to the same text must still retain dirty state
        assertTrue(version.belongsToDocument(saving))
        assertFalse(version.canReplaceDraft(saving))
        val nextSave = version.capture()
        assertTrue(version.canReplaceDraft(nextSave))
    }

    @Test fun resetInvalidatesQueuedSavesAndOutstandingExternalEditor() {
        val version = DocumentEditVersion()
        val save = version.capture()
        val external = version.capture()
        version.invalidate()
        assertFalse(version.belongsToDocument(save))
        assertFalse(version.canReplaceDraft(external))
        assertTrue(version.canReplaceDraft(version.capture()))
    }
}
