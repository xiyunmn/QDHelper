package com.xiyunmn.qdhelper.hook.adapter

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import com.xiyunmn.qdhelper.hook.registry.RuntimeHookHandle
import java.lang.reflect.Executable

class LibXposedHookHandle(val delegate: XposedInterface.HookHandle) : RuntimeHookHandle {
    override val id: String? get() = delegate.id
    override val executable: Executable get() = delegate.executable
    override fun unhook() = delegate.unhook()
}

object LibXposedHookInstaller {
    fun wrap(handle: XposedInterface.HookHandle): RuntimeHookHandle = LibXposedHookHandle(handle)

    fun unwrap(handle: RuntimeHookHandle): XposedInterface.HookHandle =
        requireNotNull((handle as? LibXposedHookHandle)?.delegate) { "Hook handle was not created by libxposed adapter" }

    fun install(
        module: XposedModule,
        executable: Executable,
        id: String?,
        hooker: XposedInterface.Hooker,
    ): XposedInterface.HookHandle {
        val builder = module.hook(executable)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        if (id != null) {
            builder.setId(id)
        }
        return builder.intercept(hooker)
    }
}
