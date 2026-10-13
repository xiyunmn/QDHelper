package com.xiyunmn.qdhelper.feature.bookshield.settings

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.xiyunmn.qdhelper.config.BookShieldRuleConfig
import com.xiyunmn.qdhelper.config.BookShieldRuleField
import com.xiyunmn.qdhelper.config.BookShieldRuleOperationResult
import com.xiyunmn.qdhelper.config.BookShieldRuleRepository
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import java.lang.ref.WeakReference

internal object BookShieldSessionInputs {
    private const val WRITE_DELAY_MS = 350L
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = ManagedExecutor(
        ownerKey = "feature.book-shield.session-input",
        threadName = "QDHelper-BookShieldInput",
    )
    private val entries = BookShieldRuleField.entries.associateWith { SessionEntry() }.toMutableMap()
    private val pending = mutableMapOf<BookShieldRuleField, Runnable>()
    private var lastSessionRevision = -1L

    init {
        FeatureResources.register("book-shield-session-inputs") { clear() }
    }

    fun value(context: Context, field: BookShieldRuleField): String {
        reconcileIfIdle(context)
        return synchronized(lock) { entries.getValue(field).rawText }
    }

    fun update(activity: Activity, field: BookShieldRuleField, rawText: String) {
        val baseRevision = BookShieldRuleRepository.runtimeSnapshot(activity).revision
        val generation = synchronized(lock) {
            val entry = entries.getValue(field)
            entry.rawText = rawText
            entry.generation += 1L
            entry.baseRevision = baseRevision
            pending.remove(field)?.let(mainHandler::removeCallbacks)
            entry.generation
        }
        val application = activity.applicationContext ?: activity
        val activityRef = WeakReference(activity)
        val runnable = Runnable {
            synchronized(lock) { pending.remove(field) }
            worker.execute { applyLatest(application, field, generation, activityRef) }
        }
        synchronized(lock) { pending[field] = runnable }
        mainHandler.postDelayed(runnable, WRITE_DELAY_MS)
    }

    fun reconcile(context: Context) {
        val snapshot = BookShieldRuleRepository.runtimeSnapshot(context)
        reconcile(snapshot.config, snapshot.revision, force = true)
    }

    fun beginAuthoritativeChange() {
        synchronized(lock) {
            pending.values.forEach(mainHandler::removeCallbacks)
            pending.clear()
            entries.values.forEach { entry ->
                entry.generation += 1L
            }
            lastSessionRevision = -1L
        }
    }

    fun clear() {
        synchronized(lock) {
            pending.values.forEach(mainHandler::removeCallbacks)
            pending.clear()
            entries.values.forEach(SessionEntry::clear)
            lastSessionRevision = -1L
        }
    }

    private fun reconcileIfIdle(context: Context) {
        val snapshot = BookShieldRuleRepository.runtimeSnapshot(context)
        val shouldReconcile = synchronized(lock) {
            snapshot.revision != lastSessionRevision && pending.isEmpty() && entries.values.none { it.inFlight }
        }
        if (shouldReconcile) reconcile(snapshot.config, snapshot.revision, force = false)
    }

    private fun reconcile(config: BookShieldRuleConfig, revision: Long, force: Boolean) {
        synchronized(lock) {
            if (!force && (pending.isNotEmpty() || entries.values.any { it.inFlight })) return
            BookShieldRuleField.entries.forEach { field ->
                pending.remove(field)?.let(mainHandler::removeCallbacks)
                val entry = entries.getValue(field)
                entry.generation += 1L
                val reconciled = reconcileSessionInput(config, field, entry.rawText, entry.ownedIdentities)
                entry.rawText = reconciled.rawText
                entry.desiredTokens = reconciled.tokens
                entry.appliedTokens = reconciled.tokens
                entry.ownedIdentities.clear()
                entry.ownedIdentities.addAll(reconciled.ownedIdentities)
                entry.baseRevision = revision
            }
            lastSessionRevision = revision
        }
    }

    private fun applyLatest(
        context: Context,
        field: BookShieldRuleField,
        requestedGeneration: Long,
        activityRef: WeakReference<Activity>,
    ) {
        val before = synchronized(lock) {
            val entry = entries.getValue(field)
            if (entry.generation != requestedGeneration) return
            val desired = parseSessionTokens(field, entry.rawText)
            entry.desiredTokens = desired
            entry.inFlight = true
            ScheduledSessionUpdate(
                snapshot = SessionSnapshot(entry.appliedTokens, desired, entry.ownedIdentities.toSet()),
                baseRevision = entry.baseRevision,
            )
        }
        var applied: SessionDeltaResult? = null
        val result = BookShieldRuleRepository.update(context) { current ->
            val currentRevision = BookShieldRuleRepository.runtimeSnapshot().revision
            val allowed = synchronized(lock) {
                val entry = entries.getValue(field)
                entry.generation == requestedGeneration &&
                    (currentRevision == before.baseRevision || currentRevision == lastSessionRevision)
            }
            if (!allowed) current else applySessionDelta(current, field, before.snapshot).also { applied = it }.config
        }
        val latestRevision = BookShieldRuleRepository.runtimeSnapshot(context).revision
        var reconcileAfterSuccess = false
        when (result) {
            is BookShieldRuleOperationResult.Success -> synchronized(lock) {
                val entry = entries.getValue(field)
                entry.inFlight = false
                applied?.let { delta ->
                    entry.appliedTokens = before.snapshot.desired
                    entry.ownedIdentities.clear()
                    entry.ownedIdentities.addAll(delta.ownedIdentities)
                    lastSessionRevision = latestRevision
                } ?: run {
                    reconcileAfterSuccess = pending.isEmpty() && entry.generation == requestedGeneration
                }
            }
            is BookShieldRuleOperationResult.Failure -> {
                val showError = synchronized(lock) {
                    val entry = entries.getValue(field)
                    entry.inFlight = false
                    entry.generation == requestedGeneration
                }
                if (showError) activityRef.get()?.let { activity ->
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "规则保存失败：${result.error.displayMessage()}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        if (reconcileAfterSuccess) reconcile(context)
    }

    private data class SessionEntry(
        var rawText: String = "",
        var desiredTokens: List<SessionToken> = emptyList(),
        var appliedTokens: List<SessionToken> = emptyList(),
        val ownedIdentities: MutableSet<String> = linkedSetOf(),
        var generation: Long = 0L,
        var baseRevision: Long = -1L,
        var inFlight: Boolean = false,
    ) {
        fun clear() {
            rawText = ""
            desiredTokens = emptyList()
            appliedTokens = emptyList()
            ownedIdentities.clear()
            generation += 1L
            baseRevision = -1L
            inFlight = false
        }
    }

    private data class ScheduledSessionUpdate(
        val snapshot: SessionSnapshot,
        val baseRevision: Long,
    )
}

internal data class SessionToken(val display: String, val identity: String)

internal data class SessionSnapshot(
    val previous: List<SessionToken>,
    val desired: List<SessionToken>,
    val ownedIdentities: Set<String>,
)

internal data class SessionDeltaResult(
    val config: BookShieldRuleConfig,
    val ownedIdentities: Set<String>,
)

internal data class SessionReconcileResult(
    val rawText: String,
    val tokens: List<SessionToken>,
    val ownedIdentities: Set<String>,
)

internal fun reconcileSessionInput(
    config: BookShieldRuleConfig,
    field: BookShieldRuleField,
    rawText: String,
    ownedIdentities: Set<String>,
): SessionReconcileResult {
    val canonicalIds = sessionTokensFor(config, field).mapTo(linkedSetOf(), SessionToken::identity)
    val kept = parseSessionTokens(field, rawText).filter { it.identity in canonicalIds }
    val keptIds = kept.mapTo(linkedSetOf(), SessionToken::identity)
    return SessionReconcileResult(
        rawText = kept.joinToString("\n") { it.display },
        tokens = kept,
        ownedIdentities = ownedIdentities.intersect(keptIds),
    )
}

internal fun parseSessionTokens(field: BookShieldRuleField, value: String): List<SessionToken> {
    val seen = linkedSetOf<String>()
    // 文本字段一行一条（书名里的逗号、空格是内容）；书号不含这些字符，保留宽松分隔。
    val segments = if (field == BookShieldRuleField.BOOK_IDS) {
        BookShieldRuleConfig.splitQuickInput(value)
    } else {
        BookShieldRuleConfig.splitLines(value)
    }
    return segments.mapNotNull { display ->
        when (field) {
            BookShieldRuleField.BOOK_IDS -> display.toLongOrNull()?.takeIf { it > 0L }?.let { id ->
                SessionToken(id.toString(), id.toString())
            }
            else -> BookShieldRuleConfig.normalizeIdentity(display).takeIf(String::isNotEmpty)?.let { identity ->
                SessionToken(display, identity)
            }
        }
    }.filter { seen.add(it.identity) }
}

private fun sessionTokensFor(config: BookShieldRuleConfig, field: BookShieldRuleField): List<SessionToken> = when (field) {
    BookShieldRuleField.AUTHORS -> config.authors.toSessionTokens()
    BookShieldRuleField.TITLE_KEYWORDS -> config.titleKeywords.toSessionTokens()
    BookShieldRuleField.FULL_TITLES -> config.fullTitles.toSessionTokens()
    BookShieldRuleField.TYPES -> config.types.toSessionTokens()
    BookShieldRuleField.BOOK_IDS -> config.bookIds.map { SessionToken(it.toString(), it.toString()) }
}

private fun List<String>.toSessionTokens(): List<SessionToken> = map {
    SessionToken(it, BookShieldRuleConfig.normalizeIdentity(it))
}

internal fun applySessionDelta(
    config: BookShieldRuleConfig,
    field: BookShieldRuleField,
    session: SessionSnapshot,
): SessionDeltaResult {
    val previousIds = session.previous.mapTo(linkedSetOf(), SessionToken::identity)
    val desiredIds = session.desired.mapTo(linkedSetOf(), SessionToken::identity)
    val removable = (previousIds - desiredIds).intersect(session.ownedIdentities)
    val current = when (field) {
        BookShieldRuleField.AUTHORS -> config.authors.map { SessionToken(it, BookShieldRuleConfig.normalizeIdentity(it)) }
        BookShieldRuleField.TITLE_KEYWORDS -> config.titleKeywords.map { SessionToken(it, BookShieldRuleConfig.normalizeIdentity(it)) }
        BookShieldRuleField.FULL_TITLES -> config.fullTitles.map { SessionToken(it, BookShieldRuleConfig.normalizeIdentity(it)) }
        BookShieldRuleField.TYPES -> config.types.map { SessionToken(it, BookShieldRuleConfig.normalizeIdentity(it)) }
        BookShieldRuleField.BOOK_IDS -> config.bookIds.map { SessionToken(it.toString(), it.toString()) }
    }.filterNot { it.identity in removable }.toMutableList()

    val present = current.mapTo(linkedSetOf(), SessionToken::identity)
    val owned = session.ownedIdentities.toMutableSet().apply { removeAll(removable) }
    session.desired.forEach { token ->
        if (present.add(token.identity)) {
            current += token
            owned += token.identity
        }
    }
    val next = when (field) {
        BookShieldRuleField.AUTHORS -> config.copy(authors = current.map(SessionToken::display))
        BookShieldRuleField.TITLE_KEYWORDS -> config.copy(titleKeywords = current.map(SessionToken::display))
        BookShieldRuleField.FULL_TITLES -> config.copy(fullTitles = current.map(SessionToken::display))
        BookShieldRuleField.TYPES -> config.copy(types = current.map(SessionToken::display))
        BookShieldRuleField.BOOK_IDS -> config.copy(bookIds = current.mapNotNull { it.display.toLongOrNull() })
    }
    return SessionDeltaResult(next, owned)
}
