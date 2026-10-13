package com.xiyunmn.qdhelper.host.resolver.cache

import com.xiyunmn.qdhelper.host.api.target.Cardinality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolutionCacheCodecTest {
    @Test
    fun rejectsDamagedAndPartialMultiPayloadAsAWhole() {
        val many = Cardinality.Many(2, 3, "fixture")
        val encoded = ResolutionCacheCodec.encode(listOf("one", "two"), many)

        assertEquals(listOf("one", "two"), ResolutionCacheCodec.decode(requireNotNull(encoded), many))
        assertNull(ResolutionCacheCodec.decode("count=2\none\n", many))
        assertNull(ResolutionCacheCodec.decode("count=4\none\ntwo\nthree\nfour\n", many))
        assertNull(ResolutionCacheCodec.encode(listOf("one"), many))
    }
}
