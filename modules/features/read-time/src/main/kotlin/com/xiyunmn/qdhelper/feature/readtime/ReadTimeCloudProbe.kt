package com.xiyunmn.qdhelper.feature.readtime

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.ReadTimeTargetKeys
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

internal object ReadTimeCloudProbe {
    private const val NETWORK_TIMEOUT_MS = 12_000L

    /**
     * Blocks on the network call; must be invoked off the main thread.
     *
     * Returns null on any failure (class/method missing, network error, response shape mismatch).
     * The caller surfaces a neutral "unavailable" message rather than retrying — this is a manual
     * diagnostic action, not a hot path.
     */
    fun fetchTodayWeekSeconds(classLoader: ClassLoader): Result? {
        val apiMethod = resolveApiMethod(classLoader) ?: return null
        val service = retrofitService(apiMethod.declaringClass) ?: return null
        val observable = runCatching { FeatureHooks.invoke(apiMethod, service) }.getOrNull() ?: return null
        val data = blockingFirstWithTimeout(observable) ?: return null
        val readInfo = data.callNoArgMethod("getReadInfo") ?: return null
        val totals = ReadTimeCloudResponseReader.read(readInfo) ?: run {
            FeatureHooks.w("Read time cloud probe response fields unavailable")
            return null
        }
        FeatureHooks.i(
            "Read time cloud probe raw result: todaySec=${totals.todaySeconds}, " +
                "weekSec=${totals.weekSeconds}",
        )
        return Result(todaySeconds = totals.todaySeconds, weekSeconds = totals.weekSeconds)
    }

    data class Result(val todaySeconds: Int, val weekSeconds: Int)

    private fun resolveApiMethod(classLoader: ClassLoader): Method? =
        HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = ReadTimeTargetKeys.cloudReadPageApi,
        )

    private fun retrofitService(apiInterface: Class<*>): Any? {
        val classLoader = apiInterface.classLoader ?: return null
        val clientClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.QD_RETROFIT_CLIENT, classLoader)
            ?: return null
        val instanceField = FeatureHooks.findFieldOrNull(clientClass, "INSTANCE") ?: return null
        val instance = FeatureHooks.read(instanceField, null) ?: return null
        val getApi = FeatureHooks.declaredMethodsNamed(clientClass, "getApi")
            .singleOrNull { method -> method.parameterTypes.contentEquals(arrayOf(Class::class.java)) }
            ?: return null
        return FeatureHooks.invoke(getApi, instance, apiInterface)
    }

    private fun blockingFirstWithTimeout(observable: Any): Any? = runCatching {
        val classLoader = observable.javaClass.classLoader ?: return@runCatching null
        val observableClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.RX_OBSERVABLE, classLoader)
            ?: return@runCatching null
        val timeout = FeatureHooks.findDeclaredMethodOrNull(
            observableClass,
            "timeout",
            Long::class.javaPrimitiveType!!,
            TimeUnit::class.java,
        ) ?: return@runCatching null
        val timed = FeatureHooks.invoke(timeout, observable, NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: return@runCatching null
        val blockingFirst = FeatureHooks.findDeclaredMethodOrNull(observableClass, "blockingFirst") ?: return@runCatching null
        val response = FeatureHooks.invoke(blockingFirst, timed) ?: return@runCatching null
        response.callNoArgMethod("getData")
    }.onFailure { throwable ->
        FeatureHooks.w("Read time cloud probe failed: ${throwable.javaClass.simpleName}")
    }.getOrNull()

    private fun Any.callNoArgMethod(name: String): Any? = runCatching {
        FeatureHooks.declaredMethodsNamed(javaClass, name)
            .firstOrNull { method -> method.parameterTypes.isEmpty() }
            ?.let { method -> FeatureHooks.invoke(method, this) }
    }.getOrNull()
}
