package com.xiyunmn.qdhelper.feature.pagecustomization

import org.junit.Assert.assertEquals
import org.junit.Test

class MinePageMemberFilterPolicyTest {
    @Test
    fun mergedMemberKeepsBalanceCardAndClearsOnlyMember() {
        assertEquals(
            MineMemberFilterAction.CLEAR_MEMBER,
            mineMemberFilterAction(type = 2, hasMember = true, hasAccountBalance = true, hideMember = true),
        )
    }

    @Test
    fun memberOnlyMergedCardIsRemovedInsteadOfLeavingBlankRow() {
        assertEquals(
            MineMemberFilterAction.REMOVE_ITEM,
            mineMemberFilterAction(type = 2, hasMember = true, hasAccountBalance = false, hideMember = true),
        )
    }

    @Test
    fun legacyStandaloneMemberCardIsRemoved() {
        assertEquals(
            MineMemberFilterAction.REMOVE_ITEM,
            mineMemberFilterAction(type = 3, hasMember = true, hasAccountBalance = false, hideMember = true),
        )
    }

    @Test
    fun disabledOrMissingMemberDoesNotChangeHostData() {
        assertEquals(
            MineMemberFilterAction.KEEP,
            mineMemberFilterAction(type = 2, hasMember = true, hasAccountBalance = true, hideMember = false),
        )
        assertEquals(
            MineMemberFilterAction.KEEP,
            mineMemberFilterAction(type = 2, hasMember = false, hasAccountBalance = true, hideMember = true),
        )
    }
}
