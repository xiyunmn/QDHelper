package com.xiyunmn.qdhelper.host.resolver.dexkit.query

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.Cardinality
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetCatalog
import com.xiyunmn.qdhelper.host.resolver.catalog.TargetRisk
import org.junit.Assert.*
import org.junit.Test
import org.luckypray.dexkit.query.FindMethod

class WelfareRecoveryQueriesTest {
    @Test fun allRecoveryContractsAreUniqueVersionedAndSerializable() {
        val keys = WelfareTargetKeys.recovery.map { it.id }.toSet()
        val specs = HostTargetCatalog.forProcess(HostSymbols.PACKAGE).filter { it.key.id in keys }
        assertEquals(keys, specs.map { it.key.id }.toSet())
        assertEquals(17, specs.size)
        specs.forEach {
            assertEquals(Cardinality.One, it.cardinality)
            assertEquals(TargetRisk.HIGH, it.risk)
            assertTrue(it.revision >= 1)
            assertTrue(it.semantic.queryId.endsWith(".r${it.revision}"))
            assertTrue(FindMethod.create().apply(it.query.query).toString().isNotBlank())
        }
    }

    @Test fun subscriptionVerifierRejectsWrongConsumerAndNonRxOwner() {
        val candidates = SubscriptionLookalike::class.java.declaredMethods
        assertTrue(candidates.isNotEmpty())
        candidates.forEach { assertFalse(WelfareRecoveryContracts.isSubscribe(it)) }
    }

    private class SubscriptionLookalike {
        fun subscribe(first: Runnable, second: Runnable): AutoCloseable = AutoCloseable {}
        fun subscribe(first: Any, second: Any): Any = Any()
    }
}
