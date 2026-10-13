package com.xiyunmn.qdhelper.feature.readtime

/**
 * Pure multiplier arithmetic for reported reading time.
 *
 * Kept free of Android and host types so the boundaries that actually matter — range validation
 * and the per-session ceiling — are unit-testable without a device.
 */
internal object ReadTimeMultiplierPolicy {
    const val MIN_MULTIPLIER = 0.1
    const val MAX_MULTIPLIER = 2.5
    const val DEFAULT_MULTIPLIER = 1.0

    fun effectiveMultiplier(configuredMultiplier: Double, automaticLimit: Double?): Double =
        automaticLimit
            ?.takeIf { it.isFinite() && it in 1.0..MAX_MULTIPLIER }
            ?.let { minOf(configuredMultiplier, it) }
            ?: configuredMultiplier

    /**
     * Per-row safety ceiling, matching the host's 10-minute automatic session-save threshold.
     *
     * Without it a multiplier could persist a row longer than the host itself normally produces,
     * which is exactly the kind of outlier the server is positioned to notice.
     */
    const val MAX_SESSION_MILLIS = 600_000L

    /** Parses user input, returning null when it is blank, malformed, or out of range. */
    fun parseMultiplier(raw: String?): Double? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val value = text.toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        if (value < MIN_MULTIPLIER || value > MAX_MULTIPLIER) return null
        return value
    }

    /** Clamps values saved by older versions to the current range during lazy migration. */
    fun migrateStoredMultiplier(raw: String?): Double? {
        val value = raw?.trim()?.toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        return value.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }

    /**
     * Scales one persisted session. Non-positive input is passed through untouched — the host
     * writes zero/negative values in edge cases and inflating them would fabricate sessions
     * rather than adjust real ones.
     */
    fun scaleSession(originalMillis: Long, multiplier: Double): Long {
        if (originalMillis <= 0L) return originalMillis
        val scaled = originalMillis * multiplier
        if (scaled.isNaN() || scaled.isInfinite()) return originalMillis
        val rounded = scaled.toLong().coerceAtLeast(0L)
        return rounded.coerceAtMost(MAX_SESSION_MILLIS)
    }

    /** Compact, non-identifying description used in hit logs. */
    fun describeScaling(originalMillis: Long, scaledMillis: Long, multiplier: Double): String {
        val clamped = isClamped(originalMillis, scaledMillis, multiplier)
        return "x$multiplier,fromSec=${originalMillis / 1000},toSec=${scaledMillis / 1000},clamped=$clamped"
    }

    fun isClamped(originalMillis: Long, scaledMillis: Long, multiplier: Double): Boolean =
        scaledMillis == MAX_SESSION_MILLIS && originalMillis * multiplier > MAX_SESSION_MILLIS
}
