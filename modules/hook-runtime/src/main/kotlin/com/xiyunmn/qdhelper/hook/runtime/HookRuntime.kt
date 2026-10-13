package com.xiyunmn.qdhelper.hook.runtime

import com.xiyunmn.qdhelper.foundation.logging.LogEvent
import com.xiyunmn.qdhelper.foundation.logging.LogLevel
import com.xiyunmn.qdhelper.foundation.logging.LogSink
import com.xiyunmn.qdhelper.foundation.logging.ModuleLogger
import com.xiyunmn.qdhelper.foundation.logging.RuntimeLog
import com.xiyunmn.qdhelper.foundation.logging.SafeLog
import com.xiyunmn.qdhelper.hook.adapter.LibXposedHookInstaller
import com.xiyunmn.qdhelper.hook.logging.LibXposedRuntimeLogger
import com.xiyunmn.qdhelper.hook.registry.RuntimeHookRegistry
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/** The single libxposed Hook installation and logging boundary. */
object HookRuntime : ModuleLogger {
    @Volatile
    private var fileLogSink: LogSink? = null

    fun configureFileLogSink(sink: LogSink?) {
        fileLogSink = sink
    }

    fun attach(module: XposedModule) {
        LibXposedRuntimeLogger.attach(module)
        RuntimeLog.configure(this)
    }

    fun detach(module: XposedModule) {
        LibXposedRuntimeLogger.detach(module)
        RuntimeLog.clear(this)
    }

    override fun info(message: String) = emit(LogLevel.INFO, message, null)
    override fun warn(message: String) = emit(LogLevel.WARN, message, null)
    override fun error(message: String, throwable: Throwable?) = emit(LogLevel.ERROR, message, throwable)

    fun i(message: String) = info(message)
    fun w(message: String) = warn(message)
    fun e(message: String, throwable: Throwable? = null) = error(message, throwable)

    fun <T> withFrameworkLogSuppressed(block: () -> T): T =
        LibXposedRuntimeLogger.withFrameworkLogSuppressed(block)

    fun hookAfter(
        module: XposedModule,
        executable: Executable?,
        feature: String,
        id: String? = feature,
        after: (XposedInterface.Chain, Any?) -> Unit,
    ): Boolean = interceptProtective(module, executable, feature, id) { chain ->
        val result = chain.proceed()
        runCatching { after(chain, result) }
            .onFailure { throwable -> e("After-hook callback failed: $feature", throwable) }
        result
    }

    fun hookAround(
        module: XposedModule,
        executable: Executable?,
        feature: String,
        id: String? = feature,
        around: (XposedInterface.Chain) -> Any?,
    ): Boolean = interceptProtective(module, executable, feature, id, around)

    fun prepareHotReloadReplacement(oldHandles: List<XposedInterface.HookHandle>) {
        RuntimeHookRegistry.prepareHotReloadReplacement(oldHandles.map(LibXposedHookInstaller::wrap))
    }

    fun drainStaleHotReloadHandles(): List<XposedInterface.HookHandle> =
        RuntimeHookRegistry.drainStaleHotReloadHandles().map(LibXposedHookInstaller::unwrap)

    fun registrySize(): Int = RuntimeHookRegistry.size()

    fun clearRegistry() = RuntimeHookRegistry.clearLocal()

    private fun interceptProtective(
        module: XposedModule,
        executable: Executable?,
        feature: String,
        id: String?,
        interceptor: (XposedInterface.Chain) -> Any?,
    ): Boolean {
        if (executable == null) {
            w("Skip missing executable: $feature")
            return false
        }
        return runCatching {
            val hooker = XposedInterface.Hooker(interceptor)
            if (id != null && RuntimeHookRegistry.activeHandle(id, executable) != null) {
                i("Hook already installed, skip duplicate: $feature, id=$id")
                return true
            }
            val handle = if (id != null) {
                val oldHandle = RuntimeHookRegistry.pendingHotReloadHandle(id, executable)
                if (oldHandle != null) {
                    replaceHotReloadHook(
                        LibXposedHookInstaller.unwrap(oldHandle),
                        module,
                        executable,
                        feature,
                        id,
                        hooker,
                    )
                } else {
                    installNewHook(module, executable, id, hooker)
                }
            } else {
                installNewHook(module, executable, null, hooker)
            }
            if (id != null) {
                when (RuntimeHookRegistry.register(id, executable, LibXposedHookInstaller.wrap(handle))) {
                    RuntimeHookRegistry.Registration.New ->
                        i("Hook installed: $feature, registrySize=${RuntimeHookRegistry.size()}")
                    RuntimeHookRegistry.Registration.Duplicate -> {
                        handle.unhook()
                        w("Hook duplicate released: $feature, id=$id, registrySize=${RuntimeHookRegistry.size()}")
                    }
                }
            } else {
                i("Hook installed: $feature, registrySize=${RuntimeHookRegistry.size()}")
            }
            true
        }.getOrElse { throwable ->
            e("Hook failed: $feature", throwable)
            false
        }
    }

    private fun replaceHotReloadHook(
        oldHandle: XposedInterface.HookHandle,
        module: XposedModule,
        executable: Executable,
        feature: String,
        id: String,
        hooker: XposedInterface.Hooker,
    ): XposedInterface.HookHandle = runCatching {
        installNewHook(module, executable, id, hooker).also {
            RuntimeHookRegistry.markHotReloadReplaced(id, executable)
            i("Hook hot-replaced: $feature, id=$id")
        }
    }.getOrElse { throwable ->
        w("Hook hot-replace by id failed: $feature, id=$id, error=${throwable.javaClass.name}")
        runCatching(oldHandle::unhook).onFailure { unhookError ->
            w("Hook hot-replace old unhook failed: $feature, id=$id, error=${unhookError.javaClass.name}")
        }
        installNewHook(module, executable, id, hooker).also {
            RuntimeHookRegistry.markHotReloadReplaced(id, executable)
        }
    }

    private fun installNewHook(
        module: XposedModule,
        executable: Executable,
        id: String?,
        hooker: XposedInterface.Hooker,
    ): XposedInterface.HookHandle = LibXposedHookInstaller.install(module, executable, id, hooker)

    private fun emit(level: LogLevel, message: String, throwable: Throwable?) {
        val safeMessage = SafeLog.sanitize(message)
        when (level) {
            LogLevel.INFO -> LibXposedRuntimeLogger.info(safeMessage)
            LogLevel.WARN -> LibXposedRuntimeLogger.warn(safeMessage)
            LogLevel.ERROR -> LibXposedRuntimeLogger.error(safeMessage, throwable)
        }
        val sinkMessage = if (throwable == null) safeMessage else
            "$safeMessage | ${throwable.javaClass.name}: ${SafeLog.sanitize(throwable.message.orEmpty())}"
        fileLogSink?.emit(LogEvent(level, sinkMessage, throwable))
    }
}
