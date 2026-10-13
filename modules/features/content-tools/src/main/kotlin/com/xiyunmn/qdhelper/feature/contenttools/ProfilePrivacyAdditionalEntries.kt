package com.xiyunmn.qdhelper.feature.contenttools

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import java.util.ArrayList

internal object ProfilePrivacyAdditionalEntries {
    private const val USER_HEADER_TYPE = 7
    private const val PERSONAL_BOOK_LIST_TYPE = 8
    private const val PERSONAL_COLUMN_TYPE = 9
    private const val PERSONAL_YUELI_TYPE = 13
    // Normal personal profiles use type 24, whose holder/layout includes the avatar,
    // nickname and native date/text typography. Type 14 is the compact summary card.
    private const val PROFILE_MICROBLOG_TYPE = 24
    private const val PERSONAL_AUDIO_TYPE = 18
    private const val PERSONAL_CAPSULE_TYPE = 19
    private const val PERSONAL_CV_BOOK_LIST_TYPE = 21
    private const val YUELI_URL_PREFIX = "https://h5.if.qidian.com/new/yueli/?_viewmode=0&from=personalpage&userId="

    fun restore(
        sourceData: Any?,
        original: List<*>,
        metadata: RestoreMetadata,
        userId: Long,
        cachedYueLi: Any? = null,
        cachedBookList: Any? = null,
        cachedColumn: Any? = null,
        cachedMicroBlog: Any? = null,
        cachedAudio: Any? = null,
        cachedCapsule: Any? = null,
        cachedCv: Any? = null,
        anchorId: Long = 0L,
    ): List<Any?>? {
        if (userId <= 0L || !isPrivateProfile(original, metadata)) return null
        if (USER_HEADER_TYPE !in itemTypes(original, metadata)) return null

        val itemTypes = itemTypes(original, metadata)
        val candidates = candidates(
            sourceData,
            metadata,
            userId,
            cachedYueLi,
            cachedBookList,
            cachedColumn,
            cachedMicroBlog,
            cachedAudio,
            cachedCapsule,
            cachedCv,
            anchorId,
        )
        val availableTypes = candidates.filter(Entry::available).map(Entry::type)
        FeatureHooks.i(
            "Profile privacy hidden entry probe: availableTypes=$availableTypes, " +
                "existingTypes=${itemTypes.sorted()}",
        )
        val missing = candidates.filter { candidate ->
            candidate.supported && candidate.type !in itemTypes && candidate.available
        // 真实主页顺序：用户信息 header 后首个是「动态」，再依次书单/专栏/阅历/配音。
        // 服务端剥离后统一在 header 后补入，需把 type24 排在最前，否则卡片顺序错乱。
        }.sortedBy { it.type != PROFILE_MICROBLOG_TYPE }
        val hasYueLi = PERSONAL_YUELI_TYPE in itemTypes && metadata.setYueLiBean != null && metadata.setYueLiStatus != null
        if (missing.isEmpty() && !hasYueLi) return null

        val restored = ArrayList<Any?>(original.size + missing.size)
        var inserted = false
        original.forEach { item ->
            val type = item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType)
            if (type == PERSONAL_YUELI_TYPE && hasYueLi) {
                patchYueLi(item, candidates.first { it.type == PERSONAL_YUELI_TYPE }.value, userId, metadata)
            }
            restored += item
            if (!inserted && type == USER_HEADER_TYPE) {
                missing.forEach { candidate ->
                    val entry = FeatureHooks.newInstance(metadata.itemConstructor, candidate.type)
                    if (!applyEntry(entry, candidate, userId, metadata, anchorId)) return null
                    restored += entry
                }
                inserted = true
            }
        }
        if (!inserted) return null
        FeatureHooks.i("Profile privacy restore repaired homepage entries: " +
            "added=${missing.map { it.type }}, repairedYueLi=$hasYueLi, userId=<redacted>")
        return restored
    }

    private fun candidates(
        sourceData: Any?,
        metadata: RestoreMetadata,
        userId: Long,
        cachedYueLi: Any?,
        cachedBookList: Any?,
        cachedColumn: Any?,
        cachedMicroBlog: Any?,
        cachedAudio: Any?,
        cachedCapsule: Any?,
        cachedCv: Any?,
        anchorId: Long,
    ): List<Entry> {
        val bookList = sourceData?.let { FeatureHooks.invoke(metadata.myBookListGetter, it) }
            ?.takeIf(::hasBookListData)
            ?: cachedBookList
        val yueLi = preferYueLi(cachedYueLi, sourceData?.let { FeatureHooks.invoke(metadata.yueLiGetter, it) })
        val column = sourceData?.let { FeatureHooks.invoke(metadata.columnGetter, it) }
            ?.takeIf(::hasColumnData)
            ?: cachedColumn
        val microBlog = sourceData?.let { FeatureHooks.invoke(metadata.microBlogGetter, it) }
            ?.takeIf(::hasMicroBlogData)
            ?: cachedMicroBlog
        val audio = sourceData?.let { FeatureHooks.invoke(metadata.audioGetter, it) }
            ?.takeIf(::hasAudioData)
            ?: cachedAudio
        val capsule = sourceData?.let { FeatureHooks.invoke(metadata.capsuleGetter, it) }
            ?.takeIf(::hasCapsuleData)
            ?: cachedCapsule
        val cv = sourceData?.let { FeatureHooks.invoke(metadata.anchorInfoGetter, it) }
            ?.takeIf(::hasCvData)
            ?: cachedCv
        return listOf(
            Entry(
                type = PERSONAL_BOOK_LIST_TYPE,
                value = bookList,
                available = bookList?.let(::hasBookListData) == true,
                supported = metadata.setMyBookListBean != null,
                apply = setter(metadata.setMyBookListBean),
            ),
            Entry(
                type = PERSONAL_COLUMN_TYPE,
                value = column,
                available = column?.let(::hasColumnData) == true,
                supported = metadata.setColumnBean != null,
                apply = setter(metadata.setColumnBean),
            ),
            Entry(
                type = PERSONAL_YUELI_TYPE,
                value = yueLi,
                available = yueLi?.let(::hasYueLiData) == true || metadata.setYueLiBean != null,
                supported = metadata.setYueLiBean != null && metadata.setYueLiStatus != null,
                apply = { item, value -> patchYueLi(item, value, userId, metadata); true },
            ),
            Entry(
                type = PROFILE_MICROBLOG_TYPE,
                value = microBlog,
                available = microBlog?.let(::hasMicroBlogData) == true,
                supported = metadata.setMicroBlogBean != null,
                apply = setter(metadata.setMicroBlogBean),
            ),
            Entry(
                type = PERSONAL_AUDIO_TYPE,
                value = audio,
                available = audio?.let(::hasAudioData) == true,
                supported = metadata.setAudioInfoBean != null,
                apply = setter(metadata.setAudioInfoBean),
            ),
            Entry(
                type = PERSONAL_CAPSULE_TYPE,
                value = capsule,
                available = capsule?.let(::hasCapsuleData) == true,
                supported = metadata.setCapsuleBean != null,
                apply = setter(metadata.setCapsuleBean),
            ),
            Entry(
                type = PERSONAL_CV_BOOK_LIST_TYPE,
                value = cv,
                available = anchorId > 0L && cv?.let(::hasCvData) == true,
                supported = metadata.setAnchorInfoBean != null,
                apply = setter(metadata.setAnchorInfoBean),
            ),
        )
    }

    private fun setter(method: java.lang.reflect.Method?): (Any, Any?) -> Boolean = { item, value ->
        if (method == null) {
            false
        } else {
            FeatureHooks.invoke(method, item, value)
            true
        }
    }

    private fun applyEntry(
        item: Any?,
        entry: Entry,
        userId: Long,
        metadata: RestoreMetadata,
        anchorId: Long,
    ): Boolean {
        if (item == null || !metadata.itemClass.isInstance(item)) return false
        FeatureHooks.invoke(metadata.setUserId, item, userId)
        FeatureHooks.invoke(metadata.setMaster, item, false)
        FeatureHooks.invoke(metadata.setAuthor, item, false)
        FeatureHooks.invoke(metadata.setPrivacy, item, entry.type != PERSONAL_YUELI_TYPE)
        if (entry.type == PERSONAL_CV_BOOK_LIST_TYPE && anchorId > 0L) {
            metadata.setAnchorId?.let { FeatureHooks.invoke(it, item, anchorId) }
        }
        return entry.apply(item, entry.value)
    }

    private fun patchYueLi(item: Any, value: Any?, userId: Long, metadata: RestoreMetadata) {
        FeatureHooks.invoke(metadata.setUserId, item, userId)
        FeatureHooks.invoke(metadata.setMaster, item, false)
        FeatureHooks.invoke(metadata.setAuthor, item, false)
        FeatureHooks.invoke(metadata.setPrivacy, item, false)
        val current = invokeNoArg(item, "getYueLiBean")
        val bean = when {
            value?.let(::hasYueLiTips) == true -> value
            current?.let(::hasYueLiTips) == true -> current
            value?.let(::hasYueLiData) == true -> value
            else -> runCatching {
                FeatureHooks.newInstance(metadata.yueLiConstructor).also { fallback ->
                    FeatureHooks.invoke(metadata.setYueLiUrl, fallback, "$YUELI_URL_PREFIX$userId")
                    FeatureHooks.invoke(metadata.setYueLiTips, fallback, "")
                }
            }.getOrNull()
        }
        metadata.setYueLiBean?.let { FeatureHooks.invoke(it, item, bean) }
        metadata.setYueLiStatus?.let { FeatureHooks.invoke(it, item, 1) }
    }

    private fun isPrivateProfile(original: List<*>, metadata: RestoreMetadata): Boolean =
        original.any { item ->
            item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::isPrivacyStatEnabled) == true
        }

    private fun itemTypes(original: List<*>, metadata: RestoreMetadata): Set<Int> =
        original.mapNotNull { item ->
            item?.takeIf(metadata.itemClass::isInstance)?.let(metadata::itemType)
        }.toSet()

    internal fun hasBookListData(value: Any): Boolean {
        val lists = invokeNoArg(value, "getBookLists") as? List<*>
        val count = (invokeNoArg(value, "getCount") as? Number)?.toInt() ?: 0
        return !lists.isNullOrEmpty() || count > 0
    }

    internal fun hasColumnData(value: Any): Boolean {
        val lists = invokeNoArg(value, "getColumnList") as? List<*>
        val count = (invokeNoArg(value, "getCount") as? Number)?.toInt() ?: 0
        return !lists.isNullOrEmpty() || count > 0
    }

    internal fun hasYueLiData(value: Any): Boolean =
        !(invokeNoArg(value, "getUrl") as? String).isNullOrBlank()

    internal fun hasYueLiTips(value: Any): Boolean =
        !(invokeNoArg(value, "getTips") as? String).isNullOrBlank()

    // QDHomePageMicroBlogMasterViewHolder renders nothing unless authorTalkList is
    // non-empty; a count>0 bean with an empty list previously produced a blank card.
    internal fun hasMicroBlogData(value: Any): Boolean =
        !(invokeNoArg(value, "getAuthorTalkList") as? List<*>).isNullOrEmpty()

    // QDHomePagePersonalAudioHolder renders the dataList; a count>0 bean with an empty
    // list previously produced a titled but blank card.
    internal fun hasAudioData(value: Any): Boolean {
        val lists = invokeNoArg(value, "getDataList") as? List<*>
        val count = (invokeNoArg(value, "getTotalCount") as? Number)?.toInt() ?: 0
        return !lists.isNullOrEmpty() || count > 0
    }

    /** Capsule holder renders the list itself; a count without preview rows would be blank. */
    internal fun hasCapsuleData(value: Any): Boolean =
        !(invokeNoArg(value, "getCapsuleList") as? List<*>).isNullOrEmpty()

    /** CV holder dereferences list.size(), so only a non-empty preview is renderable. */
    internal fun hasCvData(value: Any): Boolean =
        !(invokeNoArg(value, "getList") as? List<*>).isNullOrEmpty()

    // Prefer the public-page cached yueLi (carries the day-count tips) over the
    // server-stripped private bean (url present, tips blank).
    internal fun preferYueLi(cached: Any?, source: Any?): Any? =
        cached?.takeIf { hasYueLiData(it) || hasYueLiTips(it) }
            ?: source?.takeIf(::hasYueLiData)

    private fun invokeNoArg(owner: Any, name: String): Any? =
        FeatureHooks.invokeNoArgOrNull(owner, name)

    private data class Entry(
        val type: Int,
        val value: Any?,
        val available: Boolean,
        val supported: Boolean,
        val apply: (Any, Any?) -> Boolean,
    )
}
