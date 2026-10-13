package com.xiyunmn.qdhelper.feature.account

internal data class AutoInvestDetailSnapshot(
    val bookId: Long,
    val bookInvestStatus: Int,
    val userInvestStatus: Int,
    val todayReadSeconds: Long?,
    val restCount: Int,
)

internal enum class AutoInvestEligibility {
    READY,
    BUSY,
    NEED_MORE_READING,
    NO_CAPACITY,
    ALREADY_INVESTED,
    CLOSED,
    INVALID,
}

internal object AutoInvestPolicy {
    const val REQUIRED_READ_SECONDS = 600L
    const val MAX_COUNTED_SESSION_MILLIS = REQUIRED_READ_SECONDS * 1_000L

    // Audited host semantics: 1 is active, 2 is completed, and 3 is failed. Requiring the explicit
    // active value prevents a default-filled response for a non-investment book from becoming READY.
    fun evaluate(details: AutoInvestDetailSnapshot): AutoInvestEligibility = when {
        details.bookId <= 0L || details.todayReadSeconds == null || details.todayReadSeconds < 0L ||
            details.bookInvestStatus !in 1..3 || details.userInvestStatus < 0 || details.restCount < 0 ->
            AutoInvestEligibility.INVALID
        details.userInvestStatus != 0 -> AutoInvestEligibility.ALREADY_INVESTED
        details.bookInvestStatus != 1 -> AutoInvestEligibility.CLOSED
        details.restCount == 0 -> AutoInvestEligibility.NO_CAPACITY
        details.todayReadSeconds >= REQUIRED_READ_SECONDS -> AutoInvestEligibility.READY
        else -> AutoInvestEligibility.NEED_MORE_READING
    }

    fun localDayKey(nowMillis: Long): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(nowMillis))

    fun sumReportedReadMillis(values: Iterable<Long>): Long = values.fold(0L) { total, value ->
        val positive = value.coerceAtLeast(0L)
        if (Long.MAX_VALUE - total < positive) Long.MAX_VALUE else total + positive
    }
}

internal data class AutoInvestQueryReservation(
    val userId: Long,
    val bookId: Long,
    val reason: String,
)

/** Process-local, bounded and deliberately one-shot state machine. */
internal class AutoInvestStateTracker(
    private val maxTrackedBooks: Int = 32,
) {
    private val books = LinkedHashMap<Long, BookState>(16, 0.75f, true)
    private val queriedBookIds = HashSet<Long>()
    private var activeUserId: Long = 0L

    /** Starts the sole investment-detail query only after local DB rows are reported upstream. */
    @Synchronized
    fun observeReportedRead(userId: Long, bookId: Long, reportedReadMillis: Long): AutoInvestQueryReservation? {
        if (userId <= 0L || bookId <= 0L || reportedReadMillis < AutoInvestPolicy.MAX_COUNTED_SESSION_MILLIS) {
            return null
        }
        switchUserIfNeeded(userId)
        if (!queriedBookIds.add(bookId)) return null
        books[bookId] = BookState(Phase.QUERYING)
        trimToBound()
        return AutoInvestQueryReservation(userId, bookId, "reported-read-threshold")
    }

    @Synchronized
    fun onDetails(
        userId: Long,
        requestedBookId: Long,
        details: AutoInvestDetailSnapshot,
    ): AutoInvestEligibility? {
        if (userId != activeUserId) return null
        val state = books[requestedBookId] ?: return null
        if (state.phase != Phase.QUERYING) return null
        if (details.bookId != requestedBookId) {
            state.phase = Phase.DONE
            return AutoInvestEligibility.INVALID
        }
        val eligibility = AutoInvestPolicy.evaluate(details)
        val anotherInvestmentReserved = books.any { (bookId, candidate) ->
            bookId != requestedBookId && candidate.phase == Phase.INVESTING
        }
        if (eligibility == AutoInvestEligibility.READY && anotherInvestmentReserved) {
            state.phase = Phase.DONE
            return AutoInvestEligibility.BUSY
        }
        state.phase = if (eligibility == AutoInvestEligibility.READY) Phase.INVESTING else Phase.DONE
        return eligibility
    }

    @Synchronized
    fun onDetailFailure(userId: Long, bookId: Long) {
        if (userId != activeUserId) return
        val state = books[bookId] ?: return
        if (state.phase != Phase.QUERYING) return
        state.phase = Phase.DONE
    }

    @Synchronized
    fun isInvestmentReserved(userId: Long, bookId: Long): Boolean =
        userId == activeUserId && books[bookId]?.phase == Phase.INVESTING

    @Synchronized
    fun onInvestmentSucceeded(userId: Long, bookId: Long) {
        if (userId == activeUserId) books[bookId]?.phase = Phase.DONE
    }

    @Synchronized
    fun onInvestmentDeferred(userId: Long, bookId: Long) {
        if (userId != activeUserId) return
        books[bookId]?.takeIf { it.phase == Phase.INVESTING }?.phase = Phase.ABORTED
    }

    @Synchronized
    fun onInvestmentFailed(userId: Long, bookId: Long, uncertain: Boolean) {
        if (userId != activeUserId) return
        books[bookId]?.takeIf { it.phase == Phase.INVESTING }
            ?.let {
                if (uncertain) {
                    it.phase = Phase.UNCERTAIN
                } else {
                    it.phase = Phase.DONE
                }
            }
    }

    @Synchronized
    fun onInvestmentAborted(userId: Long, bookId: Long) {
        if (userId == activeUserId) {
            books[bookId]?.takeIf { it.phase == Phase.INVESTING }?.phase = Phase.ABORTED
        }
    }

    @Synchronized
    fun clear() {
        activeUserId = 0L
        books.clear()
        queriedBookIds.clear()
    }

    private fun switchUserIfNeeded(userId: Long) {
        if (activeUserId == userId) return
        activeUserId = userId
        books.clear()
        queriedBookIds.clear()
    }

    private fun trimToBound() {
        while (books.size > maxTrackedBooks) {
            val removable = books.entries.firstOrNull { (_, state) ->
                state.phase != Phase.QUERYING && state.phase != Phase.INVESTING
            } ?: return
            books.remove(removable.key)
        }
    }

    private class BookState(var phase: Phase)

    private enum class Phase {
        QUERYING,
        INVESTING,
        ABORTED,
        UNCERTAIN,
        DONE,
    }
}
