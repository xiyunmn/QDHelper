package com.xiyunmn.qdhelper.host.resolver.runtime

import android.content.Context
import com.xiyunmn.qdhelper.host.api.target.HostContractResolver
import com.xiyunmn.qdhelper.host.api.target.Resolution
import com.xiyunmn.qdhelper.host.api.target.TargetKey

/** Production resolver backed by the process-scoped resolver session. */
internal class ResolverRuntime(
    context: Context,
    classLoader: ClassLoader,
    processName: String,
) : HostContractResolver {
    private val delegate = ResolverSessionRegistry.session(context, classLoader, processName)

    override fun <T : Any> resolve(key: TargetKey<T>, forceRescan: Boolean): Resolution<T> =
        delegate.resolve(key, forceRescan)
}
