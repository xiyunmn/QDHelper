package com.xiyunmn.qdhelper.feature.diagnostics.probe

import com.xiyunmn.qdhelper.feature.diagnostics.target.HookTargets
import com.xiyunmn.qdhelper.feature.diagnostics.target.resolveMethods
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object TargetDiscoveryFeature : InstallableFeature {
    override val name: String = "TargetDiscoveryFeature"

    private val reported = ConcurrentHashMap.newKeySet<String>()

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        var foundMethods = 0
        HookTargets.readOnlyDiscovery.forEach { spec ->
            val classKey = "${phase}:${spec.className}"
            val clazz = FeatureHooks.findClassOrNull(spec.className, classLoader)
            if (clazz == null) {
                if (reported.add("$classKey:missing")) {
                    FeatureHooks.w("Discovery missing class: ${spec.className} (${spec.id}, phase=$phase)")
                }
                return@forEach
            }

            val methods = spec.resolveMethods(clazz, classLoader, processName)
            if (methods.isEmpty()) {
                if (reported.add("${phase}:${spec.id}:missingMethod")) {
                    FeatureHooks.w(
                        "Discovery missing method: ${spec.className}.${spec.methodNames.joinToString("/")} " +
                            "(${spec.purpose}, phase=$phase)",
                    )
                }
                return@forEach
            }

            foundMethods += methods.size
            val signatures = methods.joinToString(separator = "; ") { method ->
                method.safeSignature()
            }
            if (reported.add("${phase}:${spec.id}:found")) {
                FeatureHooks.i(
                    "Discovery found ${spec.id}: ${spec.className}.${methods.joinToString("/") { it.name }} " +
                        "count=${methods.size}, priority=${spec.priority}, callProbe=${spec.callProbe}, " +
                        "signatures=$signatures, phase=$phase",
                )
            }
        }
        FeatureHooks.i("Discovery phase finished: phase=$phase, process=$processName, foundMethods=$foundMethods")
    }

    private fun Method.safeSignature(): String {
        val visibility = when {
            Modifier.isPublic(modifiers) -> "public"
            Modifier.isProtected(modifiers) -> "protected"
            Modifier.isPrivate(modifiers) -> "private"
            else -> "package"
        }
        val native = if (Modifier.isNative(modifiers)) " native" else ""
        val static = if (Modifier.isStatic(modifiers)) " static" else ""
        val params = parameterTypes.joinToString(",") { type -> type.name }
        return "$visibility$static$native ${returnType.name} $name($params)"
    }
}
