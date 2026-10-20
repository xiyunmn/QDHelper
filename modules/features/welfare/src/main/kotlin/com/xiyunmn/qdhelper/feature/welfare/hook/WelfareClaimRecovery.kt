package com.xiyunmn.qdhelper.feature.welfare.hook

import android.app.Activity
import android.util.Pair
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookChain
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.welfare.action.welfareConsumerProxy
import com.xiyunmn.qdhelper.feature.welfare.host.WelfareRecoveryHost
import com.xiyunmn.qdhelper.feature.welfare.model.WelfareClaimLeg
import com.xiyunmn.qdhelper.feature.welfare.model.WelfareClaimOutcome
import com.xiyunmn.qdhelper.feature.welfare.model.WelfareClaimPath
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal object WelfareClaimRecovery {
    private var runtime: WelfareClaimRuntime? = null

    init {
        FeatureResources.register("welfare-claim-recovery") {
            synchronized(this) {
                runtime?.close()
                runtime = null
            }
        }
    }

    @Synchronized
    fun install(module: HookModule, classLoader: ClassLoader): Int {
        if (runtime != null) return 0
        val host = WelfareRecoveryHost.resolve(classLoader) ?: return 0
        val candidate = WelfareClaimRuntime(host)
        val count = candidate.install(module)
        runtime = candidate
        return count
    }
}

private class WelfareClaimScope(val request: WelfareRecoveryRequest, val leg: WelfareClaimLeg? = null) {
    var subscribed = false
    var outcome: WelfareClaimOutcome? = null
    val consumed = AtomicBoolean()
}

private class WelfareClaimRuntime(private val host: WelfareRecoveryHost) : AutoCloseable {
    private val pages = WelfarePageRecovery(host) { ready && WelfareRewardProbeFeature.isAnyRewardBypassEnabled() }
    private val subscribing = ThreadLocal<WelfareClaimScope?>()
    private val synchronous = ThreadLocal<WelfareClaimScope?>()
    private val emitting = ThreadLocal<WelfareRecoveryRequest?>()
    // AtomicReference and host dialog wrappers use identity equality. Weak keys release completed streams.
    private val nativeOwners = WeakHashMap<Any, WelfareRecoveryRequest>()
    private val groupedOwners = WeakHashMap<Any, WelfareRecoveryRequest>()
    @Volatile private var ready = false

    fun install(module: HookModule): Int {
        var installed = 0
        fun around(key: TargetKey.MethodTarget, action: (HookChain) -> Any?) {
            if (FeatureHooks.hookAround(
                    module, host[key], "welfare.claimRecovery.${key.id.value}", "qdhelper.${key.id.value}",
                ) { chain -> if (ready) action(chain) else chain.proceed() }
            ) installed++
        }
        around(WelfareTargetKeys.nativeClaimBridge) { bridge(it, WelfareClaimPath.NATIVE) }
        around(WelfareTargetKeys.groupedClaimBridge) { bridge(it, WelfareClaimPath.GROUPED) }
        around(WelfareTargetKeys.nativeClaimEvent, ::nativeEvent)
        around(WelfareTargetKeys.groupedClaimEvent, ::groupedEvent)
        around(WelfareTargetKeys.groupedClaimError, ::groupedError)
        around(WelfareTargetKeys.rxSubscribe, ::subscribe)
        around(WelfareTargetKeys.responseBody) { chain ->
            val response = chain.proceed()
            synchronous.get()?.let { scope ->
                if (scope.outcome == null) observe(scope.request, "response_classification") {
                    scope.outcome = host.outcome(response)
                }
            }
            response
        }
        around(WelfareTargetKeys.h5Callback) { chain ->
            val request = emitting.get()
            if (request == null || chain.thisObject !== request.plugin.get() ||
                chain.args.firstOrNull() != "execCallback" ||
                chain.args.getOrNull(2) != request.state.callbackId
            ) return@around chain.proceed()
            if (!pages.valid(request) || !request.state.callbackAttempt()) {
                pages.trace(request, "stale_or_duplicate_callback_dropped")
                return@around null
            }
            pages.trace(request, "callback_attempt")
            chain.proceed()
        }
        around(WelfareTargetKeys.browserPageStarted) { chain ->
            observe(null, "page_started") { pages.pageStarted(chain.thisObject) }
            chain.proceed()
        }
        ready = installed == 9
        FeatureHooks.i("Welfare claim recovery installed=$installed/9 ready=$ready")
        return installed
    }

    private fun bridge(chain: HookChain, path: WelfareClaimPath): Any? {
        val native = path == WelfareClaimPath.NATIVE
        val plugin = chain.thisObject ?: return chain.proceed()
        val activity = chain.args.getOrNull(if (native) 7 else 3) as? Activity ?: return chain.proceed()
        val config = chain.args.getOrNull(if (native) 3 else 2) ?: return chain.proceed()
        if (chain.args.getOrNull(if (native) 8 else 4) !is JSONObject) return chain.proceed()
        val callbackId = chain.args.lastOrNull() as? Int ?: return chain.proceed()
        val start = runCatching { pages.begin(plugin, activity, config, callbackId, path) }.getOrElse {
            FeatureHooks.w("Welfare recovery begin failed: ${it.javaClass.simpleName}")
            return chain.proceed()
        }
        if (start.suppress) return null
        val request = start.request ?: return chain.proceed()
        val scope = WelfareClaimScope(request)
        return try {
            within(subscribing, scope) { chain.proceed() }
        } catch (failure: Throwable) {
            observe(request, "bridge_error") {
                request.state.endStream()
                pages.changed(request, "bridge_error_${failure.javaClass.simpleName}")
            }
            throw failure
        } finally {
            if (!scope.subscribed) observe(request, "missing_subscription") {
                request.state.endStream()
                pages.changed(request, "missing_subscription")
            }
        }
    }

    private fun nativeEvent(chain: HookChain): Any? {
        val reference = chain.args.firstOrNull() ?: return chain.proceed()
        val request = synchronized(nativeOwners) {
            nativeOwners[reference] ?: pages.byConfig(chain.args.getOrNull(4))?.also { nativeOwners[reference] = it }
        } ?: return chain.proceed()
        val event = chain.args.lastOrNull() as? Int
        val leg = when (event) {
            5, 11 -> WelfareClaimLeg.PRIMARY
            10 -> WelfareClaimLeg.SECONDARY
            else -> null
        }
        if (leg == null) {
            pages.trace(request, "native_ad_event_$event")
            return chain.proceed()
        }
        return business(chain, request, leg, async = event == 10)
    }

    private fun groupedRequest(chain: HookChain): WelfareRecoveryRequest? {
        val dialog = chain.args.firstOrNull() ?: return null
        return synchronized(groupedOwners) {
            groupedOwners[dialog] ?: pages.byActivity(host.groupedActivity(dialog))?.also { groupedOwners[dialog] = it }
        }
    }

    private fun groupedEvent(chain: HookChain): Any? {
        val request = groupedRequest(chain) ?: return chain.proceed()
        val event = (chain.args.lastOrNull() as? Pair<*, *>)?.first as? Int
        if (event != 5) return chain.proceed()
        return business(chain, request, WelfareClaimLeg.PRIMARY, async = true)
    }

    private fun groupedError(chain: HookChain): Any? {
        val request = groupedRequest(chain) ?: return chain.proceed()
        val error = chain.args.lastOrNull() as? Throwable ?: return chain.proceed()
        // The default claim remains synchronous, but newer hosts return an error/recovery stream.
        return business(chain, request, WelfareClaimLeg.DEFAULT_REWARD, async = false,
            suppressed = { host.suppressedGroupedError(error) })
    }

    private fun business(
        chain: HookChain,
        request: WelfareRecoveryRequest,
        leg: WelfareClaimLeg,
        async: Boolean,
        suppressed: () -> Any? = { null },
    ): Any? {
        if (!request.state.beginClaim(leg)) {
            pages.trace(request, "duplicate_or_stale_claim_event_dropped")
            return suppressed()
        }
        pages.trace(request, "claim_begin_$leg")
        pages.claimStarted(request)
        val scope = WelfareClaimScope(request, leg)
        var failed = false
        return try {
            within(synchronous, scope) { within(subscribing, scope) { chain.proceed() } }
        } catch (failure: Throwable) {
            failed = true
            if (scope.outcome == null) scope.outcome = WelfareClaimOutcome.NETWORK_ERROR
            throw failure
        } finally {
            if (!async || !scope.subscribed || failed) observe(request, "claim_finish") {
                request.state.finishClaim(leg, scope.outcome ?: WelfareClaimOutcome.UNKNOWN)
                pages.changed(request, "claim_end_$leg")
            }
        }
    }

    private fun subscribe(chain: HookChain): Any? {
        val scope = subscribing.get() ?: return chain.proceed()
        if (scope.subscribed) return chain.proceed()
        val type = host[WelfareTargetKeys.rxSubscribe].parameterTypes[0]
        val delegates = chain.args.take(2)
        if (delegates.size != 2 || delegates.any { !type.isInstance(it) }) return chain.proceed()
        val arguments = chain.args.toTypedArray()
        for (index in 0..1) {
            arguments[index] = welfareConsumerProxy(type, delegates[index]!!) { value, proceed ->
                if (scope.leg == null) streamResult(scope.request, index == 1, value, proceed)
                else businessResult(scope, index == 1, value, proceed)
            }
        }
        scope.subscribed = true
        // Only decorate the existing subscription. Never subscribe a second time.
        return chain.proceed(arguments)
    }

    private fun businessResult(scope: WelfareClaimScope, error: Boolean, value: Any?, proceed: () -> Unit) {
        if (!scope.consumed.compareAndSet(false, true)) return
        val request = scope.request
        val outcome = if (error) WelfareClaimOutcome.NETWORK_ERROR else
            runCatching { host.outcome(value) }.getOrDefault(WelfareClaimOutcome.UNKNOWN)
        try {
            within(emitting, request, proceed)
        } finally {
            observe(request, "async_claim_finish") {
                request.state.finishClaim(scope.leg!!, outcome)
                pages.changed(request, "async_claim_end_$outcome")
            }
        }
    }

    private fun streamResult(request: WelfareRecoveryRequest, error: Boolean, value: Any?, proceed: () -> Unit) {
        val native = request.state.path == WelfareClaimPath.NATIVE
        val rewardList = value as? List<*>
        val empty = rewardList?.isEmpty() == true
        // New grouped streams return their accumulated reward list, not a Pair close event.
        val closed = error || native || rewardList != null || (value as? Pair<*, *>)?.first == 8
        if (closed && !request.state.endStream()) {
            pages.trace(request, "duplicate_or_stale_stream_terminal_dropped")
            return
        }
        if (error && request.state.hasConfirmedReward()) {
            // A late ad error must not overwrite an already confirmed server reward with a toast.
            request.state.requireRecovery()
            pages.changed(request, "confirmed_reward_late_ad_error")
            return
        }
        try {
            // Preserve both host handlers and their payloads. Missing callbacks are reconciled
            // through the page's read-only task-data event, never a synthetic reward callback.
            within(emitting, request, proceed)
        } catch (failure: Throwable) {
            observe(request, "host_callback_failed") { request.state.requireRecovery() }
            throw failure
        } finally {
            observe(request, "stream_result") {
                if (closed) pages.changed(request, if (error) "ad_or_stream_error" else if (empty) "empty_result" else "stream_terminal")
            }
        }
    }

    private fun observe(request: WelfareRecoveryRequest?, event: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            request?.state?.requireRecovery()
            FeatureHooks.w("Welfare recovery observer failed: event=$event, type=${failure.javaClass.simpleName}")
        }
    }

    private fun <T, R> within(local: ThreadLocal<T?>, value: T, action: () -> R): R {
        val previous = local.get()
        local.set(value)
        return try { action() } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }

    override fun close() {
        ready = false
        pages.close()
        synchronized(nativeOwners) { nativeOwners.clear() }
        synchronized(groupedOwners) { groupedOwners.clear() }
    }
}
