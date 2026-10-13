package com.xiyunmn.qdhelper.shared.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedIoTest {
    @Test
    fun exactLimitSucceedsAndOneExtraByteFails() {
        val bytes = ByteArray(16) { it.toByte() }
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readBytesBounded(16))
        assertThrows(SizeLimitExceededException::class.java) {
            ByteArrayInputStream(bytes).readBytesBounded(15)
        }
    }

    @Test
    fun copyDoesNotWriteTheSentinelByte() {
        val output = ByteArrayOutputStream()
        assertThrows(SizeLimitExceededException::class.java) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3)).copyBoundedTo(output, 2)
        }
        assertEquals(listOf<Byte>(1, 2), output.toByteArray().toList())
    }
}
