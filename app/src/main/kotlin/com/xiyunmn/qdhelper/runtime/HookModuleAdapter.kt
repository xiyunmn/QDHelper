package com.xiyunmn.qdhelper.runtime

import com.xiyunmn.qdhelper.hook.runtime.HookRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookChain
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

internal class HookModuleAdapter(
    internal val delegate: XposedModule,
) : HookModule {
    override fun hookAfter(
        executable: Executable?,
        feature: String,
        id: String?,
        callback: (HookChain, Any?) -> Unit,
    ): Boolean = HookRuntime.hookAfter(delegate, executable, feature, id) { chain, result ->
        callback(ChainAdapter(chain), result)
    }

    override fun hookAround(
        executable: Executable?,
        feature: String,
        id: String?,
        callback: (HookChain) -> Any?,
    ): Boolean = HookRuntime.hookAround(delegate, executable, feature, id) { chain ->
        callback(ChainAdapter(chain))
    }

    private class ChainAdapter(private val delegate: XposedInterface.Chain) : HookChain {
        override val thisObject: Any? get() = delegate.thisObject
        override val args: List<Any?> get() = delegate.args
        override fun proceed(arguments: Array<Any?>?): Any? =
            if (arguments == null) delegate.proceed() else delegate.proceed(arguments)
    }
}
