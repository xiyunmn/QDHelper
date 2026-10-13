package com.xiyunmn.qdhelper.feature.contenttools

import java.lang.ref.WeakReference

internal class ProfilePrivacySessionStore {
    private companion object {
        const val MAX_SUPPLEMENTAL_USERS = 32
    }

    private val lock = Any()
    private val sections = mutableMapOf<Long, CachedProfileSections>()
    private val yueLi = mutableMapOf<Long, Any>()
    private val bookLists = mutableMapOf<Long, Any>()
    private val columns = mutableMapOf<Long, Any>()
    private val microBlogs = mutableMapOf<Long, Any>()
    private val audios = mutableMapOf<Long, Any>()
    private val capsules = mutableMapOf<Long, Any>()
    private val cvInfos = mutableMapOf<Long, Any>()
    private val activePages = mutableMapOf<Long, ActiveProfilePage>()

    fun clearAll() = synchronized(lock) {
        activePages.clear()
        sections.clear()
        yueLi.clear()
        bookLists.clear()
        columns.clear()
        microBlogs.clear()
        audios.clear()
        capsules.clear()
        cvInfos.clear()
    }

    fun activate(userId: Long, activity: Any, original: List<*>, forceFlag: Boolean) = synchronized(lock) {
        activePages[userId] = ActiveProfilePage(WeakReference(activity), original, forceFlag)
    }

    fun cache(
        userId: Long,
        chapterBean: Any?,
        circleBean: Any?,
        requireActivePage: Boolean,
    ): Boolean = synchronized(lock) {
        if (requireActivePage && activePages[userId]?.activityRef?.get() == null) return@synchronized false
        val existing = sections[userId]
        sections[userId] = CachedProfileSections(
            chapterBean = chapterBean ?: existing?.chapterBean,
            circleBean = circleBean ?: existing?.circleBean,
        )
        true
    }

    fun cached(userId: Long): CachedProfileSections? = synchronized(lock) { sections[userId] }

    fun cacheYueLi(userId: Long, value: Any): Boolean = synchronized(lock) {
        if (userId <= 0L) return@synchronized false
        yueLi.putSupplemental(userId, value)
        true
    }

    fun cachedYueLi(userId: Long): Any? = synchronized(lock) { yueLi[userId] }

    fun cacheBookList(userId: Long, value: Any): Boolean = synchronized(lock) {
        if (userId <= 0L) return@synchronized false
        bookLists.putSupplemental(userId, value)
        true
    }

    fun cachedBookList(userId: Long): Any? = synchronized(lock) { bookLists[userId] }

    fun cacheColumns(userId: Long, value: Any): Boolean = synchronized(lock) {
        if (userId <= 0L) return@synchronized false
        columns.putSupplemental(userId, value)
        true
    }

    fun cachedColumns(userId: Long): Any? = synchronized(lock) { columns[userId] }

    fun cacheMicroBlog(userId: Long, value: Any): Boolean = synchronized(lock) {
        if (userId <= 0L) return@synchronized false
        microBlogs.putSupplemental(userId, value)
        true
    }

    fun cachedMicroBlog(userId: Long): Any? = synchronized(lock) { microBlogs[userId] }

    fun cacheAudio(userId: Long, value: Any): Boolean = synchronized(lock) {
        if (userId <= 0L) return@synchronized false
        audios.putSupplemental(userId, value)
        true
    }

    fun cachedAudio(userId: Long): Any? = synchronized(lock) { audios[userId] }

    fun cacheCapsule(userId: Long, value: Any): Boolean = synchronized(lock) {
        if (userId <= 0L) return@synchronized false
        capsules.putSupplemental(userId, value)
        true
    }

    fun cachedCapsule(userId: Long): Any? = synchronized(lock) { capsules[userId] }

    fun cacheCv(userId: Long, value: Any): Boolean = synchronized(lock) {
        if (userId <= 0L) return@synchronized false
        cvInfos.putSupplemental(userId, value)
        true
    }

    fun cachedCv(userId: Long): Any? = synchronized(lock) { cvInfos[userId] }

    private fun <T> MutableMap<Long, T>.putSupplemental(userId: Long, value: T) {
        remove(userId)
        this[userId] = value
        while (size > MAX_SUPPLEMENTAL_USERS) remove(keys.first())
    }

    fun active(userId: Long): ActiveProfilePage? = synchronized(lock) {
        val active = activePages[userId] ?: return@synchronized null
        if (active.activityRef.get() != null) return@synchronized active
        activePages.remove(userId)
        sections.remove(userId)
        null
    }

    fun removeActivity(activity: Any): List<Long> = synchronized(lock) {
        val removedUserIds = mutableListOf<Long>()
        val iterator = activePages.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val activeActivity = entry.value.activityRef.get()
            if (activeActivity === activity || activeActivity == null) {
                iterator.remove()
                sections.remove(entry.key)
                removedUserIds += entry.key
            }
        }
        removedUserIds
    }
}

internal data class CachedProfileSections(
    val chapterBean: Any?,
    val circleBean: Any?,
)

internal data class ActiveProfilePage(
    val activityRef: WeakReference<Any>,
    val original: List<*>,
    val forceFlag: Boolean,
)
