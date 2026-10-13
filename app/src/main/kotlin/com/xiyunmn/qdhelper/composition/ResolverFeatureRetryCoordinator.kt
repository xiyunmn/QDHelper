package com.xiyunmn.qdhelper.composition

import android.os.Handler
import android.os.Looper
import com.xiyunmn.qdhelper.hook.resource.RuntimeCleanupRegistry
import com.xiyunmn.qdhelper.host.api.target.TargetId
import com.xiyunmn.qdhelper.host.resolver.runtime.ResolverInstallRetryEvents
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Delivers one target-scoped reinstall request per resolver generation. */
internal object ResolverFeatureRetryCoordinator {
    private val generation = AtomicLong(0L)
    private val deliveredTargets = ConcurrentHashMap.newKeySet<String>()

    fun configure(processName: String, retry: (Set<TargetId>) -> Unit) {
        val currentGeneration = generation.incrementAndGet()
        deliveredTargets.clear()
        ResolverInstallRetryEvents.configure { event ->
            if (event.processName != processName || generation.get() != currentGeneration) {
                return@configure
            }
            val newlyEligible = event.targetIds
                .toSet()
                .filterTo(linkedSetOf()) { target ->
                    deliveredTargets.add("$currentGeneration:${target.value}")
                }
            if (newlyEligible.isEmpty()) return@configure
            Handler(Looper.getMainLooper()).post {
                if (generation.get() == currentGeneration) retry(newlyEligible)
            }
        }
        RuntimeCleanupRegistry.register("resolver-install-retry") {
            generation.incrementAndGet()
            deliveredTargets.clear()
            ResolverInstallRetryEvents.clear()
        }
    }
}
