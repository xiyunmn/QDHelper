package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileUserIdPolicyTest {
    @Test
    fun authorHeaderUsesPageUserId() {
        assertEquals(
            123456L,
            ProfileUserIdPolicy.resolveUserId(
                itemType = ProfileUserIdPolicy.AUTHOR_HEADER_TYPE,
                itemUserId = 123456L,
                readerUserId = null,
            ),
        )
        assertNull(
            ProfileUserIdPolicy.resolveUserId(
                itemType = ProfileUserIdPolicy.AUTHOR_HEADER_TYPE,
                itemUserId = 0L,
                readerUserId = 654321L,
            ),
        )
    }

    @Test
    fun readerHeaderPrefersBeanUserIdAndFallsBackToPageUserId() {
        assertEquals(
            654321L,
            ProfileUserIdPolicy.resolveUserId(
                itemType = ProfileUserIdPolicy.USER_HEADER_TYPE,
                itemUserId = 123456L,
                readerUserId = 654321L,
            ),
        )
        assertEquals(
            123456L,
            ProfileUserIdPolicy.resolveUserId(
                itemType = ProfileUserIdPolicy.USER_HEADER_TYPE,
                itemUserId = 123456L,
                readerUserId = 0L,
            ),
        )
    }

    @Test
    fun unsupportedOrInvalidHeadersDoNotRender() {
        assertNull(ProfileUserIdPolicy.resolveUserId(2, 123456L, 654321L))
        assertEquals(
            ProfileUserIdBindAction.REMOVE,
            ProfileUserIdPolicy.bindAction(enabled = true, userId = 0L, hasExistingView = false),
        )
        assertEquals(
            ProfileUserIdBindAction.REMOVE,
            ProfileUserIdPolicy.bindAction(enabled = false, userId = 123456L, hasExistingView = true),
        )
    }

    @Test
    fun repeatedBindUpdatesInsteadOfDuplicating() {
        assertEquals(
            ProfileUserIdBindAction.INSERT,
            ProfileUserIdPolicy.bindAction(enabled = true, userId = 123456L, hasExistingView = false),
        )
        assertEquals(
            ProfileUserIdBindAction.UPDATE,
            ProfileUserIdPolicy.bindAction(enabled = true, userId = 123456L, hasExistingView = true),
        )
    }

    @Test
    fun clipboardContainsOnlyNumericUserId() {
        assertEquals("userId：123456", ProfileUserIdPolicy.displayText(123456L))
        assertEquals("123456", ProfileUserIdPolicy.clipboardText(123456L))
    }
}
