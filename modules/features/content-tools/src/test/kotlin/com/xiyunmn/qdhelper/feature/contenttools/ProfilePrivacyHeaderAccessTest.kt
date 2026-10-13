package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilePrivacyHeaderAccessTest {
    @Test
    fun `keeps host all-sub URL when available`() {
        assertEquals(
            "qdreader://host-route",
            ProfilePrivacyHeaderAccess.resolveAllSubUrl("qdreader://host-route", true, 42L),
        )
    }

    @Test
    fun `builds verified all-sub URL only for a private profile`() {
        assertEquals(
            "https://h5.if.qidian.com/new/yueli/fullAll/?_viewmode=0&from=personalpage&userId=42",
            ProfilePrivacyHeaderAccess.resolveAllSubUrl(null, true, 42L),
        )
        assertNull(ProfilePrivacyHeaderAccess.resolveAllSubUrl(null, false, 42L))
    }

    @Test
    fun `does not build all-sub URL without a valid user ID`() {
        assertNull(ProfilePrivacyHeaderAccess.resolveAllSubUrl(null, true, 0L))
        assertNull(ProfilePrivacyHeaderAccess.resolveAllSubUrl("", true, -1L))
    }
}
