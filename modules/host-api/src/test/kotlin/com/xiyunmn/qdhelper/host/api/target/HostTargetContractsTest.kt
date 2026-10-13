package com.xiyunmn.qdhelper.host.api.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostTargetContractsTest {
    @Test
    fun manyCardinalityRequiresBoundedJustifiedRange() {
        val key = TargetKey.MethodTarget(
            TargetId("reader.page-methods"),
            Cardinality.Many(min = 1, max = 4, justification = "host exposes one method per renderer"),
        )

        assertEquals("reader.page-methods", key.id.value)
        assertThrows(IllegalArgumentException::class.java) {
            Cardinality.Many(min = 2, max = 1, justification = "invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Cardinality.Many(min = 0, max = 1, justification = "")
        }
    }
}
