package com.xiyunmn.qdhelper.feature.readtime

import android.content.Context
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max

internal data class ReadTimeCloudBaseline(
    val dayKey: String,
    val checkedAt: Long,
    val todaySeconds: Int,
    val weekSeconds: Int,
    val localOriginalMillis: Long,
    val localReportedMillis: Long,
)

internal enum class ReadTimeCloudVerificationStatus {
    BASELINE,
    NO_REPORTS,
    WAITING_FOR_CLOUD,
    ACCEPTED,
    DISCOUNTED,
    EFFECTIVE_LIMIT_APPLIED,
    RESET,
}

internal data class ReadTimeCloudVerification(
    val checkedAt: Long,
    val status: ReadTimeCloudVerificationStatus,
    val cloudTodaySeconds: Int,
    val cloudWeekSeconds: Int,
    val cloudDeltaMillis: Long? = null,
    val originalDeltaMillis: Long? = null,
    val reportedDeltaMillis: Long? = null,
    val acceptedMultiplier: Double? = null,
    val discountedMillis: Long? = null,
    val configuredMultiplier: Double? = null,
    val effectiveMultiplierBefore: Double? = null,
    val effectiveMultiplierAfter: Double? = null,
    val automaticLimitApplied: Boolean = false,
)

internal data class ReadTimeCloudAuditState(
    val baseline: ReadTimeCloudBaseline? = null,
    val latest: ReadTimeCloudVerification? = null,
)

internal object ReadTimeCloudAuditPolicy {
    const val MIN_SAMPLE_MILLIS = 60_000L
    private const val EFFECTIVE_MULTIPLIER_SCALE = 1_000.0
    private const val ABSOLUTE_DISCOUNT_TOLERANCE_MILLIS = 10_000L
    private const val RELATIVE_DISCOUNT_TOLERANCE = 0.05

    data class Result(
        val state: ReadTimeCloudAuditState,
        val automaticLimitMultiplier: Double? = null,
    )

    fun preciseAutomaticLimit(acceptedMultiplier: Double): Double =
        (floor(acceptedMultiplier * EFFECTIVE_MULTIPLIER_SCALE) / EFFECTIVE_MULTIPLIER_SCALE)
            .coerceIn(1.0, ReadTimeMultiplierPolicy.MAX_MULTIPLIER)

    fun reconcile(
        previous: ReadTimeCloudAuditState,
        dayKey: String,
        checkedAt: Long,
        cloudTodaySeconds: Int,
        cloudWeekSeconds: Int,
        local: ReadTimeAuditCounters,
        configuredMultiplier: Double?,
        currentEffectiveMultiplier: Double? = configuredMultiplier,
    ): Result {
        val current = ReadTimeCloudBaseline(
            dayKey = dayKey,
            checkedAt = checkedAt,
            todaySeconds = cloudTodaySeconds,
            weekSeconds = cloudWeekSeconds,
            localOriginalMillis = local.reportedOriginalMillis,
            localReportedMillis = local.reportedMillis,
        )
        val baseline = previous.baseline
        if (
            baseline == null || baseline.dayKey != dayKey ||
            cloudTodaySeconds < baseline.todaySeconds ||
            local.reportedOriginalMillis < baseline.localOriginalMillis ||
            local.reportedMillis < baseline.localReportedMillis
        ) {
            val status = if (baseline == null) ReadTimeCloudVerificationStatus.BASELINE else ReadTimeCloudVerificationStatus.RESET
            return Result(
                previous.copy(
                    baseline = current,
                    latest = verification(current, status, configuredMultiplier, currentEffectiveMultiplier),
                ),
            )
        }

        val cloudDelta = (cloudTodaySeconds - baseline.todaySeconds).toLong() * 1_000L
        val originalDelta = local.reportedOriginalMillis - baseline.localOriginalMillis
        val reportedDelta = local.reportedMillis - baseline.localReportedMillis
        if (originalDelta <= 0L || reportedDelta <= 0L) {
            val latest = verification(
                current,
                ReadTimeCloudVerificationStatus.NO_REPORTS,
                configuredMultiplier,
                currentEffectiveMultiplier,
            ).copy(
                cloudDeltaMillis = cloudDelta,
                originalDeltaMillis = originalDelta.coerceAtLeast(0L),
                reportedDeltaMillis = reportedDelta.coerceAtLeast(0L),
            )
            return Result(previous.copy(baseline = current, latest = latest))
        }

        val acceptedMultiplier = cloudDelta.toDouble() / originalDelta
        val discountedMillis = (reportedDelta - cloudDelta).coerceAtLeast(0L)
        val tolerance = max(
            ABSOLUTE_DISCOUNT_TOLERANCE_MILLIS,
            (reportedDelta * RELATIVE_DISCOUNT_TOLERANCE).toLong(),
        )
        val sampleReady = originalDelta >= MIN_SAMPLE_MILLIS
        val significantDiscount = sampleReady && discountedMillis > tolerance
        val waitingForCloud = reportedDelta > 0L && (cloudDelta <= 0L || (sampleReady && acceptedMultiplier < 0.9))
        val automaticLimit = if (
            significantDiscount && !waitingForCloud && configuredMultiplier != null &&
            currentEffectiveMultiplier != null && currentEffectiveMultiplier > 1.0
        ) {
            preciseAutomaticLimit(acceptedMultiplier)
                .takeIf { it < currentEffectiveMultiplier - (0.5 / EFFECTIVE_MULTIPLIER_SCALE) }
        } else {
            null
        }

        val status = when {
            waitingForCloud -> ReadTimeCloudVerificationStatus.WAITING_FOR_CLOUD
            significantDiscount -> ReadTimeCloudVerificationStatus.DISCOUNTED
            else -> ReadTimeCloudVerificationStatus.ACCEPTED
        }
        val latest = verification(current, status, configuredMultiplier, currentEffectiveMultiplier).copy(
            cloudDeltaMillis = cloudDelta,
            originalDeltaMillis = originalDelta,
            reportedDeltaMillis = reportedDelta,
            acceptedMultiplier = acceptedMultiplier,
            discountedMillis = discountedMillis,
            effectiveMultiplierBefore = currentEffectiveMultiplier,
            effectiveMultiplierAfter = currentEffectiveMultiplier,
        )
        // Keep the old baseline while the cloud value is still unchanged so a delayed server
        // update is assessed against the complete local interval on the next probe.
        val nextBaseline = if (waitingForCloud) baseline else current
        return Result(previous.copy(baseline = nextBaseline, latest = latest), automaticLimit)
    }

    private fun verification(
        current: ReadTimeCloudBaseline,
        status: ReadTimeCloudVerificationStatus,
        configuredMultiplier: Double?,
        effectiveMultiplier: Double?,
    ) = ReadTimeCloudVerification(
        checkedAt = current.checkedAt,
        status = status,
        cloudTodaySeconds = current.todaySeconds,
        cloudWeekSeconds = current.weekSeconds,
        configuredMultiplier = configuredMultiplier,
        effectiveMultiplierBefore = effectiveMultiplier,
        effectiveMultiplierAfter = effectiveMultiplier,
    )
}

internal object ReadTimeCloudAuditStore {
    private const val VERSION = 2
    private val lock = Any()

    @Volatile
    private var mutation = 0L

    fun reconcile(
        context: Context,
        userId: Long,
        cloud: ReadTimeCloudProbe.Result,
        now: Long = System.currentTimeMillis(),
        isCurrent: () -> Boolean = { true },
    ): ReadTimeCloudVerification? = synchronized(lock) {
        if (userId <= 0L || !isCurrent()) return null
        val appContext = context.applicationContext
        val container = readContainer(appContext)
        val previous = container.optJSONObject("accounts")?.optJSONObject(userId.toString())
        val audit = ReadTimeReportAuditStore.snapshot(appContext, userId, now)
        val configured = ReadTimeMultiplierPolicy.migrateStoredMultiplier(
            SettingsRuntime.readTimeMultiplierRaw(appContext),
        ).takeIf { SettingsRuntime.isReadTimeMultiplierEnabled(appContext) }
        val storedLimit = limit(previous)
        val effective = configured?.let { ReadTimeMultiplierPolicy.effectiveMultiplier(it, storedLimit) }
        val result = ReadTimeCloudAuditPolicy.reconcile(
            previous = decodeState(previous),
            dayKey = ReadTimeLocalStatisticsPolicy.dayKey(now),
            checkedAt = now,
            cloudTodaySeconds = cloud.todaySeconds,
            cloudWeekSeconds = cloud.weekSeconds,
            local = audit.today,
            configuredMultiplier = configured,
            currentEffectiveMultiplier = effective,
        )
        val originalLatest = requireNotNull(result.state.latest)
        val automaticLimit = result.automaticLimitMultiplier
        val latest = if (automaticLimit == null) originalLatest else originalLatest.copy(
            status = ReadTimeCloudVerificationStatus.EFFECTIVE_LIMIT_APPLIED,
            effectiveMultiplierAfter = automaticLimit,
            automaticLimitApplied = true,
        )
        container.getJSONObject("accounts").put(
            userId.toString(),
            encode(result.state.copy(latest = latest)).putNullable("effectiveMultiplier", automaticLimit ?: storedLimit),
        )
        if (!isCurrent()) return null
        // The baseline and its effective limit are one atomic preference write.
        if (!writeContainer(appContext, container)) return null
        if (automaticLimit != null) {
            FeatureHooks.w("Read time effective multiplier limited: configured=$configured,from=$effective,to=$automaticLimit")
        }
        latest
    }

    fun snapshot(context: Context, userId: Long?): ReadTimeCloudAuditState = synchronized(lock) {
        if (userId == null || userId <= 0L) return ReadTimeCloudAuditState()
        decodeState(readContainer(context).optJSONObject("accounts")?.optJSONObject(userId.toString()))
    }

    fun automaticLimit(context: Context, userId: Long?): Double? = synchronized(lock) {
        if (userId == null || userId <= 0L) return null
        limit(readContainer(context).optJSONObject("accounts")?.optJSONObject(userId.toString()))
    }

    fun clear(context: Context, userId: Long): Boolean = synchronized(lock) {
        val container = readContainer(context)
        val accounts = container.getJSONObject("accounts")
        val previous = accounts.optJSONObject(userId.toString()) ?: return true
        accounts.put(userId.toString(), encode(ReadTimeCloudAuditState()).putNullable("effectiveMultiplier", limit(previous)))
        writeContainer(context, container)
    }

    fun mutationSignature(): Long = mutation

    /** Start a new comparison interval after a deliberate user override, retaining audit history. */
    fun clearAutomaticLimit(context: Context, userId: Long): Boolean = synchronized(lock) {
        if (userId <= 0L) return false
        val container = readContainer(context)
        val account = container.getJSONObject("accounts").optJSONObject(userId.toString()) ?: return true
        account.remove("effectiveMultiplier")
        account.remove("baseline")
        writeContainer(context, container)
    }

    private fun readContainer(context: Context): JSONObject {
        val raw = SettingsRuntime.readTimeCloudAuditState(context)
        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json?.optInt("version") == VERSION) {
            if (json.optJSONObject("accounts") == null) json.put("accounts", JSONObject())
            return json
        }
        val migrated = JSONObject().put("version", VERSION).put("accounts", JSONObject())
        if (json?.optInt("version") == 1) migrated.put("unassigned", json)
        val legacyLimit = SettingsRuntime.readTimeEffectiveMultiplierRaw(context)
        if (legacyLimit.isNotBlank()) migrated.put("unassignedEffectiveMultiplier", legacyLimit)
        if ((json != null || legacyLimit.isNotBlank()) && writeContainer(context, migrated)) {
            SettingsRuntime.clearReadTimeEffectiveMultiplier(context)
        }
        return migrated
    }

    private fun writeContainer(context: Context, value: JSONObject): Boolean =
        SettingsRuntime.putReadTimeCloudAuditState(context.applicationContext, value.toString()).also { if (it) mutation++ }

    private fun limit(json: JSONObject?): Double? = json?.optNullableDouble("effectiveMultiplier")
        ?.takeIf { it.isFinite() && it in 1.0..ReadTimeMultiplierPolicy.MAX_MULTIPLIER }

    private fun decodeState(json: JSONObject?): ReadTimeCloudAuditState {
        json ?: return ReadTimeCloudAuditState()
        return ReadTimeCloudAuditState(
            baseline = json.optJSONObject("baseline")?.let(::decodeBaseline),
            latest = json.optJSONObject("latest")?.let(::decodeVerification),
        )
    }

    private fun encode(state: ReadTimeCloudAuditState): JSONObject = JSONObject()
        .put("baseline", state.baseline?.let(::encodeBaseline) ?: JSONObject.NULL)
        .put("latest", state.latest?.let(::encodeVerification) ?: JSONObject.NULL)

    private fun encodeBaseline(value: ReadTimeCloudBaseline): JSONObject = JSONObject()
        .put("dayKey", value.dayKey)
        .put("checkedAt", value.checkedAt)
        .put("todaySeconds", value.todaySeconds)
        .put("weekSeconds", value.weekSeconds)
        .put("localOriginalMillis", value.localOriginalMillis)
        .put("localReportedMillis", value.localReportedMillis)

    private fun decodeBaseline(json: JSONObject) = ReadTimeCloudBaseline(
        dayKey = json.optString("dayKey"),
        checkedAt = json.optLong("checkedAt"),
        todaySeconds = json.optInt("todaySeconds"),
        weekSeconds = json.optInt("weekSeconds"),
        localOriginalMillis = json.optLong("localOriginalMillis"),
        localReportedMillis = json.optLong("localReportedMillis"),
    )

    private fun encodeVerification(value: ReadTimeCloudVerification): JSONObject = JSONObject()
        .put("checkedAt", value.checkedAt)
        .put("status", value.status.name)
        .put("cloudTodaySeconds", value.cloudTodaySeconds)
        .put("cloudWeekSeconds", value.cloudWeekSeconds)
        .putNullable("cloudDeltaMillis", value.cloudDeltaMillis)
        .putNullable("originalDeltaMillis", value.originalDeltaMillis)
        .putNullable("reportedDeltaMillis", value.reportedDeltaMillis)
        .putNullable("acceptedMultiplier", value.acceptedMultiplier)
        .putNullable("discountedMillis", value.discountedMillis)
        .putNullable("configuredMultiplier", value.configuredMultiplier)
        .putNullable("effectiveMultiplierBefore", value.effectiveMultiplierBefore)
        .putNullable("effectiveMultiplierAfter", value.effectiveMultiplierAfter)
        .put("automaticLimitApplied", value.automaticLimitApplied)

    private fun decodeVerification(json: JSONObject) = ReadTimeCloudVerification(
        checkedAt = json.optLong("checkedAt"),
        status = runCatching { ReadTimeCloudVerificationStatus.valueOf(json.optString("status")) }
            .getOrDefault(ReadTimeCloudVerificationStatus.BASELINE),
        cloudTodaySeconds = json.optInt("cloudTodaySeconds"),
        cloudWeekSeconds = json.optInt("cloudWeekSeconds"),
        cloudDeltaMillis = json.optNullableLong("cloudDeltaMillis"),
        originalDeltaMillis = json.optNullableLong("originalDeltaMillis"),
        reportedDeltaMillis = json.optNullableLong("reportedDeltaMillis"),
        acceptedMultiplier = json.optNullableDouble("acceptedMultiplier"),
        discountedMillis = json.optNullableLong("discountedMillis"),
        configuredMultiplier = json.optNullableDouble("configuredMultiplier"),
        effectiveMultiplierBefore = json.optNullableDouble("effectiveMultiplierBefore"),
        effectiveMultiplierAfter = json.optNullableDouble("effectiveMultiplierAfter"),
        automaticLimitApplied = json.optBoolean("automaticLimitApplied"),
    )

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
    private fun JSONObject.optNullableLong(name: String): Long? = takeIf { has(name) && !isNull(name) }?.optLong(name)
    private fun JSONObject.optNullableDouble(name: String): Double? =
        takeIf { has(name) && !isNull(name) }?.optDouble(name)
}
