package com.xiyunmn.qdhelper.hook.registry

import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap

interface RuntimeHookHandle {
    val id: String?
    val executable: Executable
    fun unhook()
}

object RuntimeHookRegistry {
    private val handles = ConcurrentHashMap<HookSignature, RuntimeHookHandle>()
    private val pendingHotReloadHandles = ConcurrentHashMap<HookSignature, RuntimeHookHandle>()

    fun activeHandle(id: String, executable: Executable): RuntimeHookHandle? =
        handles[HookSignature.of(id, executable)]

    fun register(id: String, executable: Executable, handle: RuntimeHookHandle): Registration {
        val previous = handles.putIfAbsent(HookSignature.of(id, executable), handle)
        return if (previous == null) Registration.New else Registration.Duplicate
    }

    fun prepareHotReloadReplacement(oldHandles: List<RuntimeHookHandle>) {
        handles.clear()
        pendingHotReloadHandles.clear()
        oldHandles.forEach { handle ->
            val id = runCatching { handle.id }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
            val executable = runCatching { handle.executable }.getOrNull() ?: return@forEach
            pendingHotReloadHandles[HookSignature.of(id, executable)] = handle
        }
    }

    fun pendingHotReloadHandle(id: String, executable: Executable): RuntimeHookHandle? =
        pendingHotReloadHandles[HookSignature.of(id, executable)]

    fun markHotReloadReplaced(id: String, executable: Executable) {
        pendingHotReloadHandles.remove(HookSignature.of(id, executable))
    }

    fun drainStaleHotReloadHandles(): List<RuntimeHookHandle> {
        val stale = pendingHotReloadHandles.values.toList()
        pendingHotReloadHandles.clear()
        return stale
    }

    fun size(): Int = handles.size

    fun clearLocal() {
        handles.clear()
        pendingHotReloadHandles.clear()
    }

    enum class Registration {
        New,
        Duplicate,
    }

    private data class HookSignature(val id: String, val executable: String) {
        companion object {
            fun of(id: String, executable: Executable): HookSignature =
                HookSignature(id, executable.toGenericString())
        }
    }
}
