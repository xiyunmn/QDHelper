package com.xiyunmn.qdhelper.host.resolver.runtime

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.target.TargetId

/**
 * Completion signal from the resolver to the application composition root.
 * Host-resolver reports only targets that changed from unresolved to resolved;
 * the app decides which features to retry and re-checks activation.
 */
object ResolverInstallRetryEvents {
    @Volatile
    private var listener: ((ResolverInstallRetryEvent) -> Unit)? = null

    fun configure(listener: ((ResolverInstallRetryEvent) -> Unit)?) {
        this.listener = listener
    }

    fun clear() {
        listener = null
    }

    internal fun publish(event: ResolverInstallRetryEvent) {
        if (event.targetIds.isEmpty() || event.owners.isEmpty()) return
        runCatching { listener?.invoke(event) }
    }
}

data class ResolverInstallRetryEvent(
    val processName: String,
    val owners: Set<OwnerId>,
    val targetIds: Set<TargetId>,
)
