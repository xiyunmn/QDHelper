package com.xiyunmn.qdhelper.feature.welfare.action

import android.os.Handler
import android.os.Looper
import android.util.Pair
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.welfare.model.findField
import com.xiyunmn.qdhelper.feature.welfare.model.findMethod
import com.xiyunmn.qdhelper.feature.welfare.model.readFieldByType
import com.xiyunmn.qdhelper.feature.welfare.model.readNoArgMethod
import com.xiyunmn.qdhelper.feature.welfare.model.readObjectField
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Pending synthetic callbacks belong to one module load, not to the host process. */
internal object WelfareRewardDispatch {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var closed = false

    init {
        FeatureResources.register("welfare-reward-dispatch") {
            synchronized(lock) {
                closed = true
                handler.removeCallbacksAndMessages(null)
            }
        }
    }

    fun post(delayMs: Long = 0L, action: () -> Unit) {
        synchronized(lock) {
            if (!closed) handler.postDelayed({ if (!closed) action() }, delayMs)
        }
    }
}

internal fun Any?.writeObjectField(name: String, value: Any?) {
    this ?: return
    findField(javaClass, name)?.let { field ->
        if (!FeatureHooks.write(field, this, value)) {
            FeatureHooks.e("Welfare reward field write failed: field=$name")
        }
    }
}

internal fun Any?.sendFlutterSuccess(value: Any?) {
    this ?: return
    runCatching {
        FeatureHooks.declaredMethodsNamed(javaClass, "success")
            .singleOrNull { method -> method.parameterTypes.size == 1 }
            ?.let { method -> FeatureHooks.invoke(method, this, value) }
    }.onFailure { throwable ->
        FeatureHooks.e("Welfare reward Flutter result.success failed", throwable)
    }
}

internal fun Any?.dispatchQqMiniRewardCallbacks(compId: String?) {
    this ?: return
    val listener = readObjectField("mListener") ?: return
    WelfareRewardDispatch.post {
        listener.invokeNoArgVoidMethod("onADShow")
        WelfareRewardDispatch.post(QQMINI_REWARD_EVENT_DELAY_MS) { listener.invokeNoArgVoidMethod("onReward") }
        WelfareRewardDispatch.post(QQMINI_COMPLETE_EVENT_DELAY_MS) { listener.invokeNoArgVoidMethod("onVideoComplete") }
        WelfareRewardDispatch.post(QQMINI_CLOSE_EVENT_DELAY_MS) {
            listener.invokeSingleArgVoidMethod("onADClose", String::class.java, compId)
        }
    }
}

internal fun Any?.dispatchTangramRewardCallbacks() {
    this ?: return
    val listener = readFieldByType(QDReaderHost.Classes.TANGRAM_REWARD_LISTENER)
        ?: run {
            FeatureHooks.w("Welfare tangram reward dispatch skipped: listener field not found by type")
            return
        }
    val hostClassLoader = javaClass.classLoader ?: return
    WelfareRewardDispatch.post {
        listener.invokeNoArgVoidMethod("onADShow")
        WelfareRewardDispatch.post(TANGRAM_REWARD_EVENT_DELAY_MS) {
            val rewardResult = runCatching {
                val rewardClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.TANGRAM_REWARD_RESULT, hostClassLoader)
                    ?: return@runCatching null
                val constructor = FeatureHooks.findConstructorOrNull(rewardClass) ?: return@runCatching null
                FeatureHooks.newInstance(constructor)
            }.getOrNull()
            if (rewardResult != null) {
                listener.invokeSingleArgVoidMethod("onReward", rewardResult.javaClass, rewardResult)
            }
            listener.invokeNoArgVoidMethod("onReward")
        }
        WelfareRewardDispatch.post(TANGRAM_COMPLETE_EVENT_DELAY_MS) { listener.invokeNoArgVoidMethod("onADComplete") }
        WelfareRewardDispatch.post(TANGRAM_CLOSE_EVENT_DELAY_MS) { listener.invokeNoArgVoidMethod("onADClose") }
    }
}

internal fun Any?.buildImmediateRewardEventObservable(): Any? {
    this ?: return null
    val hostClassLoader = javaClass.classLoader ?: return null
    return runCatching {
        val rewardResultClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.TANGRAM_REWARD_RESULT, hostClassLoader)
            ?: return@runCatching null
        val rewardConstructor = FeatureHooks.findConstructorOrNull(rewardResultClass) ?: return@runCatching null
        val rewardResult = FeatureHooks.newInstance(rewardConstructor) ?: return@runCatching null
        val observableClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.RX_OBSERVABLE, hostClassLoader)
            ?: return@runCatching null
        val observableSourceClass = FeatureHooks.findClassOrNull(QDReaderHost.Classes.RX_OBSERVABLE_SOURCE, hostClassLoader)
            ?: return@runCatching null
        val rewardPair = Pair.create(5, rewardResult)
        val closePair = Pair.create(8, null)
        val just = FeatureHooks.findDeclaredMethodOrNull(observableClass, "just", Any::class.java)
            ?: return@runCatching null
        val rewardObservable = FeatureHooks.invoke(just, null, rewardPair) ?: return@runCatching null
        val closeObservable = FeatureHooks.invoke(just, null, closePair) ?: return@runCatching null
        val delay = FeatureHooks.findDeclaredMethodOrNull(
            observableClass,
            "delay",
            Long::class.javaPrimitiveType!!,
            TimeUnit::class.java,
        ) ?: return@runCatching null
        val delayedCloseObservable = FeatureHooks.invoke(
            delay,
            closeObservable,
            COMBO_CLOSE_EVENT_DELAY_MS,
            TimeUnit.MILLISECONDS,
        ) ?: return@runCatching null
        val concatWith = FeatureHooks.findDeclaredMethodOrNull(observableClass, "concatWith", observableSourceClass)
            ?: return@runCatching null
        FeatureHooks.invoke(concatWith, rewardObservable, delayedCloseObservable)
    }.onFailure { throwable ->
        FeatureHooks.e("Welfare reward immediate reward event observable build failed", throwable)
    }.getOrNull()
}

internal fun Any?.dispatchH5RewardStatusSequence(
    callbackId: Int,
    statusField: String,
    extraFields: Map<String, Any?> = emptyMap(),
) {
    this ?: return
    H5_REWARD_STATUS_SEQUENCE.forEachIndexed { index, status ->
        val delayMs = H5_REWARD_STATUS_DELAYS_MS.getOrElse(index) { 0L }
        WelfareRewardDispatch.post(delayMs) {
            runCatching {
                val payload = JSONObject()
                payload.put(statusField, status)
                extraFields.forEach { (key, value) ->
                    if (value != null) {
                        payload.put(key, value)
                    }
                }
                invokeJudianExecCallback(payload, callbackId)
            }.onFailure { throwable ->
                FeatureHooks.e(
                    "Welfare H5 reward callback dispatch failed: callbackId=$callbackId, status=$status",
                    throwable,
                )
            }
        }
    }
}

private fun Any?.invokeJudianExecCallback(payload: JSONObject, callbackId: Int) {
    this ?: return
    val method = findMethod(javaClass, "judian") { candidate ->
        val parameterTypes = candidate.parameterTypes
        parameterTypes.size == 3 &&
            parameterTypes[0] == String::class.java &&
            parameterTypes[1].name == JSONObject::class.java.name &&
            (parameterTypes[2] == Int::class.javaPrimitiveType || parameterTypes[2] == Int::class.javaObjectType)
    }
    if (method == null) {
        FeatureHooks.w("Welfare H5 reward callback invoke skipped: missing judian(String, JSONObject, int)")
        return
    }
    runCatching {
        FeatureHooks.invoke(method, this, "execCallback", payload, callbackId)
    }.onFailure { throwable ->
        FeatureHooks.e("Welfare H5 reward callback invoke failed", throwable)
    }
}

internal fun buildRewardBridgeKey(rewardVideoConfig: Any?): String? =
    (rewardVideoConfig.readNoArgMethod("getAdCodeId") as? String)?.takeIf(String::isNotBlank)

internal fun Any?.invokeNoArgVoidMethod(name: String) {
    this ?: return
    runCatching {
        FeatureHooks.declaredMethodsNamed(javaClass, name)
            .singleOrNull { method -> method.parameterTypes.isEmpty() }
            ?.let { method -> FeatureHooks.invoke(method, this) }
    }.onFailure { throwable ->
        FeatureHooks.e("Welfare reward callback invoke failed: method=$name", throwable)
    }
}

internal fun Any?.invokeSingleArgVoidMethod(name: String, parameterType: Class<*>, arg: Any?) {
    this ?: return
    runCatching {
        FeatureHooks.declaredMethodsNamed(javaClass, name).singleOrNull { method ->
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isAssignableFrom(parameterType)
        }?.let { method -> FeatureHooks.invoke(method, this, arg) }
    }.onFailure { throwable ->
        FeatureHooks.e("Welfare reward callback invoke failed: method=$name", throwable)
    }
}

private const val COMBO_CLOSE_EVENT_DELAY_MS = 260L
private val H5_REWARD_STATUS_SEQUENCE = intArrayOf(1, 2, 3, 5)
private val H5_REWARD_STATUS_DELAYS_MS = longArrayOf(0L, 60L, 140L, 300L)
private const val QQMINI_REWARD_EVENT_DELAY_MS = 120L
private const val QQMINI_COMPLETE_EVENT_DELAY_MS = 260L
private const val QQMINI_CLOSE_EVENT_DELAY_MS = 520L
private const val TANGRAM_REWARD_EVENT_DELAY_MS = 120L
private const val TANGRAM_COMPLETE_EVENT_DELAY_MS = 260L
private const val TANGRAM_CLOSE_EVENT_DELAY_MS = 520L
