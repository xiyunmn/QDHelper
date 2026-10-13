package com.xiyunmn.qdhelper.host.resolver.prewarm

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetCatalog
import com.xiyunmn.qdhelper.host.resolver.catalog.PrewarmPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPrewarmSelectorTest {
    private val catalog = HostTargetCatalog.forProcess(HostSymbols.PACKAGE)
    private val activeOwners = catalog.mapTo(linkedSetOf()) { it.owner }
    private val routine = HostTargetCatalog.prewarmForProcess(HostSymbols.PACKAGE, activeOwners)

    @Test
    fun newFingerprintChecksFullCatalogWithRoutineTargetsFirst() {
        val selection = TargetPrewarmSelector.select(
            catalogTargets = catalog,
            routineTargets = routine,
            forceRescan = false,
            fullCatalogAttempted = false,
        )

        assertEquals(TargetPrewarmMode.BOOTSTRAP_FULL, selection.mode)
        assertFalse(selection.forceRescan)
        assertEquals(catalog.size, selection.targets.size)
        assertTrue(routine.isNotEmpty())
        assertEquals(
            routine.map { it.key.id },
            selection.targets.take(routine.size).map { it.key.id },
        )
        val delayedTargets = selection.targets.drop(routine.size)
        assertEquals(catalog.size - routine.size, delayedTargets.size)
        assertTrue(delayedTargets.all { it.prewarm == PrewarmPolicy.NEVER })
        assertEquals(catalog.map { it.key.id }.toSet(), selection.targets.map { it.key.id }.toSet())
    }

    @Test
    fun completedFingerprintUsesRoutinePrewarmOnly() {
        val selection = TargetPrewarmSelector.select(
            catalogTargets = catalog,
            routineTargets = routine,
            forceRescan = false,
            fullCatalogAttempted = true,
        )

        assertEquals(TargetPrewarmMode.ROUTINE, selection.mode)
        assertFalse(selection.forceRescan)
        assertEquals(routine.map { it.key.id }, selection.targets.map { it.key.id })
    }

    @Test
    fun explicitForceRescanAlwaysChecksFullCatalog() {
        val selection = TargetPrewarmSelector.select(
            catalogTargets = catalog,
            routineTargets = routine,
            forceRescan = true,
            fullCatalogAttempted = true,
        )

        assertEquals(TargetPrewarmMode.FORCED_FULL, selection.mode)
        assertTrue(selection.forceRescan)
        assertEquals(catalog.size, selection.targets.size)
        assertEquals(catalog.map { it.key.id }.toSet(), selection.targets.map { it.key.id }.toSet())
    }
}
