package com.xiyunmn.qdhelper.shared.fonts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontLibraryModelTest {
    @Test
    fun indexedOrderIsKeptWhileManualFilesAreAppended() {
        val root = File(System.getProperty("java.io.tmpdir"), "qdhelper-font-model")
        val first = File(root, "first.ttf").canonicalPath
        val alias = File(root, "sub/../first.ttf").path
        val referenced = File(root, "selected.otf").canonicalPath
        val discovered = File(root, "manual.ttf").canonicalPath

        assertEquals(
            listOf(first, referenced, discovered),
            mergeFontPaths(listOf(first, alias), listOf(referenced), listOf(discovered, first)),
        )
    }

    @Test
    fun unusableEntriesRemainDistinguishableFromReadyEntries() {
        assertTrue(FontFileState.READY.isUsable)
        assertFalse(FontFileState.UNREADABLE.isUsable)
        assertEquals("文件不存在", FontFileState.MISSING.displayLabel())
        assertEquals("文件超过 80 MiB", FontFileState.TOO_LARGE.displayLabel())
    }

    @Test
    fun aliasesAreDeduplicatedWhenAppending() {
        val path = File("fonts/selected.ttf").canonicalPath
        assertEquals(
            listOf(path),
            appendFontWithoutReordering(listOf(File("fonts/./selected.ttf").path), path),
        )
    }

    @Test
    fun fileSignatureChangesWhenAFontIsReplaced() {
        val file = File.createTempFile("qdhelper-font-", ".ttf")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val before = FontLibraryStore.fileSignature(file)
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            val after = FontLibraryStore.fileSignature(file)
            assertNotEquals(before, after)
        } finally {
            file.delete()
        }
    }
}
