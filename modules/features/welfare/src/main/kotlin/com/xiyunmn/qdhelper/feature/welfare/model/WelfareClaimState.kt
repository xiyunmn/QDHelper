package com.xiyunmn.qdhelper.feature.welfare.model

internal enum class WelfareClaimPath { NATIVE, GROUPED }
internal enum class WelfareClaimLeg { PRIMARY, SECONDARY, DEFAULT_REWARD }
internal enum class WelfareClaimOutcome { REWARDED, EMPTY, BUSINESS_REJECTED, RISK_REJECTED, NETWORK_ERROR, UNKNOWN }
internal enum class WelfareRecoveryDecision { WAIT, CHECK_CALLBACK, SYNC_PAGE, FINISHED, CANCELLED }

/**
 * A single document/callback request. Ad completion is independent of business completion.
 * This state machine has no operation that can submit or retry a reward request.
 */
internal class WelfareClaimState(
    val id: Long,
    val generation: Long,
    val callbackId: Int,
    val path: WelfareClaimPath,
) {
    private val results = linkedMapOf<WelfareClaimLeg, WelfareClaimOutcome?>()
    private var responseRevision = 0
    private var waitingRevision = 0
    private var callbackRevision = -1
    private var streamEnded = false
    private var recoveryNeeded = false
    private var timedOut = false
    private var cancelled = false
    private var finished = false
    private var pageSynced = false

    @Synchronized
    fun beginClaim(leg: WelfareClaimLeg): Boolean {
        if (cancelled || finished || results.containsKey(leg)) return false
        results[leg] = null
        waitingRevision++
        timedOut = false
        return true
    }

    @Synchronized
    fun finishClaim(leg: WelfareClaimLeg, outcome: WelfareClaimOutcome): Boolean {
        if (cancelled || finished || !results.containsKey(leg) || results[leg] != null) return false
        results[leg] = outcome
        responseRevision++
        return true
    }

    @Synchronized
    fun endStream(): Boolean {
        if (cancelled || finished || streamEnded) return false
        streamEnded = true
        return true
    }

    /** Record an attempt, never equate a Java dispatch with receipt by the page. */
    @Synchronized
    fun callbackAttempt(): Boolean {
        if (cancelled || finished || callbackRevision >= 0) return false
        callbackRevision = responseRevision
        return true
    }

    @Synchronized
    fun requireRecovery() {
        if (!cancelled && !finished) recoveryNeeded = true
    }

    @Synchronized
    fun timeout(expectedRevision: Int = waitingRevision): Boolean {
        if (!cancelled && !finished && expectedRevision == waitingRevision) {
            timedOut = true
            return true
        }
        return false
    }

    @Synchronized
    fun waitRevision(): Int = waitingRevision

    @Synchronized
    fun decision(received: Boolean? = null): WelfareRecoveryDecision {
        if (cancelled) return WelfareRecoveryDecision.CANCELLED
        if (finished) return WelfareRecoveryDecision.FINISHED
        // A watchdog must not reconcile before an outstanding reward request has actually ended.
        if (results.values.any { it == null } || (!streamEnded && !timedOut)) return WelfareRecoveryDecision.WAIT
        if (received == null) return WelfareRecoveryDecision.CHECK_CALLBACK
        val confirmed = results.isNotEmpty() && results.values.all {
            it == WelfareClaimOutcome.REWARDED || it == WelfareClaimOutcome.EMPTY
        }
        if (recoveryNeeded || !confirmed || callbackRevision != responseRevision) return WelfareRecoveryDecision.SYNC_PAGE
        return when (received) {
            true -> WelfareRecoveryDecision.FINISHED
            false -> WelfareRecoveryDecision.SYNC_PAGE
        }
    }

    @Synchronized
    fun finishWithReceipt(): Boolean {
        if (decision(true) != WelfareRecoveryDecision.FINISHED || finished) return false
        finished = true
        return true
    }

    @Synchronized
    fun reservePageSync(): Boolean {
        if (cancelled || finished || pageSynced || decision(false) == WelfareRecoveryDecision.WAIT) return false
        pageSynced = true
        finished = true
        return true
    }

    @Synchronized
    fun cancel() {
        cancelled = true
    }

    @Synchronized
    fun acceptsEvents(): Boolean = !cancelled && !finished

    @Synchronized
    fun summary(): String =
        "path=$path, pending=${results.values.count { it == null }}, outcomes=${results.values}, " +
            "responseRevision=$responseRevision, callbackRevision=$callbackRevision, timeout=$timedOut"
}

/** Each document owns its callback IDs. Suppressing a duplicate never changes its real result. */
internal class WelfarePageState {
    @Volatile
    var generation: Long = 0L
        private set
    @Volatile
    var active: WelfareClaimState? = null
        private set
    private val consumedCallbacks = linkedSetOf<Int>()

    fun begin(id: Long, callbackId: Int, path: WelfareClaimPath): WelfareClaimState? {
        if (active?.acceptsEvents() == true || callbackId <= 0 || callbackId in consumedCallbacks) {
            return null
        }
        consumedCallbacks += callbackId
        if (consumedCallbacks.size > 256) consumedCallbacks.remove(consumedCallbacks.first())
        return WelfareClaimState(id, generation, callbackId, path).also { active = it }
    }

    fun pageStarted() {
        active?.cancel()
        active = null
        generation++
        consumedCallbacks.clear()
    }

    fun cancel() {
        active?.cancel()
        active = null
        generation++
        consumedCallbacks.clear()
    }

    fun isCurrent(claim: WelfareClaimState): Boolean =
        active === claim && claim.generation == generation && claim.acceptsEvents()
}
