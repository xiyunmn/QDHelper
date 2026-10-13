package com.xiyunmn.qdhelper.feature.bookshield.stats

import com.xiyunmn.qdhelper.feature.bookshield.policy.ShieldReasonKind
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources

/**
 * One shielded-book record, captured at the moment a rule removed the book from a list.
 * Runtime/session scoped and in-memory only — never persisted to disk.
 */
data class ShieldEvent(
    val timestamp: Long,
    val location: String,
    val reasonKind: ShieldReasonKind,
    val reasonDetail: String,
    val bookId: Long?,
    val title: String?,
    val author: String?,
    val category: String?,
    val tags: String?,
    val words: Long?,
    val source: String,
)

/**
 * In-memory, process-shared statistics for book shielding. The shield filters run in the
 * host process on hook threads; the settings panel runs in the same process, so it reads
 * these snapshots directly — no disk churn on the hot filtering path, no IPC, no global
 * listener. Session-scoped: reset on host restart and on module hot-reload.
 */
object BookShieldStats {
    /** Cap the detailed ring buffer; older events roll off but still count toward totals. */
    private const val MAX_EVENTS = 400

    /**
     * A single feed load can re-filter the same book through overlapping adapters within a few
     * milliseconds (e.g. MorphingAdapter.updateList then a sub-adapter's setItems on the copied
     * data). Collapse those so the total and the list reflect distinct shield events, while still
     * keeping genuine re-appearances across refreshes (which land outside this window).
     */
    private const val DEDUP_WINDOW_MS = 2000L
    private const val DEDUP_SCAN = 24

    private val lock = Any()
    private val events = ArrayDeque<ShieldEvent>()
    private val locationCounts = LinkedHashMap<String, Long>()
    private val reasonCounts = LinkedHashMap<ShieldReasonKind, Long>()
    private var totalRemoved = 0L
    private var dropped = 0L
    private var sessionStart = 0L

    @Volatile
    private var mutation = 0L

    init {
        FeatureResources.register("BookShieldStats") { clear() }
    }

    fun record(event: ShieldEvent) {
        synchronized(lock) {
            val duplicateIndex = findRecentDuplicateIndexLocked(event)
            if (duplicateIndex != null) {
                val previous = events[duplicateIndex]
                val enriched = previous.copy(
                    bookId = previous.bookId?.takeIf { it > 0L } ?: event.bookId,
                    title = preferredText(previous.title, event.title),
                    author = preferredText(previous.author, event.author),
                    category = preferredText(previous.category, event.category),
                    tags = preferredText(previous.tags, event.tags),
                    words = previous.words?.takeIf { it > 0L } ?: event.words,
                )
                if (enriched != previous) {
                    events[duplicateIndex] = enriched
                    mutation++
                }
                return
            }
            if (sessionStart == 0L) sessionStart = event.timestamp
            events.addLast(event)
            while (events.size > MAX_EVENTS) {
                events.removeFirst()
                dropped++
            }
            locationCounts[event.location] = (locationCounts[event.location] ?: 0L) + 1L
            reasonCounts[event.reasonKind] = (reasonCounts[event.reasonKind] ?: 0L) + 1L
            totalRemoved++
            mutation++
        }
    }

    private fun findRecentDuplicateIndexLocked(event: ShieldEvent): Int? {
        val windowStart = event.timestamp - DEDUP_WINDOW_MS
        var scanned = 0
        var index = events.size - 1
        while (index >= 0 && scanned < DEDUP_SCAN) {
            val prev = events[index]
            if (prev.timestamp < windowStart) {
                break
            }
            val sameIdentity = if (prev.bookId != null && event.bookId != null) {
                prev.bookId == event.bookId
            } else {
                prev.title == event.title && prev.author == event.author
            }
            if (prev.location == event.location && sameIdentity && prev.reasonKind == event.reasonKind) {
                return index
            }
            scanned++
            index--
        }
        return null
    }

    private fun preferredText(primary: String?, fallback: String?): String? {
        return primary?.trim()?.takeIf(String::isNotEmpty)
            ?: fallback?.trim()?.takeIf(String::isNotEmpty)
    }

    /** Cheap signature for dialog-scoped polling: changes iff something was recorded or cleared. */
    fun mutationSignature(): Long = mutation

    fun snapshot(): Snapshot {
        synchronized(lock) {
            return Snapshot(
                totalRemoved = totalRemoved,
                retained = events.size,
                dropped = dropped,
                sessionStart = sessionStart,
                events = events.toList().asReversed(),
                locationCounts = LinkedHashMap(locationCounts),
                reasonCounts = LinkedHashMap(reasonCounts),
                mutation = mutation,
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            events.clear()
            locationCounts.clear()
            reasonCounts.clear()
            totalRemoved = 0L
            dropped = 0L
            sessionStart = 0L
            mutation++
        }
    }

    data class Snapshot(
        val totalRemoved: Long,
        val retained: Int,
        val dropped: Long,
        val sessionStart: Long,
        /** Newest first. */
        val events: List<ShieldEvent>,
        val locationCounts: Map<String, Long>,
        val reasonCounts: Map<ShieldReasonKind, Long>,
        val mutation: Long,
    )
}
