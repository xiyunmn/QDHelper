package com.xiyunmn.qdhelper.feature.diagnostics.identity

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Read-only probe for the OStar/Beacon device fingerprint payloads.
 *
 * `com.ola.star.*` is a vendored third-party SDK, not host business code: its class and
 * method names are byte-identical across the active audit interval, so no DexKit rule applies
 * (see the maintenance handbook §7.1 — stable public SDKs are out of DexKit scope).
 * Shapes are still verified at runtime instead of trusting names alone.
 *
 * Payloads carry raw device identifiers and are therefore only ever logged through
 * [JsonFingerprintSummary] / [IdentityEvidenceCodec]; raw values must never be emitted.
 */
internal object OStarFingerprintProbe {
    /**
     * `aj.f#a(w.c, af.a, al.a)` builds the inner device-attribute JSON and
     * `aj.f#a(String)` builds the outer numbered-slot JSON that embeds it as slot `12`.
     * Both are public instance methods returning String; the native accessors on `uin.U`
     * are deliberately excluded (see [isSafeFingerprintSource]).
     */
    fun install(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val clazz = FeatureHooks.findClassOrNull(HostSymbols.Classes.OSTAR_FINGERPRINT_BUILDER, classLoader)
        if (clazz == null) {
            FeatureHooks.w("OStar fingerprint probe: builder class missing, phase=$phase")
            return 0
        }
        val targets = FeatureHooks.allDeclaredMethods(clazz).filter(::isSafeFingerprintSource)
        if (targets.isEmpty()) {
            FeatureHooks.w("OStar fingerprint probe: no builder method matched the expected shape, phase=$phase")
            return 0
        }
        val installed = targets.count { method ->
            FeatureHooks.hookAround(
                module = module,
                executable = method,
                feature = "probe.device.ostar_fingerprint",
                id = "probe.device.ostar_fingerprint:${method.toGenericString()}",
            ) { chain ->
                val result = chain.proceed()
                (result as? String)?.takeIf(String::isNotEmpty)?.let { payload ->
                    DeviceIdentityProbeFeature.logHit(
                        "evidence.ostar_fingerprint",
                        "arity=${method.parameterCount},${JsonFingerprintSummary.summarize(payload)}",
                    )
                }
                result
            }
        }
        FeatureHooks.i("OStar fingerprint probe installed: $installed/${targets.size}, phase=$phase")
        return installed
    }

    /**
     * Accepts only public, non-static, non-native, non-synthetic String builders.
     *
     * Excluding native is not cosmetic: `com.ola.star.uin.U` reaches the fingerprint through
     * private `static native` accessors, and hooking a JNI method under the host's hardening
     * is both unreliable and out of scope for a read-only probe.
     */
    internal fun isSafeFingerprintSource(method: Method): Boolean =
        method.returnType == String::class.java &&
            method.parameterCount > 0 &&
            Modifier.isPublic(method.modifiers) &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isNative(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.isSynthetic &&
            !method.isBridge
}
