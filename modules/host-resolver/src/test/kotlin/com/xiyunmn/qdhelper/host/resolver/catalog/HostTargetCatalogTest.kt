package com.xiyunmn.qdhelper.host.resolver.catalog

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.Cardinality
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTargetCatalogTest {
    @Test
    fun catalogsContainUniqueCompleteContracts() {
        for (process in listOf(HostSymbols.PACKAGE, "${HostSymbols.PACKAGE}:mini0")) {
            val targets = HostTargetCatalog.forProcess(process)
            assertTrue(targets.isNotEmpty())
            assertEquals(targets.size, targets.map { it.key.id }.toSet().size)
            for (target in targets) {
                assertTrue(target.revision > 0 && target.purpose.isNotBlank())
                assertTrue(target.semantic.queryId.isNotBlank() && target.semantic.candidateVerifierId.isNotBlank())
                assertEquals(target.key.cardinality, target.cardinality)
                val many = target.cardinality as? Cardinality.Many ?: continue
                assertTrue(many.min >= 1 && many.max >= many.min && many.justification.isNotBlank())
            }
        }
    }

    @Test
    fun prewarmFollowsActiveOwnersAndLeavesManualTargetsDormant() {
        assertTrue(HostTargetCatalog.prewarmForProcess(HostSymbols.PACKAGE, emptySet()).isEmpty())
        for (name in listOf("reader", "account", "welfare")) {
            val owner = OwnerId(name)
            val targets = HostTargetCatalog.prewarmForProcess(HostSymbols.PACKAGE, setOf(owner))
            assertTrue(targets.isNotEmpty())
            assertTrue(targets.all { it.owner == owner })
            assertTrue(targets.none {
                it.timing == ResolutionTiming.ON_ACTION || it.timing == ResolutionTiming.PROBE_ONLY
            })
        }
        assertTrue(HostTargetCatalog.prewarmForProcess(
            HostSymbols.PACKAGE,
            setOf(OwnerId("network-identity"), OwnerId("diagnostics")),
        ).isEmpty())
    }

    @Test
    fun miniCatalogAndPrewarmStayIsolatedFromMainProcess() {
        val mainTargets = HostTargetCatalog.forProcess(HostSymbols.PACKAGE)
        val miniProcess = "${HostSymbols.PACKAGE}:mini0"
        val miniTargets = HostTargetCatalog.forProcess(miniProcess)
        val miniIds = miniTargets.map { it.key.id }.toSet()

        assertEquals(WelfareTargetKeys.mini.map { it.id }.toSet(), miniIds)
        assertTrue(mainTargets.none { it.key.id in miniIds })
        assertTrue(mainTargets.all { it.process == "main" })
        assertTrue(miniTargets.all { it.process == "mini" })
        assertTrue(HostTargetCatalog.prewarmForProcess(miniProcess, setOf(OwnerId("reader"))).isEmpty())
        assertEquals(
            miniIds,
            HostTargetCatalog.prewarmForProcess(miniProcess, setOf(OwnerId("welfare"))).map { it.key.id }.toSet(),
        )
    }
}
