package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePrivacyAdditionalEntriesTest {
    // #3: QDHomePageMicroBlogMasterViewHolder renders nothing unless authorTalkList is
    // non-empty. A bean with count>0 but an empty list previously produced a blank
    // card; it must not be treated as restoreable.
    @Test
    fun `microBlog with empty authorTalkList is not renderable even with count`() {
        assertFalse(ProfilePrivacyAdditionalEntries.hasMicroBlogData(SimpleMicroBlog(emptyList<Any>(), count = 5)))
        assertFalse(ProfilePrivacyAdditionalEntries.hasMicroBlogData(SimpleMicroBlog(null, count = 5)))
        assertTrue(ProfilePrivacyAdditionalEntries.hasMicroBlogData(SimpleMicroBlog(listOf<Any>(Any()), count = 0)))
    }

    @Test
    fun `yueLi data and tips checks match the holder subtitle requirement`() {
        val urlOnly = SimpleYueLi("https://h5.if.qidian.com/new/yueli/?userId=42", null)
        val tipsOnly = SimpleYueLi(null, "来到起点已经 2333 天")
        val none = SimpleYueLi(null, null)

        assertTrue(ProfilePrivacyAdditionalEntries.hasYueLiData(urlOnly))
        assertFalse(ProfilePrivacyAdditionalEntries.hasYueLiData(none))
        assertTrue(ProfilePrivacyAdditionalEntries.hasYueLiTips(tipsOnly))
        assertFalse(ProfilePrivacyAdditionalEntries.hasYueLiTips(urlOnly))
        assertFalse(ProfilePrivacyAdditionalEntries.hasYueLiTips(none))
    }

    // type9 专栏卡片与书单一致：columnList 非空或 count>0 即可恢复入口
    // （Holder 的「更多」在 count>3 时显示，count 本身支撑入口可用性）。
    @Test
    fun `column entry matches the book-list renderability rule`() {
        assertTrue(ProfilePrivacyAdditionalEntries.hasColumnData(SimpleColumn(listOf<Any>(Any()), count = 0)))
        assertTrue(ProfilePrivacyAdditionalEntries.hasColumnData(SimpleColumn(null, count = 5)))
        assertFalse(ProfilePrivacyAdditionalEntries.hasColumnData(SimpleColumn(null, count = 0)))
        assertFalse(ProfilePrivacyAdditionalEntries.hasColumnData(SimpleColumn(emptyList<Any>(), count = 0)))
    }

    // #1: the day-count subtitle is the yueLi tips. A public-page cached bean with
    // tips must win over the server-stripped private bean (url present, tips blank).
    @Test
    fun `preferYueLi prefers cached bean with tips over stripped private bean`() {
        val cached = SimpleYueLi("https://h5.if.qidian.com/new/yueli/?userId=42", "来到起点已经 2333 天")
        val stripped = SimpleYueLi("https://h5.if.qidian.com/new/yueli/?userId=42", null)
        assertSame(cached, ProfilePrivacyAdditionalEntries.preferYueLi(cached, stripped))
    }

    @Test
    fun `preferYueLi prefers cached url bean when tips are absent`() {
        val cachedUrl = SimpleYueLi("https://h5.if.qidian.com/new/yueli/?userId=42", null)
        val stripped = SimpleYueLi("https://h5.if.qidian.com/new/yueli/?userId=42", null)
        assertSame(cachedUrl, ProfilePrivacyAdditionalEntries.preferYueLi(cachedUrl, stripped))
    }

    @Test
    fun `preferYueLi falls back to source when cache has no data`() {
        val emptyCache = SimpleYueLi(null, null)
        val source = SimpleYueLi("https://h5.if.qidian.com/new/yueli/?userId=42", null)
        assertSame(source, ProfilePrivacyAdditionalEntries.preferYueLi(emptyCache, source))
        assertNull(ProfilePrivacyAdditionalEntries.preferYueLi(null, SimpleYueLi(null, null)))
    }

    // type18 配音卡片与书单/专栏一致：dataList 非空或 totalCount>0 即可恢复入口
    // （preload 只取首页，count 支撑 Holder 的「更多」入口可用性）。
    @Test
    fun `audio entry matches the book-list renderability rule`() {
        assertTrue(ProfilePrivacyAdditionalEntries.hasAudioData(SimpleAudio(listOf<Any>(Any()), totalCount = 0)))
        assertTrue(ProfilePrivacyAdditionalEntries.hasAudioData(SimpleAudio(null, totalCount = 5)))
        assertFalse(ProfilePrivacyAdditionalEntries.hasAudioData(SimpleAudio(null, totalCount = 0)))
        assertFalse(ProfilePrivacyAdditionalEntries.hasAudioData(SimpleAudio(emptyList<Any>(), totalCount = 0)))
    }

    @Test
    fun `capsule entry requires a non-empty preview list`() {
        assertTrue(ProfilePrivacyAdditionalEntries.hasCapsuleData(SimpleCapsule(listOf(Any()))))
        assertFalse(ProfilePrivacyAdditionalEntries.hasCapsuleData(SimpleCapsule(null)))
        assertFalse(ProfilePrivacyAdditionalEntries.hasCapsuleData(SimpleCapsule(emptyList<Any>())))
    }

    @Test
    fun `cv entry requires a non-empty preview list`() {
        assertTrue(ProfilePrivacyAdditionalEntries.hasCvData(SimpleCv(listOf(Any()))))
        assertFalse(ProfilePrivacyAdditionalEntries.hasCvData(SimpleCv(null)))
        assertFalse(ProfilePrivacyAdditionalEntries.hasCvData(SimpleCv(emptyList<Any>())))
    }

    private data class SimpleYueLi(val url: String?, val tips: String?)

    private data class SimpleMicroBlog(val authorTalkList: List<*>?, val count: Int)

    private data class SimpleColumn(val columnList: List<*>?, val count: Int)

    private data class SimpleAudio(val dataList: List<*>?, val totalCount: Int)

    private data class SimpleCapsule(val capsuleList: List<*>?)

    private data class SimpleCv(val list: List<*>?)
}
