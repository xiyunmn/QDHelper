package com.xiyunmn.qdhelper.host.api.gateway

data class BookCover(
    val bytes: ByteArray,
    val mediaType: String,
    val extension: String,
)

interface BookCoverGateway {
    fun loadCover(bookId: String): BookCover?
}

fun interface NativeNetResponseTransformer {
    fun transform(path: String, response: String): String
}

interface NativeNetBridgeRegistry {
    fun register(ownerId: String, priority: Int, transformer: NativeNetResponseTransformer): AutoCloseable
}

class NativeNetTransformerChain(
    private val onFailure: (ownerId: String, throwable: Throwable) -> Unit = { _, _ -> },
) : NativeNetBridgeRegistry {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    override fun register(
        ownerId: String,
        priority: Int,
        transformer: NativeNetResponseTransformer,
    ): AutoCloseable {
        require(ownerId.isNotBlank())
        val entry = Entry(ownerId, priority, transformer)
        entries[ownerId] = entry
        return AutoCloseable { entries.remove(ownerId, entry) }
    }

    fun transform(path: String, response: String): String {
        return entries.values.sortedByDescending { it.priority }.fold(response) { current, entry ->
            runCatching { entry.transformer.transform(path, current) }.getOrElse { throwable ->
                onFailure(entry.ownerId, throwable)
                current
            }
        }
    }

    fun clear() = entries.clear()

    private data class Entry(
        val ownerId: String,
        val priority: Int,
        val transformer: NativeNetResponseTransformer,
    )
}
