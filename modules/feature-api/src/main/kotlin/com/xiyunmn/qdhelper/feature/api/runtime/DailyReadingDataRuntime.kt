package com.xiyunmn.qdhelper.feature.api.runtime

import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

fun interface DailyReadingDataTransformer {
    fun transform(response: String): String
}

class DailyReadingDataTransformerChain(
    private val onFailure: (ownerId: String, throwable: Throwable) -> Unit = { _, _ -> },
) {
    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(
        ownerId: String,
        priority: Int,
        transformer: DailyReadingDataTransformer,
    ): AutoCloseable {
        require(ownerId.isNotBlank())
        val entry = Entry(ownerId, priority, transformer)
        entries[ownerId] = entry
        return AutoCloseable { entries.remove(ownerId, entry) }
    }

    fun transform(response: String): String {
        return entries.values.sortedByDescending { it.priority }.fold(response) { current, entry ->
            runCatching { entry.transformer.transform(current) }.getOrElse { throwable ->
                onFailure(entry.ownerId, throwable)
                current
            }
        }
    }

    fun clear() = entries.clear()

    private data class Entry(
        val ownerId: String,
        val priority: Int,
        val transformer: DailyReadingDataTransformer,
    )
}

object DailyReadingDataRuntime {
    private const val METHOD_CALL = "io.flutter.plugin.common.MethodCall"
    private const val METHOD_CHANNEL_RESULT = "io.flutter.plugin.common.MethodChannel\$Result"
    private const val GET_DAILY_READING_DATA = "getDailyReadingData"

    private val transformers = DailyReadingDataTransformerChain { ownerId, throwable ->
        FeatureHooks.w(
            "Daily reading data transformer failed: owner=$ownerId, error=${throwable.javaClass.name}",
        )
    }

    init {
        FeatureResources.register("daily-reading-data-runtime") { transformers.clear() }
    }

    fun register(
        ownerId: String,
        priority: Int,
        transformer: DailyReadingDataTransformer,
    ): AutoCloseable = transformers.register(ownerId, priority, transformer)

    fun install(module: HookModule, classLoader: ClassLoader, phase: String): Int {
        val activityClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.DAILY_READING_FLUTTER_ACTIVITY, classLoader)
            ?: return 0
        val methodCallClass = FeatureHooks.findClassOrNull(METHOD_CALL, classLoader) ?: return 0
        val resultClass = FeatureHooks.findClassOrNull(METHOD_CHANNEL_RESULT, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(activityClass, "doMethodCall", methodCallClass, resultClass)
            ?: FeatureHooks.declaredMethodsNamed(activityClass, "doMethodCall")
                .singleOrNull { candidate ->
                    candidate.parameterTypes.getOrNull(0)?.name == METHOD_CALL &&
                        candidate.parameterTypes.getOrNull(1)?.name == METHOD_CHANNEL_RESULT
                }
            ?: return 0
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "runtime.dailyReading.data:${method.declaringClass.name}.${method.name}",
            id = "qdhelper.runtime.daily_reading_data",
        ) { chain ->
            val call = chain.args.getOrNull(0)
            val result = chain.args.getOrNull(1)
            if (methodCallName(call) != GET_DAILY_READING_DATA || result == null) {
                return@hookAround chain.proceed()
            }
            val proxy = resultProxy(classLoader, resultClass, result) ?: return@hookAround chain.proceed()
            val nextArgs = chain.args.toTypedArray()
            nextArgs[1] = proxy
            chain.proceed(nextArgs)
        }
        if (!success) return 0
        FeatureHooks.i(
            "Daily reading data runtime installed: ${method.declaringClass.name}.${method.name}, phase=$phase",
        )
        return 1
    }

    private fun methodCallName(call: Any?): String? {
        call ?: return null
        return FeatureHooks.readDeclaredFieldValue(call, "method") as? String
    }

    private fun resultProxy(
        classLoader: ClassLoader,
        resultClass: Class<*>,
        delegate: Any,
    ): Any? {
        if (!resultClass.isInstance(delegate)) return null
        return Proxy.newProxyInstance(classLoader, arrayOf(resultClass)) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) {
                handleObjectMethod(proxy, method, args)
            } else if (method.name == "success" && args?.size == 1 && args[0] is String) {
                invokeDelegate(method, delegate, arrayOf(transformers.transform(args[0] as String)))
            } else {
                invokeDelegate(method, delegate, args)
            }
        }
    }

    private fun handleObjectMethod(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        return when (method.name) {
            "toString" -> "QDHelperDailyReadingDataProxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> null
        }
    }

    private fun invokeDelegate(method: Method, delegate: Any, args: Array<Any?>?): Any? = try {
        method.invoke(delegate, *(args ?: emptyArray()))
    } catch (throwable: InvocationTargetException) {
        throw throwable.targetException ?: throwable
    }
}
