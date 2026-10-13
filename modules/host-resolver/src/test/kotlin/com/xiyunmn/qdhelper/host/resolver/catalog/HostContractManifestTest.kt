package com.xiyunmn.qdhelper.host.resolver.catalog

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.resolver.dexkit.CandidatePipeline
import java.io.File
import java.util.Properties
import org.junit.Assert.assertTrue
import org.junit.Test

class HostContractManifestTest {
    @Test
    fun exportsCurrentCompiledCatalogForAuditValidation() {
        val targets = HostTargetCatalog.forProcess(HostSymbols.PACKAGE)
        val properties = Properties().apply {
            setProperty("schemaVersion", "3")
            setProperty("catalogHash", HostTargetCatalog.sha256(HostSymbols.PACKAGE))
            targets.forEach { target ->
                val prefix = "target.${target.key.id.value}"
                setProperty("$prefix.revision", target.revision.toString())
                setProperty("$prefix.cardinality", CandidatePipeline.cardinalityLabel(target.cardinality))
                setProperty("$prefix.owner", target.owner.value)
                setProperty("$prefix.risk", target.risk.name)
            }
        }
        assertTrue(targets.isNotEmpty())
        System.getProperty("qdhelper.hostContractManifest")?.let { path ->
            val file = File(path)
            requireNotNull(file.parentFile).mkdirs()
            file.outputStream().use { properties.store(it, null) }
        }
    }
}
