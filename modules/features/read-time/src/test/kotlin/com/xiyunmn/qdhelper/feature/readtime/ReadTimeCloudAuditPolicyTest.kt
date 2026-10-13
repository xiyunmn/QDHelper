package com.xiyunmn.qdhelper.feature.readtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadTimeCloudAuditPolicyTest {
    @Test
    fun `automatic limit floors accepted multiplier to three decimals`() {
        assertEquals(2.026, ReadTimeCloudAuditPolicy.preciseAutomaticLimit(2.0262047), 0.0)
    }

    @Test
    fun `first probe establishes baseline without judging`() {
        val result = reconcile(
            previous = ReadTimeCloudAuditState(),
            cloudTodaySeconds = 1_000,
            originalMillis = 100_000,
            reportedMillis = 250_000,
        )

        assertEquals(ReadTimeCloudVerificationStatus.BASELINE, result.state.latest?.status)
        assertNull(result.state.latest?.cloudDeltaMillis)
        assertNull(result.automaticLimitMultiplier)
    }

    @Test
    fun `discounted sample limits effective multiplier to accepted thousandth`() {
        val baseline = state(cloudSeconds = 1_000, originalMillis = 100_000, reportedMillis = 250_000)
        val result = reconcile(
            previous = baseline,
            cloudTodaySeconds = 1_121,
            originalMillis = 160_000,
            reportedMillis = 400_000,
            configuredMultiplier = 2.5,
        )

        val latest = requireNotNull(result.state.latest)
        assertEquals(ReadTimeCloudVerificationStatus.DISCOUNTED, latest.status)
        assertEquals(2.0166, latest.acceptedMultiplier!!, 0.0001)
        assertEquals(29_000L, latest.discountedMillis)
        assertEquals(2.016, result.automaticLimitMultiplier!!, 0.0)
    }

    @Test
    fun `short sample cannot trigger fallback`() {
        val baseline = state(cloudSeconds = 1_000, originalMillis = 100_000, reportedMillis = 250_000)
        val result = reconcile(
            previous = baseline,
            cloudTodaySeconds = 1_010,
            originalMillis = 110_000,
            reportedMillis = 275_000,
            configuredMultiplier = 2.5,
        )

        assertNull(result.automaticLimitMultiplier)
        assertEquals(ReadTimeCloudVerificationStatus.ACCEPTED, result.state.latest?.status)
    }

    @Test
    fun `unchanged cloud keeps baseline for delayed update`() {
        val baseline = state(cloudSeconds = 1_000, originalMillis = 100_000, reportedMillis = 250_000)
        val result = reconcile(
            previous = baseline,
            cloudTodaySeconds = 1_000,
            originalMillis = 180_000,
            reportedMillis = 450_000,
            configuredMultiplier = 2.5,
        )

        assertEquals(ReadTimeCloudVerificationStatus.WAITING_FOR_CLOUD, result.state.latest?.status)
        assertEquals(baseline.baseline, result.state.baseline)
        assertNull(result.automaticLimitMultiplier)
    }

    @Test
    fun `implausibly low partial cloud update waits instead of falling back`() {
        val baseline = state(cloudSeconds = 1_000, originalMillis = 100_000, reportedMillis = 250_000)
        val result = reconcile(
            previous = baseline,
            cloudTodaySeconds = 1_030,
            originalMillis = 180_000,
            reportedMillis = 450_000,
            configuredMultiplier = 2.5,
        )

        assertEquals(ReadTimeCloudVerificationStatus.WAITING_FOR_CLOUD, result.state.latest?.status)
        assertEquals(baseline.baseline, result.state.baseline)
        assertNull(result.automaticLimitMultiplier)
    }

    @Test
    fun `accepted full report does not fall back`() {
        val baseline = state(cloudSeconds = 1_000, originalMillis = 100_000, reportedMillis = 250_000)
        val result = reconcile(
            previous = baseline,
            cloudTodaySeconds = 1_150,
            originalMillis = 160_000,
            reportedMillis = 400_000,
            configuredMultiplier = 2.5,
        )

        assertEquals(ReadTimeCloudVerificationStatus.ACCEPTED, result.state.latest?.status)
        assertEquals(0L, result.state.latest?.discountedMillis)
        assertNull(result.automaticLimitMultiplier)
    }

    private fun state(cloudSeconds: Int, originalMillis: Long, reportedMillis: Long) =
        ReadTimeCloudAuditState(
            baseline = ReadTimeCloudBaseline(
                dayKey = "20260805",
                checkedAt = 1L,
                todaySeconds = cloudSeconds,
                weekSeconds = cloudSeconds,
                localOriginalMillis = originalMillis,
                localReportedMillis = reportedMillis,
            ),
        )

    private fun reconcile(
        previous: ReadTimeCloudAuditState,
        cloudTodaySeconds: Int,
        originalMillis: Long,
        reportedMillis: Long,
        configuredMultiplier: Double? = 2.5,
    ) = ReadTimeCloudAuditPolicy.reconcile(
        previous = previous,
        dayKey = "20260805",
        checkedAt = 2L,
        cloudTodaySeconds = cloudTodaySeconds,
        cloudWeekSeconds = cloudTodaySeconds,
        local = ReadTimeAuditCounters(
            reportedOriginalMillis = originalMillis,
            reportedMillis = reportedMillis,
        ),
        configuredMultiplier = configuredMultiplier,
    )
}
