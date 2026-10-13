package com.xiyunmn.qdhelper.feature.diagnostics.probe

import com.xiyunmn.qdhelper.feature.diagnostics.target.HookTargets
import com.xiyunmn.qdhelper.feature.diagnostics.target.HookTargetSpec
import com.xiyunmn.qdhelper.feature.diagnostics.target.resolveMethods
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object TargetCallProbeFeature : InstallableFeature {
    override val name: String = "TargetCallProbeFeature"

    private const val FIRST_VERBOSE_HITS = 20L
    private const val SAMPLE_INTERVAL = 50L

    private val hitCounters = ConcurrentHashMap<String, AtomicLong>()

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        var installedMethods = 0
        HookTargets.p1Discovery.filter { spec -> spec.callProbe }.forEach { spec ->
            val clazz = FeatureHooks.findClassOrNull(spec.className, classLoader) ?: return@forEach
            val methods = spec.resolveMethods(clazz, classLoader, processName)
            methods.forEach { method ->
                if (installProbe(module, spec, method, phase)) {
                    installedMethods += 1
                }
            }
        }
        FeatureHooks.i("Call probe phase finished: phase=$phase, process=$processName, installedMethods=$installedMethods")
    }

    private fun installProbe(
        module: HookModule,
        spec: HookTargetSpec,
        method: Method,
        phase: String,
    ): Boolean {
        val installKey = "${spec.id}:${method.toGenericString()}"
        val hookId = "probe.hit.${spec.id}.${method.shortHash()}"
        return FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "probe.hit.${spec.id}:${method.ownerLabel()}.${method.name}",
            id = hookId,
        ) { chain ->
            if (!com.xiyunmn.qdhelper.config.SettingsRuntime.isCallProbeEnabled()) return@hookAround chain.proceed()
            val count = hitCounters.computeIfAbsent(installKey) { AtomicLong(0L) }.incrementAndGet()
            val verbose = shouldLogHit(count)
            val receiver = chain.thisObject?.javaClass?.name?.let(QDReaderHost::classLabel) ?: "<static>"
            val argTypes = chain.args.typeSummary()
            val callContext = "receiver=$receiver, thread=${Thread.currentThread().name}, args=$argTypes"
            val startNanos = System.nanoTime()

            if (verbose) {
                FeatureHooks.i(
                    "Probe hit begin ${spec.id}#$count: $callContext",
                )
            }

            try {
                val result = chain.proceed()
                if (verbose) {
                    FeatureHooks.i(
                        "Probe hit end ${spec.id}#$count: result=${method.resultTypeLabel(result)}, " +
                            "elapsedMs=${elapsedMs(startNanos)}, $callContext",
                    )
                }
                result
            } catch (throwable: Throwable) {
                FeatureHooks.w(
                    "Probe hit throw ${spec.id}#$count: throwable=${throwable.javaClass.name}, " +
                        "elapsedMs=${elapsedMs(startNanos)}, $callContext",
                )
                throw throwable
            }
        }.also { success ->
            if (success) {
                FeatureHooks.i(
                    "Call probe installed ${spec.id}: ${method.ownerLabel()}.${method.name}, phase=$phase",
                )
            }
        }
    }

    private fun shouldLogHit(count: Long): Boolean {
        return count <= FIRST_VERBOSE_HITS || count % SAMPLE_INTERVAL == 0L
    }

    private fun elapsedMs(startNanos: Long): Long {
        return (System.nanoTime() - startNanos) / 1_000_000L
    }

    private fun Method.ownerLabel(): String {
        return QDReaderHost.classLabel(declaringClass.name)
    }

    private fun Method.resultTypeLabel(result: Any?): String {
        return if (returnType == Void.TYPE) {
            "void"
        } else {
            result?.javaClass?.name ?: "null"
        }
    }

    private fun Method.shortHash(): String {
        return Integer.toHexString(toGenericString().hashCode())
    }

    private fun List<Any?>.typeSummary(): String {
        if (isEmpty()) {
            return "[]"
        }
        return joinToString(prefix = "[", postfix = "]") { value ->
            value?.javaClass?.name ?: "null"
        }
    }
}
