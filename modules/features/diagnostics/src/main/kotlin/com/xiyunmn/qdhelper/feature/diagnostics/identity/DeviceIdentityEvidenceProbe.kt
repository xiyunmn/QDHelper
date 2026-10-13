package com.xiyunmn.qdhelper.feature.diagnostics.identity

import android.os.SystemClock
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.DiagnosticsTargetKeys
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** P0 evidence chain for identity construction, attribution, and getconf persistence. */
internal object DeviceIdentityEvidenceProbe {
    /** `common.lib.d` exposes exactly three public static String setters: qimei16, qimei36, OAID. */
    private const val EXPECTED_IDENTITY_SETTERS = 3

    private val traceSequence = AtomicLong()
    private val cleanupRegistered = AtomicBoolean(false)
    private val qdInfoTrace = ThreadLocal<String?>()
    private val attributionTrace = ThreadLocal<String?>()

    fun install(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        ensureCleanup()
        var installed = 0
        installed += hookIdentitySetters(module, classLoader, phase)
        installed += hookQdInfo(module, classLoader, phase)
        installed += hookQdInfoEncryption(module, classLoader, phase)
        installed += hookAttribution(module, classLoader, phase)
        installed += OStarFingerprintProbe.install(module, classLoader, phase)
        installed += DeviceConfigEvidenceProbe.install(module, classLoader, phase)
        FeatureHooks.i("Device identity P0 evidence finished: phase=$phase, installedHooks=$installed")
        return installed
    }

    fun configContextTag(): String = DeviceConfigEvidenceProbe.configContextTag()

    private fun ensureCleanup() {
        if (!cleanupRegistered.compareAndSet(false, true)) return
        FeatureResources.register("DeviceIdentityEvidenceProbe") {
            qdInfoTrace.remove()
            attributionTrace.remove()
            cleanupRegistered.set(false)
        }
    }

    private fun hookIdentitySetters(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(HostSymbols.Classes.COMMON_LIB_DEVICE, classLoader) ?: return 0
        val all = resolveMany(
            classLoader,
            DiagnosticsTargetKeys.commonDeviceIdentitySetters,
        )
        val q16 = resolveOne(
            classLoader,
            DiagnosticsTargetKeys.commonDeviceOstar16Setter,
        )
        val q36 = resolveOne(
            classLoader,
            DiagnosticsTargetKeys.commonDeviceOstar36Setter,
        )
        if (all.isEmpty()) {
            FeatureHooks.w("Device evidence: common device setters unresolved, phase=$phase")
            return 0
        }

        val q16Signature = q16?.toGenericString()
        val q36Signature = q36?.toGenericString()
        // The OAID setter body is a bare field assignment (`S = str`) with no log anchor, so it
        // can only be identified by elimination. That inference is sound *only* when both anchored
        // setters resolved and the host really exposes exactly the three known setters; otherwise
        // an unresolved q16/q36 would silently be relabelled as OAID and corrupt the evidence.
        val canInferOaid = q16Signature != null && q36Signature != null && all.size == EXPECTED_IDENTITY_SETTERS
        if (!canInferOaid) {
            FeatureHooks.w(
                "Device evidence: identity setter labels degraded, phase=$phase, " +
                    "setters=${all.size}/$EXPECTED_IDENTITY_SETTERS, q16=${q16 != null}, q36=${q36 != null}",
            )
        }
        var installed = 0
        all.forEach { method ->
            val label = when (method.toGenericString()) {
                q16Signature -> "id.ostar16_set"
                q36Signature -> "id.ostar36_set"
                else -> if (canInferOaid) "id.oaid_set" else "id.unknown_set"
            }
            val ok = FeatureHooks.hookAround(
                module = module,
                executable = method,
                feature = "probe.device.$label",
                id = "probe.device.$label:${method.toGenericString()}",
            ) { chain ->
                DeviceIdentityProbeFeature.logHit(
                    label,
                    "value=${IdentityEvidenceCodec.fingerprint(chain.args.firstOrNull() as? String)}," +
                        "elapsed=${SystemClock.elapsedRealtime()}",
                )
                chain.proceed()
            }
            if (ok) installed++
        }
        return installed
    }

    private fun hookQdInfo(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(HostSymbols.Classes.COMMON_LIB_DEVICE, classLoader) ?: return 0
        val methods = resolveMany(classLoader, DiagnosticsTargetKeys.deviceQdInfoCandidates)
        if (methods.isEmpty()) {
            FeatureHooks.w("Device evidence: QDInfo candidates unresolved, phase=$phase")
            return 0
        }
        return methods.count { method ->
            FeatureHooks.hookAround(
                module = module,
                executable = method,
                feature = "probe.device.qdinfo",
                id = "probe.device.qdinfo:${method.toGenericString()}",
            ) { chain ->
                val previous = qdInfoTrace.get()
                val trace = previous ?: nextTrace("qdi")
                if (previous == null) qdInfoTrace.set(trace)
                try {
                    val result = chain.proceed() as? String
                    val summary = IdentityEvidenceCodec.parseQdInfo(result, chain.args.getOrNull(1) as? String)
                    if (summary != null) {
                        DeviceIdentityProbeFeature.logHit(
                            "evidence.qdinfo_plain",
                            "trace=$trace,method=${method.name},${summary.asLogString()}," +
                                "elapsed=${SystemClock.elapsedRealtime()}",
                        )
                    } else {
                        DeviceIdentityProbeFeature.logHit(
                            "evidence.qdinfo_encrypt",
                            "trace=$trace,source=common_device_candidate,method=${method.name}," +
                                "ret=${IdentityEvidenceCodec.fingerprint(result)}," +
                                "elapsed=${SystemClock.elapsedRealtime()}",
                        )
                    }
                    result
                } finally {
                    if (previous == null) qdInfoTrace.remove() else qdInfoTrace.set(previous)
                }
            }
        }
    }

    private fun hookQdInfoEncryption(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(HostSymbols.Classes.FOCK_UTIL, classLoader) ?: return 0
        val methods = FeatureHooks.declaredMethodsNamed(clazz, "getQDInfoEncrypt").filter { method ->
            method.returnType == String::class.java &&
                method.parameterTypes.map { it.name } in listOf(
                    emptyList(), listOf("boolean"),
                    listOf("long", "java.lang.String"), listOf("long", "java.lang.String", "boolean"),
                )
        }
        if (methods.isEmpty()) {
            FeatureHooks.w("Device evidence: FockUtil.getQDInfoEncrypt missing, phase=$phase")
            return 0
        }
        return methods.count { method ->
            FeatureHooks.hookAround(
                module = module,
                executable = method,
                feature = "probe.device.qdinfo_encrypt",
                id = "probe.device.qdinfo_encrypt:${method.toGenericString()}",
            ) { chain ->
                val previous = qdInfoTrace.get()
                val trace = previous ?: nextTrace("qdi")
                // set() must be inside the try: if anything below throws before entering it, the
                // finally block never runs and the trace leaks into the next call on this thread.
                try {
                    qdInfoTrace.set(trace)
                    val args = if (chain.args.size <= 1) {
                        "default"
                    } else {
                        "tsLen=${chain.args.getOrNull(0)?.toString()?.length ?: 0}," +
                            "qimei=${IdentityEvidenceCodec.fingerprint(chain.args.getOrNull(1) as? String)}"
                    }
                    DeviceIdentityProbeFeature.logHit(
                        "evidence.qdinfo_encrypt",
                        "trace=$trace,begin,args=[$args],elapsed=${SystemClock.elapsedRealtime()}",
                    )
                    val result = chain.proceed() as? String
                    DeviceIdentityProbeFeature.logHit(
                        "evidence.qdinfo_encrypt",
                        "trace=$trace,end,ret=${IdentityEvidenceCodec.fingerprint(result)}," +
                            "elapsed=${SystemClock.elapsedRealtime()}",
                    )
                    result
                } finally {
                    if (previous == null) qdInfoTrace.remove() else qdInfoTrace.set(previous)
                }
            }
        }
    }

    private fun hookAttribution(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(HostSymbols.Classes.ATTRIBUTION_PARAMS_BUILDER, classLoader)
            ?: return 0
        val buildMethods = FeatureHooks.allDeclaredMethods(clazz).filter { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name.startsWith("build") &&
                method.returnType != Void.TYPE
        }
        val recordClass = buildMethods.map(Method::getReturnType)
            .filterNot { it == Any::class.java }
            .distinct()
            .singleOrNull()
            ?: FeatureHooks.nestedClasses(clazz).singleOrNull { nested ->
                FeatureHooks.findConstructorOrNull(nested, *Array(6) { String::class.java }) != null
            }
        var installed = 0
        recordClass?.let { type ->
            val constructor = FeatureHooks.findConstructorOrNull(type, *Array(6) { String::class.java })
            if (constructor != null) {
                val ok = FeatureHooks.hookAround(
                    module = module,
                    executable = constructor,
                    feature = "probe.device.attribution_record",
                    id = "probe.device.attribution_record:${constructor.toGenericString()}",
                ) { chain ->
                    val trace = attributionTrace.get() ?: nextTrace("att")
                    val args = chain.args.map { it as? String }
                    DeviceIdentityProbeFeature.logHit(
                        "evidence.attribution",
                        "trace=$trace,installType=${IdentityEvidenceCodec.safeFlag(args.getOrNull(0))}," +
                            "net=[${IdentityEvidenceCodec.fingerprint(args.getOrNull(1))}]," +
                            "q36Change=${IdentityEvidenceCodec.safeFlag(args.getOrNull(2))}," +
                            "oaidChange=${IdentityEvidenceCodec.safeFlag(args.getOrNull(3))}," +
                            "session=[${IdentityEvidenceCodec.fingerprint(args.getOrNull(4))}]," +
                            "uuid=[${IdentityEvidenceCodec.fingerprint(args.getOrNull(5))}]," +
                            "elapsed=${SystemClock.elapsedRealtime()}",
                    )
                    chain.proceed()
                }
                if (ok) installed++
            }
        }
        buildMethods.forEach { method ->
            val ok = FeatureHooks.hookAround(
                module = module,
                executable = method,
                feature = "probe.device.attribution_build",
                id = "probe.device.attribution_build:${method.toGenericString()}",
            ) { chain ->
                val previous = attributionTrace.get()
                val trace = previous ?: nextTrace("att")
                attributionTrace.set(trace)
                DeviceIdentityProbeFeature.logHit(
                    "evidence.attribution_build",
                    "trace=$trace,begin,method=${method.name},args=${summarizeSimpleArgs(chain.args)}," +
                        "elapsed=${SystemClock.elapsedRealtime()}",
                )
                try {
                    val result = chain.proceed()
                    DeviceIdentityProbeFeature.logHit(
                        "evidence.attribution_build",
                        "trace=$trace,end,result=${result?.javaClass?.name ?: "null"}," +
                            "elapsed=${SystemClock.elapsedRealtime()}",
                    )
                    result
                } finally {
                    if (previous == null) attributionTrace.remove() else attributionTrace.set(previous)
                }
            }
            if (ok) installed++
        }
        if (installed == 0) FeatureHooks.w("Device evidence: Attribution hooks missing, phase=$phase")
        return installed
    }

    private fun resolveOne(
        classLoader: ClassLoader,
        key: TargetKey.MethodTarget,
    ): Method? = HostTargetResolver.resolveMethod(
        context = HostSessionStore.contextOrNull(),
        classLoader = classLoader,
        processName = HostSessionStore.processName,
        key = key,
    )

    private fun resolveMany(
        classLoader: ClassLoader,
        key: TargetKey.MethodTarget,
    ): List<Method> = HostTargetResolver.resolveMethods(
        context = HostSessionStore.contextOrNull(),
        classLoader = classLoader,
        processName = HostSessionStore.processName,
        key = key,
    )

    private fun summarizeSimpleArgs(args: List<Any?>): String = args.mapIndexed { index, value ->
        val summary = when (value) {
            null -> "null"
            is Boolean, is Number -> value.toString()
            is String -> IdentityEvidenceCodec.fingerprint(value)
            else -> value.javaClass.simpleName
        }
        "a$index=$summary"
    }.joinToString(prefix = "[", postfix = "]")

    private fun nextTrace(prefix: String): String = "$prefix-${traceSequence.incrementAndGet()}"
}
