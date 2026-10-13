package com.xiyunmn.qdhelper.feature.contenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePrivacySessionStoreTest {
    @Test
    fun clearedPageRejectsLatePreloadCommit() {
        val store = ProfilePrivacySessionStore()
        val activity = Any()
        val chapter = Any()
        store.activate(7L, activity, listOf("header"), forceFlag = false)

        assertTrue(store.cache(7L, chapter, null, requireActivePage = true))
        assertSame(chapter, store.cached(7L)?.chapterBean)
        assertEquals(listOf(7L), store.removeActivity(activity))

        assertFalse(store.cache(7L, Any(), null, requireActivePage = true))
        assertNull(store.cached(7L))
        assertNull(store.active(7L))
    }

    @Test
    fun clearingOneActivityPreservesOtherProfileSession() {
        val store = ProfilePrivacySessionStore()
        val firstActivity = Any()
        val secondActivity = Any()
        val secondCircle = Any()
        store.activate(1L, firstActivity, emptyList<Any>(), forceFlag = false)
        store.activate(2L, secondActivity, emptyList<Any>(), forceFlag = true)
        store.cache(2L, null, secondCircle, requireActivePage = true)

        assertEquals(listOf(1L), store.removeActivity(firstActivity))
        assertSame(secondActivity, store.active(2L)?.activityRef?.get())
        assertSame(secondCircle, store.cached(2L)?.circleBean)
    }

    @Test
    fun yueLiCacheSurvivesPageDestroyForPrivatePageRestore() {
        val store = ProfilePrivacySessionStore()
        val activity = Any()
        val yueLi = Any()
        assertTrue(store.cacheYueLi(42L, yueLi))
        store.activate(42L, activity, emptyList<Any>(), forceFlag = false)

        assertSame(yueLi, store.cachedYueLi(42L))
        store.removeActivity(activity)
        assertSame(yueLi, store.cachedYueLi(42L))
    }

    @Test
    fun publicProfileCardsSurvivePageDestroyForPrivatePageRestore() {
        val store = ProfilePrivacySessionStore()
        val activity = Any()
        val bookList = Any()
        val column = Any()
        val microBlog = Any()
        val audio = Any()
        val capsule = Any()
        val cv = Any()
        assertTrue(store.cacheBookList(42L, bookList))
        assertTrue(store.cacheColumns(42L, column))
        assertTrue(store.cacheMicroBlog(42L, microBlog))
        assertTrue(store.cacheAudio(42L, audio))
        assertTrue(store.cacheCapsule(42L, capsule))
        assertTrue(store.cacheCv(42L, cv))
        store.activate(42L, activity, emptyList<Any>(), forceFlag = false)

        store.removeActivity(activity)
        assertSame(bookList, store.cachedBookList(42L))
        assertSame(column, store.cachedColumns(42L))
        assertSame(microBlog, store.cachedMicroBlog(42L))
        assertSame(audio, store.cachedAudio(42L))
        assertSame(capsule, store.cachedCapsule(42L))
        assertSame(cv, store.cachedCv(42L))
    }

    @Test
    fun supplementalCachesAreBounded() {
        val store = ProfilePrivacySessionStore()
        (1L..33L).forEach { userId ->
            store.cacheYueLi(userId, userId)
            store.cacheBookList(userId, userId)
            store.cacheColumns(userId, userId)
            store.cacheMicroBlog(userId, userId)
            store.cacheAudio(userId, userId)
            store.cacheCapsule(userId, userId)
            store.cacheCv(userId, userId)
        }

        assertNull(store.cachedYueLi(1L))
        assertNull(store.cachedBookList(1L))
        assertNull(store.cachedColumns(1L))
        assertNull(store.cachedMicroBlog(1L))
        assertNull(store.cachedAudio(1L))
        assertNull(store.cachedCapsule(1L))
        assertNull(store.cachedCv(1L))
        assertEquals(33L, store.cachedYueLi(33L))
        assertEquals(33L, store.cachedAudio(33L))
    }

    @Test
    fun `clearAll clears capsule and cv supplemental caches`() {
        val store = ProfilePrivacySessionStore()
        store.cacheCapsule(42L, Any())
        store.cacheCv(42L, Any())

        store.clearAll()

        assertNull(store.cachedCapsule(42L))
        assertNull(store.cachedCv(42L))
    }
}
