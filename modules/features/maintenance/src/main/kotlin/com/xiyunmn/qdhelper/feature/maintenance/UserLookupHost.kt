package com.xiyunmn.qdhelper.feature.maintenance

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.MaintenanceTargetKeys
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal sealed interface UserLookupHostResult {
    data class Success(val candidates: List<UserLookupCandidate>) : UserLookupHostResult
    data class Failure(val message: String) : UserLookupHostResult
}

internal object UserLookupHost {
    private const val SEARCH_TYPE_USER = 6
    private const val CALLBACK_TIMEOUT_SECONDS = 15L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun search(context: Context, keyword: String): UserLookupHostResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return UserLookupHostResult.Failure("用户查询不能在主线程执行")
        }
        return runCatching { searchResolved(context, keyword) }
            .onFailure { throwable ->
                FeatureHooks.w("User lookup failed: ${throwable.javaClass.name}")
            }
            .getOrElse { UserLookupHostResult.Failure("用户查询失败：${it.javaClass.simpleName}") }
    }

    private fun searchResolved(context: Context, keyword: String): UserLookupHostResult {
        val requestContext = context.applicationContext ?: context
        val classLoader = context.javaClass.classLoader ?: requestContext.javaClass.classLoader
            ?: return UserLookupHostResult.Failure("无法获取宿主 ClassLoader")
        val request = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull() ?: requestContext,
            classLoader = classLoader,
            processName = HostSessionStore.processName.ifBlank { requestContext.packageName },
            key = MaintenanceTargetKeys.userLookupSearchRequest,
        ) ?: return UserLookupHostResult.Failure("无法解析宿主用户搜索接口")
        val constructor = request.declaringClass.declaredConstructors.singleOrNull { candidate ->
            val params = candidate.parameterTypes
            params.size == 2 &&
                params[0].isInstance(requestContext) &&
                params[1].isInterface &&
                hasRequiredCallbacks(params[1])
        } ?: return UserLookupHostResult.Failure("宿主用户搜索构造契约不兼容")
        val callbackType = constructor.parameterTypes[1]
        val awaiting = AwaitingSearchCallback(callbackType, classLoader)
        val presenter = FeatureHooks.newInstance(constructor, requestContext, awaiting.proxy)
            ?: return UserLookupHostResult.Failure("无法创建宿主用户搜索请求")
        // 与宿主 CircleATSearchActivity.loadSearchData 完全一致：多余键会破坏 native 请求拼参。
        val params = ContentValues().apply {
            put("keyword", keyword)
            put("siteId", SEARCH_TYPE_USER)
            put("pageIndex", 1)
            put("pageSize", 20)
        }
        val posted = mainHandler.post {
            val invocation = FeatureHooks.invokeResult(request, presenter, keyword, params, SEARCH_TYPE_USER)
            if (invocation !is HostAccess.Value) {
                awaiting.fail("宿主用户搜索调用失败")
                FeatureHooks.w("User lookup request invocation failed: ${hostAccessLabel(invocation)}")
            }
        }
        if (!posted) {
            awaiting.fail("无法调度宿主用户搜索")
            FeatureHooks.w("User lookup request dispatch failed: main looper rejected task")
        }
        return awaiting.await()
    }

    private fun hasRequiredCallbacks(type: Class<*>): Boolean {
        val methods = type.methods
        return methods.any(::isSuccessCallback) && methods.any(::isFailureCallback)
    }

    private fun isSuccessCallback(method: Method): Boolean =
        method.name == "onLoadSuccess" &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(JSONObject::class.java))

    private fun isFailureCallback(method: Method): Boolean =
        method.name == "onLoadFailed" &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(String::class.java))

    private class AwaitingSearchCallback(callbackType: Class<*>, fallbackLoader: ClassLoader) {
        private val completion = CompletableFuture<CallbackOutcome>()

        val proxy: Any = Proxy.newProxyInstance(
            callbackType.classLoader ?: fallbackLoader,
            arrayOf(callbackType),
        ) { instance, method, args ->
            when {
                method.declaringClass == Any::class.java -> objectMethod(instance, method, args)
                isSuccessCallback(method) -> succeed(args?.firstOrNull() as? JSONObject)
                isFailureCallback(method) -> fail(args?.firstOrNull() as? String ?: "宿主用户搜索失败")
                else -> defaultValue(method.returnType)
            }
        }

        fun fail(message: String) {
            completion.complete(CallbackOutcome.Failure(message))
        }

        fun await(): UserLookupHostResult {
            val timeout = CallbackOutcome.Failure("用户查询超时，请检查网络后重试")
            val outcome = try {
                completion.get(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                completion.complete(timeout)
                completion.getNow(timeout)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                val cancelled = CallbackOutcome.Failure("用户查询已取消")
                completion.complete(cancelled)
                completion.getNow(cancelled)
            } catch (throwable: Throwable) {
                CallbackOutcome.Failure("宿主用户搜索未返回结果：${throwable.javaClass.simpleName}")
            }
            return when (outcome) {
                is CallbackOutcome.Payload -> {
                    val candidates = UserLookupParser.parse(outcome.root)
                    FeatureHooks.i("User lookup response parsed: candidates=${candidates.size}")
                    UserLookupHostResult.Success(candidates)
                }
                is CallbackOutcome.Failure -> UserLookupHostResult.Failure(outcome.message)
            }
        }

        private fun succeed(root: JSONObject?): Any? {
            if (root == null) {
                fail("宿主用户搜索返回为空")
            } else {
                completion.complete(CallbackOutcome.Payload(root))
            }
            return null
        }

        private sealed interface CallbackOutcome {
            data class Payload(val root: JSONObject) : CallbackOutcome
            data class Failure(val message: String) : CallbackOutcome
        }

        private fun objectMethod(instance: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "toString" -> "QDHelperUserLookupCallback"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.firstOrNull()
            else -> null
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> '\u0000'
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }

    private fun hostAccessLabel(result: HostAccess<*>): String = when (result) {
        is HostAccess.Value -> "value"
        HostAccess.Missing -> "missing"
        is HostAccess.Ambiguous -> "ambiguous(${result.candidateCount})"
        is HostAccess.Failed -> "failed(${result.errorType})"
    }
}

internal object UserProfileNavigator {
    fun open(activity: Activity, userId: Long): Boolean = runCatching {
        val classLoader = activity.javaClass.classLoader ?: return false
        val profileActivity = FeatureHooks.findClassOrNull(HostSymbols.Classes.HOME_PAGE_INFO_ACTIVITY, classLoader)
            ?: return false
        activity.startActivity(
            Intent(activity, profileActivity).putExtra("UserId", userId),
        )
        true
    }.onFailure { throwable ->
        FeatureHooks.w("Open user profile failed: ${throwable.javaClass.name}")
    }.getOrDefault(false)
}
