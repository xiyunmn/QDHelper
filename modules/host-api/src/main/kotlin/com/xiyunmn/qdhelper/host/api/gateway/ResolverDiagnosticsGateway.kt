package com.xiyunmn.qdhelper.host.api.gateway

data class ResolverRuleStatus(
    val id: String,
    val purpose: String,
    val multiple: Boolean,
    val resolved: Boolean,
    val count: Int,
    val matchedNames: List<String>,
)

data class ResolverStatusSnapshot(
    val total: Int,
    val success: Int,
    val fingerprint: String,
    val unavailableReason: String?,
    val prewarmAttempted: Boolean,
    val environment: String,
    val rules: List<ResolverRuleStatus>,
)

fun interface ResolverRescanCallback {
    fun onComplete(hits: Int, failures: Int, unavailableReason: String?)
}

interface ResolverDiagnosticsGateway {
    fun snapshot(): ResolverStatusSnapshot
    fun rescan(classLoader: ClassLoader, callback: ResolverRescanCallback)
}
