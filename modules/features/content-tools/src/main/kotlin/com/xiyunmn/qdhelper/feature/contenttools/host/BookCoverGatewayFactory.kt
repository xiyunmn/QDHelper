package com.xiyunmn.qdhelper.feature.contenttools.host

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.gateway.BookCover
import com.xiyunmn.qdhelper.host.api.gateway.BookCoverGateway
import com.xiyunmn.qdhelper.host.api.reflection.declaredMethodOrNull
import com.xiyunmn.qdhelper.host.api.reflection.findHostClass
import com.xiyunmn.qdhelper.host.api.reflection.stringField
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.host.api.target.BookCoverTargetKeys
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

object BookCoverGatewayFactory {
    fun create(classLoader: ClassLoader): BookCoverGateway = HostBookCoverGateway(classLoader)
}

private class HostBookCoverGateway(private val classLoader: ClassLoader) : BookCoverGateway, AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun loadCover(bookId: String): BookCover? {
        if (closed.get()) return null
        val numericId = bookId.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val loaded = loadCover(numericId, bookInfo(numericId)) ?: return null
        FeatureHooks.i("Original image cover: bookId=$bookId, source=${loaded.label}, " +
            "media=${loaded.cover.mediaType}, bytes=${loaded.cover.bytes.size}")
        return loaded.cover
    }

    override fun close() { closed.set(true) }

    private data class LoadedCover(val label: String, val cover: BookCover)

    private fun bookInfo(bookId: Long): Any? {
        val instanceMethod = resolveMethod(BookCoverTargetKeys.bookManagerInstance)
        val manager = instanceMethod?.let { FeatureHooks.invoke(it, null) } ?: return null
        val bookMethod = resolveMethod(BookCoverTargetKeys.bookInfoById)
        return bookMethod?.let { FeatureHooks.invoke(it, manager, bookId) }
    }

    private fun loadCover(
        bookId: Long,
        bookInfo: Any?,
    ): LoadedCover? {
        for ((label, source) in coverSources(bookId, bookInfo)) {
            if (closed.get()) return null
            val bytes = BookCoverCodec.readBytes(source) ?: continue
            if (closed.get()) return null
            val mediaType = BookCoverCodec.mediaType(source, bytes) ?: continue
            val extension = BookCoverCodec.extension(mediaType) ?: continue
            val cover = BookCover(bytes, mediaType, extension)
            if (closed.get()) return null
            return LoadedCover(label, cover)
        }
        return null
    }

    private fun coverSources(bookId: Long, bookInfo: Any?): List<Pair<String, String>> {
        val sources = LinkedHashMap<String, String>()
        fun add(label: String, source: String?) {
            val text = source?.trim().orEmpty()
            if (text.isNotBlank()) {
                sources.putIfAbsent(if (text.startsWith("//")) "https:$text" else text, label)
            }
        }
        add(
            "book_info",
            bookInfo?.stringField(
                "BookCover",
                "BookCoverUrl",
                "BookImage",
                "BookImageUrl",
                "BookImg",
                "Cover",
                "CoverUrl",
                "ImageUrl",
                "ImgUrl",
            ),
        )
        add("host_cover_b_600", hostCoverUrl("b", bookId, 600))
        add("host_cover_a", hostCoverUrl("a", bookId))
        add("host_cover_judian", hostCoverUrl("judian", bookId))
        add("qidian_cover_600", "https://bookcover.yuewen.com/qdbimg/349573/$bookId/600")
        add("qidian_cover_300", "https://bookcover.yuewen.com/qdbimg/349573/$bookId/300")
        return sources.map { it.value to it.key }
    }

    private fun hostCoverUrl(methodName: String, vararg args: Any): String? {
        val parameterTypes = args.map {
            when (it) {
                is Long -> Long::class.javaPrimitiveType!!
                is Int -> Int::class.javaPrimitiveType!!
                is Boolean -> Boolean::class.javaPrimitiveType!!
                else -> it.javaClass
            }
        }.toTypedArray()
        return runCatching {
            val method = if (args.size == 2 && args[0] is Long && args[1] is Int) {
                resolveMethod(BookCoverTargetKeys.coverUrl600)
            } else {
                classLoader.findHostClass(QDReaderHost.Classes.COVER_URL_HELPER)?.declaredMethodOrNull(methodName, *parameterTypes)
            }
            method?.let { FeatureHooks.invoke(it, null, *args) }
                as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun resolveMethod(key: TargetKey.MethodTarget): Method? {
        return HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = key,
        )
    }
}
