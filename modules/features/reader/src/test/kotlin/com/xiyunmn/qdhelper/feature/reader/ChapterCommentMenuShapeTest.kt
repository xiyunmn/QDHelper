package com.xiyunmn.qdhelper.feature.reader

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.junit.Assert.*
import org.junit.Test

class ChapterCommentMenuShapeTest {
    private val legacy = listOf("int", "int", "boolean", "boolean", "int", "int", "int", "int", "boolean")

    @Test fun supportsLegacyAndDirectSpoilerInitializer() {
        assertTrue(ChapterCommentMenuShape.matches("initShow", legacy))
        assertTrue(ChapterCommentMenuShape.matches("initShow", legacy + HostSymbols.Classes.SPOILER_ACTION))
    }

    @Test fun rejectsUnrelatedOverloadsAndDefaultBridge() {
        assertFalse(ChapterCommentMenuShape.matches("initShow", legacy + "java.lang.Object"))
        assertFalse(ChapterCommentMenuShape.matches("initShow", legacy.dropLast(1)))
        assertFalse(ChapterCommentMenuShape.matches("initShow\$default", legacy))
        assertFalse(ChapterCommentMenuShape.matches("other", legacy))
    }
}
