package com.xiyunmn.qdhelper.host.resolver.runtime

import android.content.Context
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.Cardinality
import com.xiyunmn.qdhelper.host.api.target.Resolution
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.resolver.cache.HostBuildFingerprint
import com.xiyunmn.qdhelper.host.resolver.cache.ResolutionCache
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetCatalog
import com.xiyunmn.qdhelper.host.resolver.catalog.HostTargetSpec
import com.xiyunmn.qdhelper.host.resolver.dexkit.CandidatePipeline
import com.xiyunmn.qdhelper.host.resolver.dexkit.CandidateStatus
import com.xiyunmn.qdhelper.host.resolver.dexkit.DexKitAvailability
import com.xiyunmn.qdhelper.host.resolver.dexkit.DexKitEngine
import com.xiyunmn.qdhelper.host.resolver.dexkit.DexKitScanResult
import com.xiyunmn.qdhelper.host.resolver.dexkit.reflection.DexMethodCodec
import com.xiyunmn.qdhelper.host.resolver.environment.ResolverEnvironment
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal class ResolverSession(
    private val context: Context,
    val classLoader: ClassLoader,
    val processName: String,
) : AutoCloseable {
    private val specs = HostTargetCatalog.forProcess(processName).associateBy { it.key.id }
    private val memory = ConcurrentHashMap<String, List<Method>>()
    private val cache = ResolutionCache(context)

    private val buildFingerprint: HostBuildFingerprint? by lazy {
        runCatching {
            HostBuildFingerprint.from(
                context = context,
                processName = processName,
                resolverSchema = HostTargetCatalog.SCHEMA_VERSION,
                dexKitVersion = DexKitEngine.VERSION,
                catalogSha256 = HostTargetCatalog.sha256(processName),
            )
        }.onFailure { throwable ->
            ResolverEnvironment.warn(
                "Host resolver fingerprint unavailable: process=$processName, error=${throwable.javaClass.name}",
            )
        }.getOrNull()
    }

    val fingerprint: String? by lazy { buildFingerprint?.sha256() }
    private val cacheFingerprint: String? by lazy { buildFingerprint?.cacheSha256() }

    fun <T : Any> resolve(key: TargetKey<T>, forceRescan: Boolean): Resolution<T> {
        val spec = contractSpec(key) ?: return Resolution.Unavailable("target_not_registered:${key.id}", false)
        val result = resolveSpec(spec, forceRescan)
        @Suppress("UNCHECKED_CAST")
        return result as Resolution<T>
    }

    fun cachedCount(spec: HostTargetSpec): Int {
        return cachedMethods(spec).size
    }

    fun cachedMethodNames(spec: HostTargetSpec): List<String> =
        cachedMethods(spec).map(::diagnosticName)

    private fun cachedMethods(spec: HostTargetSpec): List<Method> {
        val activeFingerprint = cacheFingerprint ?: return emptyList()
        memory[memoryKey(spec, activeFingerprint)]?.takeIf { valid(spec, it) }?.let { return it }
        if (cache.isNegative(spec, activeFingerprint)) return emptyList()
        val serialized = cache.read(spec, activeFingerprint) ?: return emptyList()
        val methods = materialize(serialized, spec)
        if (methods == null) {
            cache.delete(spec, activeFingerprint)
            return emptyList()
        }
        memory[memoryKey(spec, activeFingerprint)] = methods
        return methods
    }

    private fun diagnosticName(method: Method): String = buildString {
        append(method.declaringClass.name)
        append('#').append(method.name).append('(')
        append(method.parameterTypes.joinToString(",") { it.typeName })
        append("): ").append(method.returnType.typeName)
    }

    fun prewarm(
        targets: List<HostTargetSpec>,
        forceRescan: Boolean,
        onComplete: (PrewarmResolutionResult) -> Unit,
    ) {
        val unavailable = DexKitAvailability.unavailableReason(context, processName)
        if (unavailable != null) {
            onComplete(PrewarmResolutionResult(0, 0, emptySet(), emptySet(), unavailable))
            return
        }
        DexKitEngine.submitBatch(context, classLoader) { generation, scanner ->
            var hits = 0
            var failures = 0
            val newlyResolvedOwners = linkedSetOf<com.xiyunmn.qdhelper.foundation.identity.OwnerId>()
            val newlyResolvedTargetIds = linkedSetOf<com.xiyunmn.qdhelper.host.api.target.TargetId>()
            targets.forEach { spec ->
                if (!DexKitEngine.isCurrent(generation)) return@submitBatch
                val before = cachedCount(spec)
                val resolution = resolveSpec(spec, forceRescan, scanner, generation)
                if (resolution.isResolved()) {
                    hits += 1
                    if (before == 0 && spec.timing.publishesCurrentProcessRetry()) {
                        newlyResolvedOwners += spec.owner
                        newlyResolvedTargetIds += spec.key.id
                    }
                } else {
                    failures += 1
                    val reason = when (resolution) {
                        is Resolution.Unavailable -> resolution.reason
                        is Resolution.Ambiguous -> "ambiguous:${resolution.candidateCount}:${resolution.reason}"
                        else -> "contract_mismatch"
                    }
                    ResolverEnvironment.warn("Target prewarm unavailable: target=${spec.key.id.value}, revision=${spec.revision}, reason=$reason")
                }
            }
            if (DexKitEngine.isCurrent(generation)) {
                onComplete(
                    PrewarmResolutionResult(
                        hits,
                        failures,
                        newlyResolvedOwners,
                        newlyResolvedTargetIds,
                        null,
                    ),
                )
            }
        }
    }

    fun clearLegacyCacheEntries(): Int? = cache.clearLegacySettingsEntries()

    fun readFlag(name: String): Boolean = fingerprint?.let { cache.readFlag(name, it) } ?: false

    fun writeFlag(name: String, value: Boolean): Boolean =
        fingerprint?.let { cache.writeFlag(name, it, value) } ?: false

    override fun close() {
        memory.clear()
        DexKitEngine.close()
    }

    private fun resolveSpec(
        spec: HostTargetSpec,
        forceRescan: Boolean,
        batchScanner: ((HostTargetSpec) -> DexKitScanResult)? = null,
        batchGeneration: Long? = null,
    ): Resolution<Method> {
        val activeFingerprint = cacheFingerprint
        val key = activeFingerprint?.let { memoryKey(spec, it) }
        if (forceRescan) {
            key?.let(memory::remove)
            activeFingerprint?.let { cache.delete(spec, it) }
        } else {
            key?.let { memoryKey ->
                memory[memoryKey]?.takeIf { valid(spec, it) }?.let { methods ->
                    return buildResolution(spec, methods, Resolution.Source.MEMORY)
                }
            }
            if (activeFingerprint != null) {
                if (cache.isNegative(spec, activeFingerprint)) {
                    return Resolution.Unavailable("negative_cache:${spec.key.id.value}", true)
                }
                cache.read(spec, activeFingerprint)?.let { serialized ->
                    val methods = materialize(serialized, spec)
                    if (methods != null) {
                        key?.let { memory[it] = methods }
                        return buildResolution(spec, methods, Resolution.Source.CACHE)
                    }
                    cache.delete(spec, activeFingerprint)
                }
            }
        }

        if (!spec.timing.allowsSynchronousScan() && batchScanner == null) {
            return Resolution.Unavailable("deferred_to_next_start:${spec.key.id.value}", false)
        }
        DexKitAvailability.unavailableReason(context, processName)?.let { reason ->
            return Resolution.Unavailable("dexkit_unavailable:$reason", false)
        }

        val scan = if (batchScanner != null) {
            batchScanner(spec)
        } else {
            DexKitEngine.scan(context, classLoader, spec)
        }
        if (scan is DexKitScanResult.Unavailable) {
            return Resolution.Unavailable(scan.reason, false)
        }
        scan as DexKitScanResult.Candidates
        if (!DexKitEngine.isCurrent(scan.generation) ||
            (batchGeneration != null && batchGeneration != scan.generation)
        ) {
            return Resolution.Unavailable("stale_generation:${spec.key.id.value}", false)
        }
        val methods = scan.result.methods
        when (scan.result.status) {
            CandidateStatus.UNRESOLVED -> {
                activeFingerprint?.let { cache.writeNegative(spec, it) }
                return Resolution.Unavailable("target_unresolved:${spec.key.id.value}", true)
            }
            CandidateStatus.VERIFIER_ERROR ->
                return Resolution.Unavailable("verifier_error:${spec.key.id.value}", false)
            CandidateStatus.AMBIGUOUS ->
                return Resolution.Ambiguous(methods.size, "cardinality_violation:${spec.key.id.value}")
            CandidateStatus.PASS -> Unit
        }
        key?.let { memory[it] = methods }
        activeFingerprint?.let { cache.write(spec, it, methods.map(DexMethodCodec::serialize)) }
        return buildResolution(spec, methods, Resolution.Source.DEXKIT)
    }

    private fun contractSpec(key: TargetKey<*>): HostTargetSpec? {
        if (key !is TargetKey.MethodTarget) return null
        return specs[key.id]?.takeIf { it.key.cardinality == key.cardinality }
    }

    private fun materialize(serialized: List<String>, spec: HostTargetSpec): List<Method>? {
        val methods = serialized.map { value ->
            DexMethodCodec.materialize(value, classLoader) ?: return null
        }
        val result = CandidatePipeline.evaluate(spec, methods)
        return result.methods.takeIf {
            result.status == CandidateStatus.PASS && result.verifiedCandidateCount == methods.size &&
                result.verifierErrorCount == 0
        }
    }

    private fun valid(spec: HostTargetSpec, methods: List<Method>): Boolean =
        CandidatePipeline.matchesCardinality(spec.cardinality, methods.size)

    private fun buildResolution(
        spec: HostTargetSpec,
        methods: List<Method>,
        source: Resolution.Source,
    ): Resolution<Method> = when (spec.cardinality) {
        Cardinality.One -> Resolution.Resolved(methods.single(), source)
        is Cardinality.Many -> Resolution.ResolvedMany(methods, source)
    }

    private fun memoryKey(spec: HostTargetSpec, activeFingerprint: String): String =
        "$activeFingerprint:${spec.key.id.value}:r${spec.revision}"

}

internal data class PrewarmResolutionResult(
    val hits: Int,
    val failures: Int,
    val newlyResolvedOwners: Set<com.xiyunmn.qdhelper.foundation.identity.OwnerId>,
    val newlyResolvedTargetIds: Set<com.xiyunmn.qdhelper.host.api.target.TargetId>,
    val unavailableReason: String?,
)

private fun Resolution<Method>.isResolved(): Boolean =
    this is Resolution.Resolved || this is Resolution.ResolvedMany

internal object ResolverSessionRegistry {
    private val lock = Any()
    private var active: ResolverSession? = null

    fun session(context: Context, classLoader: ClassLoader, processName: String): ResolverSession {
        val appContext = context.applicationContext ?: context
        synchronized(lock) {
            active?.takeIf { it.classLoader === classLoader && it.processName == processName }?.let { return it }
            active?.close()
            return ResolverSession(appContext, classLoader, processName).also { active = it }
        }
    }

    fun close() {
        synchronized(lock) {
            active?.close()
            active = null
        }
    }
}
