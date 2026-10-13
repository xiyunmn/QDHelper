package com.xiyunmn.qdhelper.host.resolver.prewarm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.Cardinality
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetCatalog
import com.xiyunmn.qdhelper.host.resolver.dexkit.DexKitAvailability
import com.xiyunmn.qdhelper.host.resolver.environment.ResolverEnvironment
import com.xiyunmn.qdhelper.host.resolver.runtime.ResolverInstallRetryEvent
import com.xiyunmn.qdhelper.host.resolver.runtime.ResolverInstallRetryEvents
import com.xiyunmn.qdhelper.host.resolver.runtime.ResolverSessionRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Projects prewarm work and diagnostics exclusively from [HostTargetCatalog]. */
object TargetPrewarmPlanner {
    private const val PREWARM_ATTEMPTED = "prewarm.attempted"
    private const val FULL_CATALOG_ATTEMPTED = "prewarm.full_catalog_attempted.v1"
    private const val START_DELAY_MS = 2_500L
    private val callbackToken = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val scheduledKeys = ConcurrentHashMap.newKeySet<String>()
    private val legacyCacheCleanupAttempted = AtomicBoolean(false)

    fun schedule(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        reason: String,
        activeOwners: Set<OwnerId>,
    ) {
        val appContext = context?.applicationContext ?: context ?: return
        if (!HostSymbols.isMainProcess(processName) && !HostSymbols.isMiniProcess(processName)) return
        if (DexKitAvailability.unavailableReason(appContext, processName) != null) return
        val session = ResolverSessionRegistry.session(appContext, classLoader, processName)
        cleanupLegacyCacheOnce(session)
        val forceRescan = ResolverEnvironment.isForceRescanEnabled(appContext)
        val catalogTargets = HostTargetCatalog.forProcess(processName)
        val routineTargets = HostTargetCatalog.prewarmForProcess(processName, activeOwners)
        val selection = TargetPrewarmSelector.select(
            catalogTargets = catalogTargets,
            routineTargets = routineTargets,
            forceRescan = forceRescan,
            fullCatalogAttempted = session.readFlag(FULL_CATALOG_ATTEMPTED),
        )
        val ownerKey = activeOwners.map { it.value }.sorted().joinToString(",")
        val scheduleKey = "$processName:${session.fingerprint}:force=$forceRescan:owners=$ownerKey"
        if (!scheduledKeys.add(scheduleKey)) return
        val runnable = Runnable {
            ResolverEnvironment.info(
                "Target prewarm start: mode=${selection.mode.logName}, targets=${selection.targets.size}, " +
                    "reason=$reason, fingerprint=${session.fingerprint}",
            )
            val startedAt = System.currentTimeMillis()
            session.prewarm(selection.targets, selection.forceRescan) { result ->
                session.writeFlag(PREWARM_ATTEMPTED, true)
                if (selection.mode.coversFullCatalog && result.unavailableReason == null) {
                    markFullCatalogAttempted(session, selection.mode)
                }
                if (result.failures > 0 || result.unavailableReason != null) {
                    scheduledKeys.remove(scheduleKey)
                }
                ResolverEnvironment.info(
                    "Target prewarm done: mode=${selection.mode.logName}, hits=${result.hits}, " +
                        "failures=${result.failures}, " +
                        "elapsedMs=${System.currentTimeMillis() - startedAt}, unavailable=${result.unavailableReason}",
                )
                if (result.newlyResolvedTargetIds.isNotEmpty()) {
                    ResolverInstallRetryEvents.publish(
                        ResolverInstallRetryEvent(
                            processName = processName,
                            owners = result.newlyResolvedOwners,
                            targetIds = result.newlyResolvedTargetIds,
                        ),
                    )
                }
            }
        }
        handler.postAtTime(runnable, callbackToken, SystemClock.uptimeMillis() + START_DELAY_MS)
        ResolverEnvironment.info(
            "Target prewarm scheduled: mode=${selection.mode.logName}, targets=${selection.targets.size}, " +
                "reason=$reason, fingerprint=${session.fingerprint}",
        )
    }

    internal fun statusSnapshot(context: Context?, processName: String): ResolverStatus {
        val appContext = context?.applicationContext ?: context
        val unavailable = if (appContext == null) {
            "context_missing"
        } else {
            DexKitAvailability.unavailableReason(appContext, processName)
        }
        val targets = HostTargetCatalog.forProcess(processName)
        val session = appContext?.let { ResolverSessionRegistry.session(it, it.classLoader, processName) }
        val rules = targets.map { spec ->
            val matchedNames = session?.cachedMethodNames(spec).orEmpty()
            ResolverRuleState(
                id = spec.key.id.value,
                purpose = spec.purpose,
                multiple = spec.cardinality is Cardinality.Many,
                resolved = matchedNames.isNotEmpty(),
                count = matchedNames.size,
                matchedNames = matchedNames,
            )
        }.sortedWith(compareBy({ it.resolved }, { it.id }))
        return ResolverStatus(
            total = rules.size,
            success = rules.count { it.resolved },
            fingerprint = session?.fingerprint.orEmpty(),
            unavailableReason = unavailable,
            prewarmAttempted = session?.readFlag(PREWARM_ATTEMPTED) ?: false,
            environment = appContext?.let(DexKitAvailability::environmentSummary).orEmpty(),
            rules = rules,
        )
    }

    internal fun rescanNow(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        onDone: (hits: Int, failures: Int, unavailableReason: String?) -> Unit,
    ) {
        val appContext = context?.applicationContext ?: context
        val unavailable = if (appContext == null) {
            "context_missing"
        } else {
            DexKitAvailability.unavailableReason(appContext, processName)
        }
        if (appContext == null || unavailable != null) {
            onDone(0, 0, unavailable)
            return
        }
        val session = ResolverSessionRegistry.session(appContext, classLoader, processName)
        session.prewarm(HostTargetCatalog.forProcess(processName), forceRescan = true) { result ->
            session.writeFlag(PREWARM_ATTEMPTED, true)
            if (result.unavailableReason == null) {
                markFullCatalogAttempted(session, TargetPrewarmMode.FORCED_FULL)
            }
            onDone(result.hits, result.failures, result.unavailableReason)
        }
    }

    fun close() {
        handler.removeCallbacksAndMessages(callbackToken)
        scheduledKeys.clear()
        legacyCacheCleanupAttempted.set(false)
    }

    private fun cleanupLegacyCacheOnce(session: com.xiyunmn.qdhelper.host.resolver.runtime.ResolverSession) {
        if (!legacyCacheCleanupAttempted.compareAndSet(false, true)) return
        val removed = session.clearLegacyCacheEntries() ?: return
        ResolverEnvironment.info("Legacy DexKit settings cache cleanup: removed=$removed")
    }

    private fun markFullCatalogAttempted(
        session: com.xiyunmn.qdhelper.host.resolver.runtime.ResolverSession,
        mode: TargetPrewarmMode,
    ) {
        if (session.writeFlag(FULL_CATALOG_ATTEMPTED, true)) return
        ResolverEnvironment.warn(
            "Target prewarm full-catalog marker write failed: " +
                "mode=${mode.logName}, fingerprint=${session.fingerprint}",
        )
    }
}

internal data class ResolverRuleState(
    val id: String,
    val purpose: String,
    val multiple: Boolean,
    val resolved: Boolean,
    val count: Int,
    val matchedNames: List<String>,
)

internal data class ResolverStatus(
    val total: Int,
    val success: Int,
    val fingerprint: String,
    val unavailableReason: String?,
    val prewarmAttempted: Boolean,
    val environment: String,
    val rules: List<ResolverRuleState>,
)
