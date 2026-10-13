package com.xiyunmn.qdhelper.host.api.target

/** Stable host targets used only by explicit maintenance actions. */
object MaintenanceTargetKeys {
    val userLookupSearchRequest = method("maintenance.user_lookup.search_request")

    val all: Set<TargetKey.MethodTarget> = linkedSetOf(userLookupSearchRequest)

    private fun method(id: String) = TargetKey.MethodTarget(TargetId(id), Cardinality.One)
}
