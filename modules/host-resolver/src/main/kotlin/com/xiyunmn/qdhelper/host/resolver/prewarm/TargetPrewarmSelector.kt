package com.xiyunmn.qdhelper.host.resolver.prewarm

import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetSpec

internal enum class TargetPrewarmMode(
    val logName: String,
    val coversFullCatalog: Boolean,
) {
    BOOTSTRAP_FULL("bootstrap-full", true),
    ROUTINE("routine", false),
    FORCED_FULL("forced-full", true),
}

internal data class TargetPrewarmSelection(
    val mode: TargetPrewarmMode,
    val targets: List<HostTargetSpec>,
    val forceRescan: Boolean,
)

/** Keeps routine startup light while ensuring each resolver fingerprint checks the full catalog once. */
internal object TargetPrewarmSelector {
    fun select(
        catalogTargets: List<HostTargetSpec>,
        routineTargets: List<HostTargetSpec>,
        forceRescan: Boolean,
        fullCatalogAttempted: Boolean,
    ): TargetPrewarmSelection {
        val mode = when {
            forceRescan -> TargetPrewarmMode.FORCED_FULL
            !fullCatalogAttempted -> TargetPrewarmMode.BOOTSTRAP_FULL
            else -> TargetPrewarmMode.ROUTINE
        }
        if (!mode.coversFullCatalog) {
            return TargetPrewarmSelection(mode, routineTargets, forceRescan = false)
        }

        val catalogIds = catalogTargets.mapTo(linkedSetOf()) { it.key.id }
        require(catalogIds.size == catalogTargets.size) { "Full target catalog contains duplicate IDs" }
        val routineIds = routineTargets.mapTo(linkedSetOf()) { it.key.id }
        require(routineIds.size == routineTargets.size) { "Routine prewarm targets contain duplicate IDs" }
        require(catalogIds.containsAll(routineIds)) { "Routine prewarm targets must belong to the full catalog" }

        val orderedTargets = ArrayList<HostTargetSpec>(catalogTargets.size)
        orderedTargets.addAll(routineTargets)
        catalogTargets.forEach { target ->
            if (target.key.id !in routineIds) orderedTargets += target
        }
        return TargetPrewarmSelection(mode, orderedTargets, forceRescan)
    }
}
