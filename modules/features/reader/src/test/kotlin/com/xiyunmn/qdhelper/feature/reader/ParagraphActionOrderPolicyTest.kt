package com.xiyunmn.qdhelper.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ParagraphActionOrderPolicyTest {
    @Test
    fun defaultOrderContainsNativeActionsThenCopy() {
        assertEquals(
            listOf("comment", "listen", "dictionary", "mark", "reward", "share", "dubbing", "correction", "copy"),
            ParagraphActionOrderPolicy.normalize(null),
        )
    }

    @Test
    fun missingKeysAreAppendedAndUnknownKeysAreDropped() {
        assertEquals(
            listOf("share", "comment", "listen", "dictionary", "mark", "reward", "dubbing", "correction", "copy"),
            ParagraphActionOrderPolicy.normalize("unknown,share,comment,share"),
        )
    }

}
