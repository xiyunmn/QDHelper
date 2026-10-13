package com.xiyunmn.qdhelper.feature.api.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyReadingDataTransformerChainTest {
    @Test
    fun `transformers run by descending priority`() {
        val chain = DailyReadingDataTransformerChain()
        chain.register("low", priority = 10) { "$it-low" }
        chain.register("high", priority = 100) { "$it-high" }

        assertEquals("raw-high-low", chain.transform("raw"))
    }

    @Test
    fun `same owner replacement and close are identity safe`() {
        val chain = DailyReadingDataTransformerChain()
        val old = chain.register("owner", priority = 10) { "$it-old" }
        val current = chain.register("owner", priority = 10) { "$it-current" }

        old.close()
        assertEquals("raw-current", chain.transform("raw"))

        current.close()
        assertEquals("raw", chain.transform("raw"))
    }

    @Test
    fun `failed transformer keeps current response and continues`() {
        val failures = mutableListOf<String>()
        val chain = DailyReadingDataTransformerChain { ownerId, _ -> failures += ownerId }
        chain.register("failure", priority = 100) { error("boom") }
        chain.register("next", priority = 10) { "$it-next" }

        assertEquals("raw-next", chain.transform("raw"))
        assertEquals(listOf("failure"), failures)
    }
}
