package com.xiyunmn.qdhelper.shared.fonts

import android.graphics.Typeface
import com.xiyunmn.qdhelper.foundation.logging.RuntimeLog
import java.io.File
import java.util.LinkedHashMap

/** Small process-local LRU keyed by the file signature as well as path and style. */
object FontTypefaceCache {
    private const val MAX_ENTRIES = 8

    private data class CacheKey(
        val path: String,
        val length: Long,
        val lastModified: Long,
        val fileKey: String?,
        val style: Int?,
        val weight: Int,
        val isolationKey: String?,
    )

    private val cache = LinkedHashMap<CacheKey, Typeface>(MAX_ENTRIES, 0.75f, true)
    @Suppress("unused")
    private val changeSubscription = FontLibraryChanges.addListener(::clear)

    @Synchronized
    fun load(path: String, style: Int? = null, weight: Int = 0, isolationKey: String? = null): Typeface? {
        if (path.isBlank()) return null
        val file = File(path)
        val signature = FontLibraryStore.fileSignature(file) ?: return null
        val key = CacheKey(signature.path, signature.length, signature.lastModified, signature.fileKey, style, weight, isolationKey)
        // A replacement at the same path must not leave old Typeface instances in the map.
        val stale = cache.keys.iterator()
        while (stale.hasNext()) {
            val old = stale.next()
            if (old.path == key.path &&
                (old.length != key.length || old.lastModified != key.lastModified || old.fileKey != key.fileKey)
            ) {
                stale.remove()
            }
        }
        cache[key]?.let { return it }
        if (!FontLibraryStore.validateTypeface(file)) return null
        return runCatching {
            // A file-backed Builder avoids the asset/style caches sharing a native role with a UI preview.
            val base = if (isolationKey == null) Typeface.createFromFile(file)
                else Typeface.Builder(file).build() ?: return null
            val styled = style?.let { Typeface.create(base, it) } ?: base
            val resolved = if (weight == 0) styled else Typeface.create(styled, weight.coerceIn(100, 900), styled.isItalic)
            cache[key] = resolved
            while (cache.size > MAX_ENTRIES) {
                val iterator = cache.entries.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            resolved
        }.getOrElse { throwable ->
            RuntimeLog.w("Font typeface load failed: ${throwable.javaClass.name}")
            null
        }
    }

    @Synchronized
    fun invalidatePath(path: String) {
        val normalized = FontLibraryStore.normalizePath(path)
        if (normalized.isBlank()) return
        val entries = cache.keys.iterator()
        while (entries.hasNext()) {
            if (entries.next().path == normalized) entries.remove()
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        FontLibraryStore.clearProbeCache()
    }
}
