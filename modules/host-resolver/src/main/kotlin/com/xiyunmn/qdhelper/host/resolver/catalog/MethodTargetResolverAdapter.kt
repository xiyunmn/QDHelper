package com.xiyunmn.qdhelper.host.resolver.catalog

import android.content.Context
import com.xiyunmn.qdhelper.host.api.target.Cardinality
import com.xiyunmn.qdhelper.host.api.target.HostContractResolver
import com.xiyunmn.qdhelper.host.api.target.MethodTargetResolver
import com.xiyunmn.qdhelper.host.api.target.Resolution
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.resolver.dexkit.NativeAbiSupport
import com.xiyunmn.qdhelper.host.resolver.environment.ResolverEnvironment
import com.xiyunmn.qdhelper.host.resolver.runtime.ResolverRuntime
import java.lang.reflect.Method
import java.util.LinkedHashMap

/** Adapts feature-facing method requests to the canonical process resolver. */
class MethodTargetResolverAdapter(
    private val resolverFactory: (Context, ClassLoader, String) -> HostContractResolver = ::ResolverRuntime,
    private val earlyCandidateProvider: (ClassLoader, TargetKey.MethodTarget) -> List<Method> =
        EarlyMethodCandidateRegistry::candidates,
) : MethodTargetResolver {
    @Volatile
    private var session: ResolverSession? = null

    override fun resolveMethod(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
        forceRescan: Boolean,
    ): Method? = resolve(context, classLoader, processName, key, forceRescan).singleOrNull(key.id.value)

    override fun resolveEarlyMethod(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
    ): Method? = when (val result = resolve(context, classLoader, processName, key, forceRescan = false)) {
        is Resolution.Resolved -> result.value
        is Resolution.ResolvedMany, is Resolution.Ambiguous -> null
        is Resolution.Unavailable -> if (result.allowsVerifiedEarlyResolution()) {
            verifiedEarly(classLoader, processName, key)
        } else {
            null
        }
    }

    override fun resolveMethods(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
        forceRescan: Boolean,
    ): List<Method> = resolve(context, classLoader, processName, key, forceRescan).manyOrEmpty()

    private fun resolve(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
        forceRescan: Boolean,
    ): Resolution<Method> {
        val spec = spec(processName, key)
            ?: return Resolution.Unavailable("target_not_registered:${key.id.value}", false)
        if (context == null) {
            val reason = if (spec.risk == TargetRisk.HIGH) {
                "context_missing_high_risk:${key.id.value}"
            } else {
                "context_missing:${key.id.value}"
            }
            return Resolution.Unavailable(reason, false)
        }
        val result = resolver(context.applicationContext ?: context, classLoader, processName)
            .resolve(spec.key, forceRescan)
        ResolverEnvironment.info(
            "Host resolver route: target=${key.id.value}, result=${result.javaClass.simpleName}",
        )
        return result
    }

    private fun verifiedEarly(
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
    ): Method? {
        val spec = spec(processName, key) ?: return null
        if (key.id !in EarlyMethodCandidateRegistry.targetIds || spec.cardinality != Cardinality.One) return null
        val unique = LinkedHashMap<String, Method>()
        val candidates = runCatching { earlyCandidateProvider(classLoader, key) }.getOrElse { return null }
        candidates
            .filter { method -> runCatching { spec.query.verify(method) }.getOrDefault(false) }
            .forEach { method -> unique.putIfAbsent(method.toGenericString(), method) }
        return unique.values.singleOrNull()
    }

    private fun spec(processName: String, key: TargetKey.MethodTarget): HostTargetSpec? =
        HostTargetCatalog.forProcess(processName).singleOrNull { it.key.id == key.id && it.key.cardinality == key.cardinality }

    private fun resolver(context: Context, classLoader: ClassLoader, processName: String): HostContractResolver {
        session?.takeIf { current ->
            current.context === context && current.classLoader === classLoader && current.processName == processName
        }?.let { return it.resolver }
        return synchronized(this) {
            session?.takeIf { current ->
                current.context === context && current.classLoader === classLoader && current.processName == processName
            }?.resolver ?: resolverFactory(context, classLoader, processName).also { created ->
                session = ResolverSession(context, classLoader, processName, created)
            }
        }
    }

    private fun Resolution<Method>.singleOrNull(targetId: String): Method? = when (this) {
        is Resolution.Resolved -> value
        is Resolution.ResolvedMany -> {
            ResolverEnvironment.warn(
                "Host resolver contract mismatch: target=$targetId, expected=single, actual=${values.size}",
            )
            null
        }
        is Resolution.Ambiguous, is Resolution.Unavailable -> null
    }

    private fun Resolution<Method>.manyOrEmpty(): List<Method> = when (this) {
        is Resolution.ResolvedMany -> values
        is Resolution.Resolved -> listOf(value)
        is Resolution.Ambiguous, is Resolution.Unavailable -> emptyList()
    }

    private data class ResolverSession(
        val context: Context,
        val classLoader: ClassLoader,
        val processName: String,
        val resolver: HostContractResolver,
    )
}

internal fun Resolution.Unavailable.allowsVerifiedEarlyResolution(): Boolean =
    !reason.startsWith("context_missing_high_risk:") &&
        !reason.startsWith("target_not_registered:") &&
        !reason.startsWith("target_contract_mismatch:") &&
        !reason.isNativeBoundaryFailure()

private fun String.isNativeBoundaryFailure(): Boolean {
    val unwrapped = removePrefix("dexkit_unavailable:")
    return unwrapped == NativeAbiSupport.UNSUPPORTED_ABI ||
        unwrapped == NativeAbiSupport.UNSUPPORTED_PROCESS_ABI ||
        unwrapped.startsWith(NativeAbiSupport.NATIVE_MISSING) ||
        unwrapped.startsWith(NativeAbiSupport.NATIVE_LOAD_FAILED)
}
