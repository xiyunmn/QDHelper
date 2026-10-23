package com.xiyunmn.qdhelper.feature.account

import android.app.Activity
import android.content.Context
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.currentHostUserId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.AccountTargetKeys
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal sealed interface AutoInvestDetailFetchResult {
    data class Success(val details: AutoInvestDetailSnapshot) : AutoInvestDetailFetchResult
    data class Rejected(val code: Int?, val message: String?) : AutoInvestDetailFetchResult
}

internal sealed interface AutoInvestRequestResult {
    data class Success(val message: String?) : AutoInvestRequestResult
    data class RiskRequired(val entry: Any, val message: String?) : AutoInvestRequestResult
    data class Rejected(val code: Int?, val message: String?) : AutoInvestRequestResult
}

internal data class AutoInvestVerification(
    val sessionKey: String,
    val banId: Int,
    val captchaTicket: String,
    val captchaRandStr: String,
    val challenge: String,
    val validate: String,
    val seccode: String,
)

/** Host network bridge backed exclusively by named resolver contracts. */
internal class AutoInvestHostBridge(private val classLoader: ClassLoader) {
    private val activeSubscriptions = ConcurrentHashMap.newKeySet<Any>()

    fun currentUserId(): Long = currentHostUserId(HostSessionStore.contextOrNull(), classLoader) ?: 0L

    fun readerBookId(activity: Activity): Long = runCatching {
        if (activity.javaClass.name != HostSymbols.Classes.QD_READER_ACTIVITY) return 0L
        val getter = resolve(
            AccountTargetKeys.autoInvestReaderBookId,
        ) ?: return 0L
        if (!isReaderBookIdGetterShape(getter)) return 0L
        (FeatureHooks.invoke(getter, activity) as? Number)?.toLong()?.takeIf { it > 0L } ?: 0L
    }.getOrDefault(0L)

    fun fetchDetails(context: Context, bookId: Long): AutoInvestDetailFetchResult {
        require(bookId > 0L)
        val detailMethod = resolve(AccountTargetKeys.autoInvestDetailApi)
            ?: error("investment detail API unavailable")
        val adapterMethod = resolve(AccountTargetKeys.autoInvestLegacyRxAdapter)
            ?: error("legacy Rx adapter unavailable")
        val entryClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.NEW_BOOK_INVEST_DETAIL_ENTRY, classLoader)
            ?: error("investment detail model unavailable")
        val serverResponseClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.SERVER_RESPONSE, classLoader)
            ?: error("server response model unavailable")
        val callbackInterface = adapterMethod.parameterTypes[0]
        val callbackBase = detailMethod.parameterTypes[2]
        val bridgeMethod = FeatureHooks.allDeclaredMethods(callbackInterface).singleOrNull { candidate ->
            Modifier.isAbstract(candidate.modifiers) && candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0].isAssignableFrom(callbackBase)
        } ?: error("legacy Rx callback contract mismatch")
        val callbackFactory = Proxy.newProxyInstance(
            callbackInterface.classLoader ?: classLoader,
            arrayOf(callbackInterface),
        ) { proxy, method, args ->
            when {
                method == bridgeMethod -> {
                    val callback = args?.getOrNull(0)
                        ?.takeIf(callbackBase::isInstance)
                        ?: error("investment callback unavailable")
                    FeatureHooks.invoke(detailMethod, null, context, bookId, callback)
                    null
                }
                method.name == "toString" -> "QDHelperAutoInvestDetailCallbackFactory"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
        val responseType = HostParameterizedType(serverResponseClass, entryClass)
        val observable = FeatureHooks.invoke(adapterMethod, null, callbackFactory, responseType, true)
            ?: error("investment detail observable unavailable")
        val response = blockingFirst(observable) ?: error("investment detail response unavailable")
        val code = numberMember(response, "code", "getCode")?.toInt()
        val message = stringMember(response, "message", "getMessage")
        if (code != 0) return AutoInvestDetailFetchResult.Rejected(code, message)
        val data = member(response, "data", "getData")
            ?.takeIf(entryClass::isInstance)
            ?: return AutoInvestDetailFetchResult.Rejected(code, message ?: "详情数据为空")
        return AutoInvestDetailFetchResult.Success(
            AutoInvestDetailSnapshot(
                bookId = numberMember(data, "BookId", "getBookId")?.toLong() ?: 0L,
                bookInvestStatus = numberMember(data, "BookInvestStatus", "getBookInvestStatus")?.toInt() ?: -1,
                userInvestStatus = numberMember(data, "UserInvestStatus", "getUserInvestStatus")?.toInt() ?: -1,
                todayReadSeconds = numberMember(data, "TodayReadTime", "getTodayReadTime")?.toLong(),
                restCount = numberMember(data, "RestCount", "getRestCount")?.toInt() ?: -1,
            ),
        )
    }

    fun requestInvestment(
        bookId: Long,
        verification: AutoInvestVerification? = null,
    ): AutoInvestRequestResult {
        require(bookId > 0L)
        val requestMethod = resolve(AccountTargetKeys.autoInvestRequestApi)
            ?: error("investment request API unavailable")
        check(isInvestmentRequestShape(requestMethod)) { "investment request contract mismatch" }
        val service = retrofitService(requestMethod.declaringClass)
            ?: error("investment Retrofit service unavailable")
        val proof = verification ?: EMPTY_VERIFICATION
        val observable = FeatureHooks.invoke(
            requestMethod,
            service,
            bookId,
            proof.sessionKey,
            proof.banId,
            proof.captchaTicket,
            proof.captchaRandStr,
            proof.challenge,
            proof.validate,
            proof.seccode,
        ) ?: error("investment request observable unavailable")
        val response = blockingFirst(observable) ?: error("investment response unavailable")
        val code = numberMember(response, "code", "getCode")?.toInt()
        val message = stringMember(response, "message", "getMessage")
        if (code == 0) return AutoInvestRequestResult.Success(message)
        val riskEntryClass = FeatureHooks.findClassOrNull(
            HostSymbols.Classes.NEW_BOOK_INVEST_VERIFY_RISK_ENTRY,
            classLoader,
        )
        val riskEntry = member(response, "data", "getData")
            ?.takeIf { riskEntryClass?.isInstance(it) == true }
        val banId = riskEntry?.let { numberMember(it, "BanId", "getBanId")?.toInt() } ?: 0
        return if ((code == 2 || code == 3) && riskEntry != null && banId > 0) {
            AutoInvestRequestResult.RiskRequired(riskEntry, message)
        } else {
            AutoInvestRequestResult.Rejected(code, message)
        }
    }

    /** Must be called on the main thread because the host may display a verification UI. */
    fun launchRiskVerification(
        activity: Activity,
        riskEntry: Any,
        onSuccess: (AutoInvestVerification) -> Unit,
        onError: (Throwable) -> Unit,
    ): Boolean {
        val helperClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.UNIVERSAL_RISK_HELPER, classLoader)
            ?: return false
        val riskEntryClass = FeatureHooks.findClassOrNull(
            HostSymbols.Classes.NEW_BOOK_INVEST_VERIFY_RISK_ENTRY,
            classLoader,
        ) ?: return false
        if (!riskEntryClass.isInstance(riskEntry)) return false
        val helper = FeatureHooks.allDeclaredFields(helperClass).singleOrNull { field ->
            Modifier.isStatic(field.modifiers) && field.type == helperClass
        }?.let { FeatureHooks.read(it, null) } ?: return false
        val observableClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.RX_OBSERVABLE, classLoader)
            ?: return false
        val showRisk = FeatureHooks.allDeclaredMethods(helperClass).singleOrNull { method ->
            Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                method.returnType == observableClass &&
                method.parameterTypes.size == 2 &&
                Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                method.parameterTypes[1] == riskEntryClass
        } ?: return false
        val observable = FeatureHooks.invoke(showRisk, helper, activity, riskEntry) ?: return false
        return subscribeObservable(
            observable = observable,
            onNext = { result ->
                val verification = result?.let { verificationOf(it, riskEntry) }
                if (verification == null) {
                    onError(IllegalStateException("host risk result contract mismatch"))
                } else {
                    onSuccess(verification)
                }
            },
            onError = onError,
        )
    }

    fun close() {
        activeSubscriptions.forEach(::disposeSubscription)
        activeSubscriptions.clear()
    }

    private fun verificationOf(result: Any, riskEntry: Any): AutoInvestVerification? {
        fun value(label: String): Any? {
            val getter = resolve(AccountTargetKeys.verification.getValue(label)) ?: return null
            if (!getter.declaringClass.isInstance(result)) return null
            return FeatureHooks.invoke(getter, result)
        }
        val verification = AutoInvestVerification(
            sessionKey = value("session") as? String ?: return null,
            banId = (value("ban") as? Number)?.toInt() ?: return null,
            captchaTicket = value("ticket") as? String ?: return null,
            captchaRandStr = value("rand") as? String ?: return null,
            challenge = value("challenge") as? String ?: return null,
            validate = value("validate") as? String ?: return null,
            seccode = value("seccode") as? String ?: return null,
        )
        val expectedSession = stringMember(riskEntry, "SessionKey", "getSessionKey") ?: return null
        val expectedBanId = numberMember(riskEntry, "BanId", "getBanId")?.toInt() ?: return null
        return verification.takeIf {
            it.sessionKey == expectedSession && it.banId == expectedBanId && it.banId > 0
        }
    }

    private fun subscribeObservable(
        observable: Any,
        onNext: (Any?) -> Unit,
        onError: (Throwable) -> Unit,
    ): Boolean {
        val subscribe = RxConsumerSubscriptionMethodPolicy.select(
            FeatureHooks.methodsInHierarchy(observable.javaClass),
        ) { consumerClass ->
            FeatureHooks.declaredMethodsNamed(consumerClass, "accept")
                .any { it.parameterTypes.size == 1 }
        } ?: return false
        val consumerClass = subscribe.parameterTypes[0]
        val disposableRef = AtomicReference<Any?>()
        val terminal = AtomicBoolean(false)
        val success = consumerProxy(consumerClass) { value ->
            if (terminal.compareAndSet(false, true)) {
                try {
                    onNext(value)
                } finally {
                    disposableRef.get()?.let(activeSubscriptions::remove)
                }
            }
        }
        val failure = consumerProxy(consumerClass) { value ->
            if (terminal.compareAndSet(false, true)) {
                try {
                    onError(value as? Throwable ?: RuntimeException(value?.toString().orEmpty()))
                } finally {
                    disposableRef.get()?.let(activeSubscriptions::remove)
                }
            }
        }
        val disposable = FeatureHooks.invoke(subscribe, observable, success, failure) ?: return false
        disposableRef.set(disposable)
        if (!terminal.get()) activeSubscriptions += disposable
        return true
    }

    private fun consumerProxy(consumerClass: Class<*>, onAccept: (Any?) -> Unit): Any =
        Proxy.newProxyInstance(consumerClass.classLoader ?: classLoader, arrayOf(consumerClass)) { proxy, method, args ->
            when (method.name) {
                "accept" -> {
                    onAccept(args?.getOrNull(0))
                    null
                }
                "toString" -> "QDHelperAutoInvestRiskConsumer"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }

    private fun disposeSubscription(subscription: Any) {
        FeatureHooks.methodsInHierarchy(subscription.javaClass)
            .firstOrNull { it.name == "dispose" && it.parameterTypes.isEmpty() }
            ?.let { runCatching { FeatureHooks.invoke(it, subscription) } }
    }

    private fun blockingFirst(observable: Any): Any? {
        val timeout = FeatureHooks.methodsInHierarchy(observable.javaClass).firstOrNull { method ->
            Modifier.isPublic(method.modifiers) && method.name == "timeout" &&
                method.parameterTypes.contentEquals(
                    arrayOf(Long::class.javaPrimitiveType, TimeUnit::class.java),
                )
        } ?: error("Rx timeout operator unavailable")
        val timed = FeatureHooks.invoke(timeout, observable, REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            ?: error("Rx timeout observable unavailable")
        val blockingFirst = FeatureHooks.methodsInHierarchy(timed.javaClass).firstOrNull { method ->
            Modifier.isPublic(method.modifiers) && method.name == "blockingFirst" &&
                method.parameterTypes.isEmpty() && method.returnType != Void.TYPE
        } ?: error("Rx blockingFirst unavailable")
        return FeatureHooks.invoke(blockingFirst, timed)
    }

    private fun retrofitService(apiInterface: Class<*>): Any? {
        val clientClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.QD_RETROFIT_CLIENT, classLoader)
            ?: return null
        val instance = FeatureHooks.findFieldOrNull(clientClass, "INSTANCE")
            ?.let { FeatureHooks.read(it, null) } ?: return null
        val getApi = FeatureHooks.declaredMethodsNamed(clientClass, "getApi").singleOrNull { method ->
            method.parameterTypes.contentEquals(arrayOf(Class::class.java))
        } ?: return null
        return FeatureHooks.invoke(getApi, instance, apiInterface)
    }

    private fun isInvestmentRequestShape(method: Method): Boolean {
        val params = method.parameterTypes
        return Modifier.isPublic(method.modifiers) && Modifier.isAbstract(method.modifiers) &&
            params.size == 8 && params[0] == Long::class.javaPrimitiveType &&
            params[1] == String::class.java && params[2] == Int::class.javaPrimitiveType &&
            params.drop(3).all { it == String::class.java } &&
            method.returnType.name == HostSymbols.Classes.RX_OBSERVABLE
    }

    private fun isReaderBookIdGetterShape(method: Method): Boolean =
        method.declaringClass.name == HostSymbols.Classes.QD_READER_ACTIVITY &&
            Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.isEmpty() && method.returnType == Long::class.javaPrimitiveType

    private fun member(target: Any, fieldName: String, getterName: String): Any? {
        FeatureHooks.findFieldOrNull(target.javaClass, fieldName)?.let { field ->
            return FeatureHooks.read(field, target)
        }
        val getter = FeatureHooks.methodsInHierarchy(target.javaClass).firstOrNull { method ->
            method.name == getterName && method.parameterTypes.isEmpty()
        } ?: return null
        return FeatureHooks.invoke(getter, target)
    }

    private fun numberMember(target: Any, fieldName: String, getterName: String): Number? =
        member(target, fieldName, getterName) as? Number

    private fun stringMember(target: Any, fieldName: String, getterName: String): String? =
        member(target, fieldName, getterName) as? String

    private fun resolve(key: TargetKey.MethodTarget): Method? =
        HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = key,
        )

    private class HostParameterizedType(
        private val raw: Type,
        private vararg val arguments: Type,
    ) : ParameterizedType {
        override fun getActualTypeArguments(): Array<out Type> = arguments.copyOf()
        override fun getRawType(): Type = raw
        override fun getOwnerType(): Type? = null
    }

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 12_000L
        const val INVESTMENT_PATH = "argus/api/v1/newbook/doinvestbook/risk"
        val EMPTY_VERIFICATION = AutoInvestVerification("", 0, "", "", "", "", "")
    }
}
