package com.xiyunmn.qdhelper.feature.welfare.model

import org.junit.Assert.*
import org.junit.Test

class WelfareClaimStateTest {
    private fun claim(path: WelfareClaimPath = WelfareClaimPath.NATIVE) = WelfareClaimState(1, 0, 7, path)

    @Test fun normalRewardUsesAcknowledgedHostCallbackWithoutExtraSync() {
        val claim = claim()
        assertTrue(claim.beginClaim(WelfareClaimLeg.PRIMARY))
        claim.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.REWARDED)
        claim.endStream()
        claim.callbackAttempt()
        assertEquals(WelfareRecoveryDecision.CHECK_CALLBACK, claim.decision())
        assertTrue(claim.finishWithReceipt())
        assertFalse(claim.reservePageSync())
    }

    @Test fun groupedAdFailureWithConfirmedDefaultRewardAcceptsExistingCallback() {
        val claim = claim(WelfareClaimPath.GROUPED)
        claim.beginClaim(WelfareClaimLeg.DEFAULT_REWARD)
        claim.finishClaim(WelfareClaimLeg.DEFAULT_REWARD, WelfareClaimOutcome.REWARDED)
        claim.endStream()
        claim.callbackAttempt()
        assertEquals(WelfareRecoveryDecision.FINISHED, claim.decision(true))
    }

    @Test fun adFailureWithoutAwardNeverBecomesSuccess() {
        val claim = claim()
        claim.endStream()
        claim.callbackAttempt()
        assertFalse(claim.finishWithReceipt())
        assertEquals(WelfareRecoveryDecision.SYNC_PAGE, claim.decision(true))
    }

    @Test fun emptyResultWithoutHostCallbackOnlySyncsServerTaskData() {
        val claim = claim()
        claim.beginClaim(WelfareClaimLeg.PRIMARY)
        claim.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.EMPTY)
        claim.endStream()
        assertFalse(claim.finishWithReceipt())
        assertEquals(WelfareRecoveryDecision.SYNC_PAGE, claim.decision(false))
        assertTrue(claim.reservePageSync())
        assertTrue(claim.summary().contains("outcomes=[EMPTY]"))
    }

    @Test fun emptyResultWithActualHostReceiptDoesNotNeedExtraSync() {
        val claim = claim()
        claim.beginClaim(WelfareClaimLeg.PRIMARY)
        claim.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.EMPTY)
        claim.endStream()
        claim.callbackAttempt()
        assertTrue(claim.finishWithReceipt())
        assertFalse(claim.reservePageSync())
    }

    @Test fun businessRejectionRiskAndNetworkFailureArePreserved() {
        listOf(WelfareClaimOutcome.BUSINESS_REJECTED, WelfareClaimOutcome.RISK_REJECTED,
            WelfareClaimOutcome.NETWORK_ERROR, WelfareClaimOutcome.UNKNOWN).forEach { outcome ->
            val claim = claim()
            claim.beginClaim(WelfareClaimLeg.PRIMARY)
            claim.finishClaim(WelfareClaimLeg.PRIMARY, outcome)
            claim.endStream()
            claim.callbackAttempt()
            assertFalse(claim.finishWithReceipt())
            assertEquals(WelfareRecoveryDecision.SYNC_PAGE, claim.decision(true))
            assertTrue(claim.summary().contains("outcomes=[$outcome]"))
        }
    }

    @Test fun closedAdDoesNotRaceAnOutstandingClaim() {
        val claim = claim(WelfareClaimPath.GROUPED)
        claim.beginClaim(WelfareClaimLeg.PRIMARY)
        claim.endStream()
        claim.callbackAttempt()
        assertEquals(WelfareRecoveryDecision.WAIT, claim.decision(true))
        assertFalse(claim.reservePageSync())
        claim.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.REWARDED)
        assertEquals(WelfareRecoveryDecision.SYNC_PAGE, claim.decision(true))
    }

    @Test fun secondaryClaimAlsoHasToFinishBeforeSync() {
        val claim = claim()
        claim.beginClaim(WelfareClaimLeg.PRIMARY)
        claim.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.REWARDED)
        claim.beginClaim(WelfareClaimLeg.SECONDARY)
        claim.endStream()
        claim.callbackAttempt()
        assertEquals(WelfareRecoveryDecision.WAIT, claim.decision())
        claim.finishClaim(WelfareClaimLeg.SECONDARY, WelfareClaimOutcome.EMPTY)
        assertEquals(WelfareRecoveryDecision.SYNC_PAGE, claim.decision(true))
    }

    @Test fun duplicateRewardEventsAndLateErrorsCannotOverwriteConfirmedResult() {
        val claim = claim()
        assertTrue(claim.beginClaim(WelfareClaimLeg.PRIMARY))
        assertFalse(claim.beginClaim(WelfareClaimLeg.PRIMARY))
        assertTrue(claim.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.REWARDED))
        assertFalse(claim.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.NETWORK_ERROR))
        assertTrue(claim.endStream())
        assertFalse(claim.endStream())
        assertTrue(claim.callbackAttempt())
        assertFalse(claim.callbackAttempt())
        assertTrue(claim.finishWithReceipt())
        assertFalse(claim.reservePageSync())
    }

    @Test fun nativeDispatchWithoutJsReceiptCanSyncOnlyOnce() {
        val claim = claim()
        claim.beginClaim(WelfareClaimLeg.PRIMARY)
        claim.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.REWARDED)
        claim.endStream()
        claim.callbackAttempt()
        assertEquals(WelfareRecoveryDecision.SYNC_PAGE, claim.decision(false))
        assertTrue(claim.reservePageSync())
        assertFalse(claim.reservePageSync())
        assertFalse(claim.beginClaim(WelfareClaimLeg.PRIMARY))
    }

    @Test fun watchdogCannotConsumeTheOneSyncBeforeSlowServerResponse() {
        val claim = claim()
        claim.beginClaim(WelfareClaimLeg.PRIMARY)
        claim.endStream()
        assertTrue(claim.timeout())
        assertEquals(WelfareRecoveryDecision.WAIT, claim.decision())
        assertFalse(claim.reservePageSync())
        assertTrue(claim.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.REWARDED))
        claim.callbackAttempt()
        assertTrue(claim.finishWithReceipt())
    }

    @Test fun oldAdDeadlineCannotExpireANewlyStartedClaim() {
        val claim = claim()
        val adRevision = claim.waitRevision()
        claim.beginClaim(WelfareClaimLeg.PRIMARY)
        claim.endStream()
        assertFalse(claim.timeout(adRevision))
        assertEquals(WelfareRecoveryDecision.WAIT, claim.decision())
        assertTrue(claim.timeout(claim.waitRevision()))
        assertEquals(WelfareRecoveryDecision.WAIT, claim.decision())
    }

    @Test fun missingStreamWithNoOutstandingClaimCanSyncAfterWatchdog() {
        val claim = claim()
        assertEquals(WelfareRecoveryDecision.WAIT, claim.decision())
        claim.timeout()
        assertEquals(WelfareRecoveryDecision.SYNC_PAGE, claim.decision(false))
        assertTrue(claim.reservePageSync())
    }

    @Test fun rapidClicksRemainOneRequestAndDoNotInvalidateSuccessfulCallback() {
        val page = WelfarePageState()
        val original = page.begin(1, 100, WelfareClaimPath.NATIVE)!!
        original.beginClaim(WelfareClaimLeg.PRIMARY)
        assertNull(page.begin(2, 101, WelfareClaimPath.NATIVE))
        assertSame(original, page.active)
        assertEquals(WelfareRecoveryDecision.WAIT, original.decision())
        original.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.REWARDED)
        original.endStream()
        original.callbackAttempt()
        assertTrue(original.finishWithReceipt())
        assertFalse(original.reservePageSync())
        assertNull(page.begin(3, 100, WelfareClaimPath.NATIVE))
        assertNotNull(page.begin(4, 102, WelfareClaimPath.NATIVE))
    }

    @Test fun syncLeavesTheDocumentGenerationIntactAndAllowsTheNextRequest() {
        val page = WelfarePageState()
        val original = page.begin(1, 7, WelfareClaimPath.NATIVE)!!
        original.endStream()
        assertTrue(original.reservePageSync())
        assertNull(page.begin(2, 7, WelfareClaimPath.NATIVE))
        val next = page.begin(3, 8, WelfareClaimPath.NATIVE)!!
        assertEquals(original.generation, next.generation)
        assertFalse(original.callbackAttempt())
        assertTrue(page.isCurrent(next))
    }

    @Test fun navigationDestructionOrAccountChangeInvalidatesOldActions() {
        listOf<(WelfarePageState) -> Unit>({ it.pageStarted() }, { it.cancel() }).forEach { leave ->
            val page = WelfarePageState()
            val original = page.begin(1, 7, WelfareClaimPath.GROUPED)!!
            original.beginClaim(WelfareClaimLeg.PRIMARY)
            leave(page)
            assertFalse(page.isCurrent(original))
            assertFalse(original.finishClaim(WelfareClaimLeg.PRIMARY, WelfareClaimOutcome.REWARDED))
            assertFalse(original.reservePageSync())
            assertEquals(WelfareRecoveryDecision.CANCELLED, original.decision())
            assertNotNull(page.begin(2, 7, WelfareClaimPath.GROUPED))
        }
    }

    @Test fun distinctPageInstancesCanReuseCallbackIdWithoutCrossDelivery() {
        val first = WelfarePageState()
        val second = WelfarePageState()
        val a = first.begin(1, 7, WelfareClaimPath.NATIVE)!!
        val b = second.begin(2, 7, WelfareClaimPath.NATIVE)!!
        assertFalse(first.isCurrent(b))
        first.cancel()
        assertTrue(second.isCurrent(b))
        assertFalse(a.acceptsEvents())
    }
}
