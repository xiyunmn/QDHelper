package com.xiyunmn.qdhelper.feature.readtime

/** Keeps the host entity scaled only for the duration of one persistence call. */
internal object ReadTimePersistenceScaling {
    fun proceed(
        original: Long,
        scaled: Long,
        write: (Long) -> Boolean,
        persist: () -> Any?,
        onPersisted: () -> Unit,
        onScaleApplied: () -> Unit = {},
        onScaleFailed: (Throwable?) -> Unit,
        restoreRelatedState: () -> Boolean = { true },
        onRestoreFailed: () -> Unit,
    ): Any? {
        if (scaled == original) return persist()
        var scaleFailureReported = false
        val scaledWritten = runCatching { write(scaled) }.getOrElse { throwable ->
            scaleFailureReported = true
            onScaleFailed(throwable)
            false
        }
        if (!scaledWritten) {
            if (!scaleFailureReported) onScaleFailed(null)
            return persist()
        }
        onScaleApplied()

        val result = try {
            persist()
        } finally {
            val readTimeRestored = runCatching { write(original) }.getOrDefault(false)
            val relatedStateRestored = runCatching { restoreRelatedState() }.getOrDefault(false)
            if (!readTimeRestored || !relatedStateRestored) onRestoreFailed()
        }
        if (result == true) onPersisted()
        return result
    }
}
