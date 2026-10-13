package com.xiyunmn.qdhelper.feature.contenttools

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule

internal object ProfilePrivacyPreloader {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val lifecycle = ProfilePrivacyPreloadLifecycle()
    private val sectionPreloader by lazy { ProfilePrivacySectionPreloader(lifecycle) { mainHandler } }
    private val supplementalPreloader by lazy {
        ProfilePrivacySupplementalPreloader(lifecycle) { mainHandler }
    }

    fun install(
        module: HookModule,
        classLoader: ClassLoader,
        metadata: RestoreMetadata,
    ): Int = sectionPreloader.install(module, metadata)

    fun maybePreload(
        context: Context?,
        userId: Long,
        metadata: RestoreMetadata,
        needsChapter: Boolean,
        needsCircle: Boolean,
        needsColumn: Boolean,
        needsBookList: Boolean,
        needsMicroBlog: Boolean,
        needsAudio: Boolean,
        needsCapsule: Boolean = false,
        needsCv: Boolean = false,
        anchorId: Long = 0L,
    ) {
        if (context == null || userId <= 0L) return
        sectionPreloader.maybePreload(
            context = context,
            userId = userId,
            metadata = metadata,
            needsChapter = needsChapter,
            needsCircle = needsCircle,
            needsColumn = needsColumn,
            needsBookList = needsBookList,
        )
        supplementalPreloader.maybePreload(
            userId = userId,
            metadata = metadata,
            needsMicroBlog = needsMicroBlog,
            needsAudio = needsAudio,
            needsCapsule = needsCapsule,
            needsCv = needsCv,
            anchorId = anchorId,
        )
    }

    fun maybePreloadYueLiTips(userId: Long, metadata: RestoreMetadata) {
        supplementalPreloader.maybePreloadYueLiTips(userId, metadata)
    }

    fun clearUser(userId: Long) {
        supplementalPreloader.clearUser(userId)
        lifecycle.invalidateUser(userId)
        sectionPreloader.clearUser(userId)
    }

    fun clearAll() {
        // Preserve the original teardown order while state lives in separate collaborators.
        lifecycle.invalidateAll()
        supplementalPreloader.clearAll()
        sectionPreloader.clearRequests()
        lifecycle.clearUserGenerations()
        sectionPreloader.clearCallbacks()
    }
}
