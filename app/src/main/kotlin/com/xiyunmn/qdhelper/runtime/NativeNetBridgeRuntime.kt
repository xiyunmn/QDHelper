package com.xiyunmn.qdhelper.runtime

import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore

import com.xiyunmn.qdhelper.hook.resource.RuntimeCleanupRegistry
import com.xiyunmn.qdhelper.hook.runtime.HookRuntime
import com.xiyunmn.qdhelper.host.api.reflection.findHostClass
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetBridgeRegistry
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetResponseTransformer
import com.xiyunmn.qdhelper.host.api.gateway.NativeNetTransformerChain
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.BookshelfTargetKeys
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

object NativeNetBridgeRuntime : NativeNetBridgeRegistry {
    private const val FLUTTER_ENGINE = "io.flutter.embedding.engine.FlutterEngine"
    private val transformers = NativeNetTransformerChain { owner, throwable ->
        HookRuntime.w("NativeNet transformer failed: owner=$owner, error=${throwable.javaClass.name}")
    }
    private val cleanupRegistered = AtomicBoolean(false)
    private val installGate = NativeNetBridgeInstallGate()

    override fun register(
        ownerId: String,
        priority: Int,
        transformer: NativeNetResponseTransformer,
    ): AutoCloseable {
        return transformers.register(ownerId, priority, transformer)
    }

    fun install(module: XposedModule, classLoader: ClassLoader, phase: String): Int {
        ensureCleanup()
        return when (installGate.install(classLoader, installer@{
            val method = resolveRegistrationMethod(classLoader) ?: return@installer false
            val searchClass = method.parameterTypes.getOrNull(1) ?: return@installer false
            val callbackClass = callbackClassFor(searchClass) ?: return@installer false
            HookRuntime.hookAround(
                module,
                method,
                "NativeNetBridgeRuntime.${method.declaringClass.name}.${method.name}",
                "qdhelper.runtime.native_net_bridge.register",
            ) { chain ->
                val delegate = chain.args.getOrNull(1)
                if (delegate == null || !searchClass.isInstance(delegate)) return@hookAround chain.proceed()
                val proxy = bridgeProxy(classLoader, searchClass, callbackClass, delegate)
                chain.proceed(arrayOf(chain.args.getOrNull(0), proxy))
            }.also { installed ->
                if (installed) {
                    HookRuntime.i(
                        "NativeNet bridge registry installed: " +
                            "phase=$phase, target=${method.declaringClass.name}.${method.name}",
                    )
                }
            }
        })) {
            NativeNetBridgeInstallResult.INSTALLED -> 1
            NativeNetBridgeInstallResult.ALREADY_INSTALLED -> {
                HookRuntime.i("NativeNet bridge registry already installed: phase=$phase")
                0
            }
            NativeNetBridgeInstallResult.FAILED -> 0
        }
    }

    private fun transform(path: String, response: String): String {
        return transformers.transform(path, response)
    }

    private fun resolveRegistrationMethod(classLoader: ClassLoader): Method? {
        return HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = BookshelfTargetKeys.nativeNetBridgeRegister,
        )
    }

    private fun callbackClassFor(searchClass: Class<*>): Class<*>? {
        val outer = searchClass.name.substringBeforeLast('$', missingDelimiterValue = "")
        val classLoader = searchClass.classLoader ?: return null
        return outer.takeIf { it.isNotBlank() }?.let { classLoader.findHostClass("$it\$cihai") }
    }

    private fun bridgeProxy(
        classLoader: ClassLoader,
        searchClass: Class<*>,
        callbackClass: Class<*>,
        delegate: Any,
    ): Any = Proxy.newProxyInstance(classLoader, arrayOf(searchClass)) { proxy, method, args ->
        objectMethod(proxy, method, args) ?: if (
            (method.name == "search" || method.name == "judian") && args?.size == 4
        ) {
            val path = args[0] as? String
            val callback = args[3]
            if (callback != null && callbackClass.isInstance(callback)) {
                val next = args.copyOf()
                next[3] = callbackProxy(classLoader, callbackClass, callback, path.orEmpty())
                invoke(method, delegate, next)
            } else {
                invoke(method, delegate, args)
            }
        } else {
            invoke(method, delegate, args)
        }
    }

    private fun callbackProxy(
        classLoader: ClassLoader,
        callbackClass: Class<*>,
        delegate: Any,
        path: String,
    ): Any = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { proxy, method, args ->
        objectMethod(proxy, method, args) ?: if (method.name == "success" && args?.size == 1 && args[0] is String) {
            invoke(method, delegate, arrayOf(transform(path, args[0] as String)))
        } else {
            invoke(method, delegate, args)
        }
    }

    private fun objectMethod(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        if (method.declaringClass != Any::class.java) return null
        return when (method.name) {
            "toString" -> "QDHelperNativeNetBridgeProxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> null
        }
    }

    private fun invoke(method: Method, delegate: Any, args: Array<Any?>?): Any? = try {
        method.invoke(delegate, *(args ?: emptyArray()))
    } catch (throwable: InvocationTargetException) {
        throw throwable.targetException ?: throwable
    }

    private fun ensureCleanup() {
        if (cleanupRegistered.compareAndSet(false, true)) {
            RuntimeCleanupRegistry.register("native-net-bridge-runtime") {
                transformers.clear()
                installGate.clear()
                cleanupRegistered.set(false)
            }
        }
    }

}

internal enum class NativeNetBridgeInstallResult {
    INSTALLED,
    ALREADY_INSTALLED,
    FAILED,
}

internal class NativeNetBridgeInstallGate {
    private val installedClassLoaders = Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())

    fun install(classLoader: ClassLoader, installer: () -> Boolean): NativeNetBridgeInstallResult = synchronized(this) {
        if (classLoader in installedClassLoaders) {
            return@synchronized NativeNetBridgeInstallResult.ALREADY_INSTALLED
        }
        if (!installer()) {
            return@synchronized NativeNetBridgeInstallResult.FAILED
        }
        installedClassLoaders += classLoader
        NativeNetBridgeInstallResult.INSTALLED
    }

    fun clear() = synchronized(this) {
        installedClassLoaders.clear()
    }
}
