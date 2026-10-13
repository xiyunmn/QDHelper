package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileH5PolicyTest {
    @Test
    fun acceptsOnlyExactSupportedProfileHomeUrls() {
        assertTrue(ProfileH5Policy.isProfileHomeUrl("https://h5.if.qidian.com/new/user-center/home/?userId=123"))
        assertTrue(ProfileH5Policy.isProfileHomeUrl("https://brave.if.qidian.com/new/user-center/home?authorId=456"))
        assertTrue(ProfileH5Policy.isProfileHomeUrl("https://jtest.if.qidian.com/new/user-center/home/"))
        assertFalse(ProfileH5Policy.isProfileHomeUrl("http://h5.if.qidian.com/new/user-center/home?userId=123"))
        assertFalse(ProfileH5Policy.isProfileHomeUrl("https://h5.if.qidian.com.evil.test/new/user-center/home"))
        assertFalse(ProfileH5Policy.isProfileHomeUrl("https://h5.if.qidian.com/new/user-center/book-list?userId=123"))
    }

    @Test
    fun parsesAvatarAndUserIdLongPressTargets() {
        val avatar = ProfileH5Policy.parseActionTarget(
                "\"{\\\"kind\\\":\\\"avatar\\\",\\\"url\\\":\\\"//example.com/avatar.png\\\"," +
                    "\\\"name\\\":\\\"Reader\\\",\\\"id\\\":\\\"123\\\"}\"",
            )
        assertTrue(avatar is ProfileH5ActionTarget.Avatar)
        avatar as ProfileH5ActionTarget.Avatar
        assertEquals("https://example.com/avatar.png", avatar.url)
        assertEquals("Reader", avatar.displayName)
        assertEquals("123", avatar.id)
        val userId = ProfileH5Policy.parseActionTarget("{\"kind\":\"userId\",\"value\":\"987654\"}")
        assertTrue(userId is ProfileH5ActionTarget.UserId)
        userId as ProfileH5ActionTarget.UserId
        assertEquals("987654", userId.value)
    }

    @Test
    fun rejectsUnsafeOrMalformedLongPressTargets() {
        assertEquals(null, ProfileH5Policy.parseActionTarget("{\"kind\":\"avatar\",\"url\":\"javascript:alert(1)\"}"))
        assertEquals(null, ProfileH5Policy.parseActionTarget("{\"kind\":\"userId\",\"value\":\"0\"}"))
        assertEquals(null, ProfileH5Policy.parseActionTarget("not-json"))
        assertNotNull(ProfileH5Policy.summarizeResult("\"ready\""))
    }

    @Test
    fun appliesPageFinishedInjectionWithFragmentLikeOwner() {
        val webView = FakeWebView("https://h5.if.qidian.com/new/user-center/home/?userId=123")
        val fragment = FakeBrowserFragment(webView)
        val options = ProfileH5Options(avatarOriginal = true, userId = false, privacyRestore = true)

        ProfileH5PageApplier.apply(
            owner = fragment,
            expectedUrl = webView.getUrl(),
            reason = "page-finished",
            options = options,
        )

        assertEquals(listOf(ProfileH5Policy.script(options)), webView.evaluatedScripts)
    }

    private class FakeBrowserFragment(private val webView: FakeWebView) {
        fun getWebView(): FakeWebView = webView
    }

    private class FakeWebView(private val url: String) {
        val evaluatedScripts = mutableListOf<String>()

        fun getUrl(): String = url

        @Suppress("UNUSED_PARAMETER")
        fun evaluateJavascript(script: String, callback: FakeValueCallback) {
            evaluatedScripts += script
        }
    }

    private fun interface FakeValueCallback {
        fun onReceiveValue(value: String?)
    }
}
