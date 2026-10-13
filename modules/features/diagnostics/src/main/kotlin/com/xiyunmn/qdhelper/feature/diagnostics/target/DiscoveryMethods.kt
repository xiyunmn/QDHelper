package com.xiyunmn.qdhelper.feature.diagnostics.target

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import java.lang.reflect.Method

internal fun HookTargetSpec.resolveMethods(owner: Class<*>, loader: ClassLoader, process: String): List<Method> {
    targetKey?.let { key ->
        return HostTargetResolver.resolveMethods(HostSessionStore.contextOrNull(), loader, process, key)
    }
    val candidates = if (publicStaticParameters == null) methodNames.flatMap { FeatureHooks.declaredMethodsNamed(owner, it) }
        else FeatureHooks.allDeclaredMethods(owner)
    val matches = candidates.filter(::matches)
    // A structural exploratory probe must stay unique, otherwise leave the host untouched.
    if (publicStaticParameters != null && matches.size > 1) {
        FeatureHooks.w("Discovery ambiguous contract: id=$id, candidates=${matches.size}")
        return emptyList()
    }
    return matches
}
