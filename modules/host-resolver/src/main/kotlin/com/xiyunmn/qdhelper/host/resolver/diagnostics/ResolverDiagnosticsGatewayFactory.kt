package com.xiyunmn.qdhelper.host.resolver.diagnostics

import android.content.Context
import com.xiyunmn.qdhelper.host.api.gateway.ResolverDiagnosticsGateway
import com.xiyunmn.qdhelper.host.api.gateway.ResolverRescanCallback
import com.xiyunmn.qdhelper.host.api.gateway.ResolverRuleStatus
import com.xiyunmn.qdhelper.host.api.gateway.ResolverStatusSnapshot
import com.xiyunmn.qdhelper.host.resolver.prewarm.TargetPrewarmPlanner

/** Adapts the resolver implementation to the host-api diagnostics contract. */
fun resolverDiagnosticsGateway(
    contextProvider: () -> Context?,
    processName: String,
): ResolverDiagnosticsGateway = ResolverDiagnosticsGatewayAdapter(contextProvider, processName)

private class ResolverDiagnosticsGatewayAdapter(
    private val contextProvider: () -> Context?,
    private val processName: String,
) : ResolverDiagnosticsGateway {
    override fun snapshot(): ResolverStatusSnapshot {
        val snapshot = TargetPrewarmPlanner.statusSnapshot(contextProvider(), processName)
        return ResolverStatusSnapshot(
            total = snapshot.total,
            success = snapshot.success,
            fingerprint = snapshot.fingerprint,
            unavailableReason = snapshot.unavailableReason,
            prewarmAttempted = snapshot.prewarmAttempted,
            environment = snapshot.environment,
            rules = snapshot.rules.map { rule ->
                ResolverRuleStatus(
                    id = rule.id,
                    purpose = rule.purpose,
                    multiple = rule.multiple,
                    resolved = rule.resolved,
                    count = rule.count,
                    matchedNames = rule.matchedNames,
                )
            },
        )
    }

    override fun rescan(classLoader: ClassLoader, callback: ResolverRescanCallback) {
        TargetPrewarmPlanner.rescanNow(
            context = contextProvider(),
            classLoader = classLoader,
            processName = processName,
        ) { hits, failures, unavailableReason ->
            callback.onComplete(hits, failures, unavailableReason)
        }
    }
}
