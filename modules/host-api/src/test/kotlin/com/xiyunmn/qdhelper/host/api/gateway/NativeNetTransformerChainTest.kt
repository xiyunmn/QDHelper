package com.xiyunmn.qdhelper.host.api.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeNetTransformerChainTest {
    @Test
    fun appliesByPriorityAndSupportsRemoval() {
        val chain = NativeNetTransformerChain()
        val low = chain.register("low", 10) { path, value -> "$value|low:$path" }
        chain.register("high", 100) { _, value -> "$value|high" }

        assertEquals("body|high|low:/books", chain.transform("/books", "body"))
        low.close()
        assertEquals("body|high", chain.transform("/books", "body"))
    }

    @Test
    fun isolatesTransformerFailure() {
        val failures = mutableListOf<String>()
        val chain = NativeNetTransformerChain { owner, _ -> failures += owner }
        chain.register("broken", 100) { _, _ -> error("boom") }
        chain.register("healthy", 10) { _, value -> "$value|ok" }

        assertEquals("body|ok", chain.transform("/path", "body"))
        assertEquals(listOf("broken"), failures)
    }
}
