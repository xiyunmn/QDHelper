package com.xiyunmn.qdhelper.host.api.target

import android.content.Context
import java.lang.reflect.Method

interface MethodTargetResolver {
    fun resolveMethod(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
        forceRescan: Boolean,
    ): Method?

    /** Resolves one of the explicitly allow-listed pre-Context startup targets. */
    fun resolveEarlyMethod(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
    ): Method?

    fun resolveMethods(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
        forceRescan: Boolean,
    ): List<Method>
}

/** Feature-facing gateway for the process-scoped host contract resolver. */
object HostTargetResolver {
    @Volatile
    private var delegate: MethodTargetResolver? = null

    fun configure(value: MethodTargetResolver) {
        delegate = value
    }

    fun resolveMethod(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
        forceRescan: Boolean = false,
    ): Method? = delegate?.resolveMethod(context, classLoader, processName, key, forceRescan)

    fun resolveEarlyMethod(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
    ): Method? = delegate?.resolveEarlyMethod(context, classLoader, processName, key)

    fun resolveMethods(
        context: Context?,
        classLoader: ClassLoader,
        processName: String,
        key: TargetKey.MethodTarget,
        forceRescan: Boolean = false,
    ): List<Method> = delegate?.resolveMethods(context, classLoader, processName, key, forceRescan).orEmpty()
}
