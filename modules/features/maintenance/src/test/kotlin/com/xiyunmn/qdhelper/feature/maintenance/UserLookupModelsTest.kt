package com.xiyunmn.qdhelper.feature.maintenance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserLookupModelsTest {
    @Test
    fun validatesUserIdInput() {
        assertEquals(123L, UserLookupInput.userId(" 123 "))
        assertNull(UserLookupInput.userId("0"))
        assertNull(UserLookupInput.userId("-2"))
        assertNull(UserLookupInput.userId("abc"))
        assertNull(UserLookupInput.userId("   "))
    }

    @Test
    fun parsesUserPagesWithCompatibleFieldsAndDeduplicatesIds() {
        val users = UserLookupParser.parse(
            JSONObject(
                """
                {
                  "Data": {
                    "UserInfoPages": [
                      {"AuthorId": 12, "AuthorName": "甲"},
                      {"UserId": "13", "NickName": "乙"},
                      {"AuthorId": 12, "AuthorName": "重复"},
                      {"AuthorId": 14},
                      {"AuthorId": 0, "AuthorName": "无效"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(
                UserLookupCandidate(12L, "甲"),
                UserLookupCandidate(13L, "乙"),
                UserLookupCandidate(14L, ""),
            ),
            users,
        )
    }

    @Test
    fun parsesNestedAndCaseVariantUserPages() {
        val users = UserLookupParser.parse(
            JSONObject(
                """
                {"data":{"userinfoPages":{"items":[{"userinfo":{"USERID":"21","USERNAME":"嵌套用户"}}]}}}
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(UserLookupCandidate(21L, "嵌套用户")), users)
    }

    @Test
    fun exactUserIdMatchesByIdAndIgnoresUnknown() {
        val duplicates = listOf(
            UserLookupCandidate(7L, "同名"),
            UserLookupCandidate(8L, "同名"),
        )

        assertEquals(8L, UserLookupSelectionPolicy.exactUserId(duplicates, 8L)?.userId)
        assertNull(UserLookupSelectionPolicy.exactUserId(duplicates, 9L))
    }
}
