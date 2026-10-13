package com.xiyunmn.qdhelper.feature.bookshield.settings

import android.app.Activity
import android.content.Context
import com.xiyunmn.qdhelper.config.BookShieldRuleOperationResult
import com.xiyunmn.qdhelper.config.BookShieldRuleRepository
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.settings.api.SettingsDocumentActionResult
import com.xiyunmn.qdhelper.settings.api.SettingsDocumentController
import com.xiyunmn.qdhelper.settings.api.SettingsDocumentState
import java.lang.ref.WeakReference

internal object BookShieldDocumentController : SettingsDocumentController {
    private const val EXTERNAL_CHANGE_MESSAGE = "规则已在其他入口更新并生效；当前未保存草稿仍保留，保存会覆盖最新规则。"
    private val owner = OwnerId("book-shield")
    private val worker = ManagedExecutor(
        ownerKey = "feature.book-shield.document",
        threadName = "QDHelper-BookShieldDocument",
    )
    private val lock = Any()
    private val writes = Any()
    private val editVersion = DocumentEditVersion()

    private var draftText: String? = null
    private var draftRevision = -1L
    private var dirty = false
    private var localError: String? = null
    private var pendingExternal: PendingExternal? = null
    private var lifecycleSubscription: AutoCloseable? = null

    init {
        FeatureResources.register("book-shield-document-controller") { clearRuntimeState() }
    }

    override fun state(context: Context): SettingsDocumentState {
        BookShieldRuleRepository.initialize(context)
        val status = BookShieldRuleRepository.status(context)
        val snapshot = BookShieldRuleRepository.runtimeSnapshot(context)
        val repositoryText = BookShieldRuleRepository.text(context)
        val viewState = synchronized(lock) {
            if (!dirty && draftRevision != snapshot.revision) {
                draftText = repositoryText
                draftRevision = snapshot.revision
                if (status.error == null) localError = null
            } else if (dirty && draftRevision != snapshot.revision) {
                draftRevision = snapshot.revision
                if (localError == null) localError = EXTERNAL_CHANGE_MESSAGE
            }
            ControllerViewState(
                text = draftText ?: repositoryText.also {
                    draftText = it
                    draftRevision = snapshot.revision
                },
                dirty = dirty,
                error = localError,
            )
        }
        return SettingsDocumentState(
            text = viewState.text,
            sourceLabel = "持久文件：${BookShieldRuleRepository.managedFile(context).absolutePath}",
            sourcePath = BookShieldRuleRepository.managedFile(context).absolutePath,
            errorMessage = viewState.error ?: status.error?.displayMessage(),
            ruleCount = status.ruleCount,
            dirty = viewState.dirty,
            ruleBreakdown = with(snapshot.config) { linkedMapOf(
                "作者" to authors.size, "关键词" to titleKeywords.size, "完整书名" to fullTitles.size,
                "分类" to types.size, "书籍 ID" to bookIds.size,
                "字数条件" to listOfNotNull(wordsAtMost, wordsAtLeast).size,
            ) },
        )
    }

    override fun updateDraft(text: String) {
        synchronized(lock) {
            editVersion.edited()
            draftText = text
            dirty = true
            if (localError != EXTERNAL_CHANGE_MESSAGE) localError = null
        }
    }

    override fun save(activity: Activity, onResult: (SettingsDocumentActionResult) -> Unit) {
        val (draft, ticket) = synchronized(lock) {
            (draftText ?: BookShieldRuleRepository.text(activity)) to editVersion.capture()
        }
        val context = activity.applicationContext
        BookShieldSessionInputs.beginAuthoritativeChange()
        val accepted = worker.execute {
            synchronized(writes) {
                if (!synchronized(lock) { editVersion.belongsToDocument(ticket) }) {
                    deliver(activity, onResult, false, "保存已取消：规则已重置")
                    return@synchronized
                }
                when (val result = BookShieldRuleRepository.saveText(context, draft)) {
                    is BookShieldRuleOperationResult.Success -> {
                        acceptSuccess(context, result, replaceDraft = true, ticket = ticket)
                        deliver(activity, onResult, true, "规则文件已保存并生效")
                    }
                    is BookShieldRuleOperationResult.Failure -> {
                        acceptFailure(result, replaceDraft = false, ticket = ticket)
                        deliver(activity, onResult, false, "保存失败：${result.error.displayMessage()}")
                    }
                }
            }
        }
        if (!accepted) deliver(activity, onResult, false, "保存未启动，请重新打开设置")
    }

    override fun openExternalEditor(activity: Activity, onResult: (SettingsDocumentActionResult) -> Unit) {
        if (synchronized(lock) { pendingExternal != null }) {
            onResult(SettingsDocumentActionResult(false, "已有外部编辑操作正在进行"))
            return
        }
        val (draft, ticket) = synchronized(lock) {
            (draftText ?: BookShieldRuleRepository.text(activity)) to editVersion.capture()
        }
        val session = BookShieldExternalEditor.createSession(activity, draft).getOrElse { throwable ->
            onResult(
                SettingsDocumentActionResult(
                    false,
                    "无法准备外部编辑文件：${throwable.message ?: throwable.javaClass.simpleName}",
                ),
            )
            return
        }
        ensureLifecycleMonitoring()
        val accepted = synchronized(lock) {
            if (pendingExternal == null) {
                pendingExternal = PendingExternal(WeakReference(activity), onResult, session, ticket)
                true
            } else {
                false
            }
        }
        if (!accepted) {
            BookShieldExternalEditor.cleanup(activity, session)
            onResult(SettingsDocumentActionResult(false, "已有外部编辑操作正在进行"))
            return
        }
        runCatching { activity.startActivity(BookShieldExternalEditor.editIntent(activity, session)) }
            .onFailure { throwable ->
                synchronized(lock) {
                    if (pendingExternal?.session == session) pendingExternal = null
                }
                BookShieldExternalEditor.cleanup(activity, session)
                onResult(SettingsDocumentActionResult(false, "无法打开文件编辑器：${throwable.message}"))
            }
    }

    fun reset(context: Context): Boolean {
        synchronized(lock) { editVersion.invalidate() }
        return synchronized(writes) { resetDocument(context) }
    }

    private fun resetDocument(context: Context): Boolean {
        BookShieldSessionInputs.beginAuthoritativeChange()
        return when (val result = BookShieldRuleRepository.reset(context)) {
            is BookShieldRuleOperationResult.Success -> {
                BookShieldSessionInputs.clear()
                acceptSuccess(context, result, replaceDraft = true)
                true
            }
            is BookShieldRuleOperationResult.Failure -> {
                acceptFailure(result, replaceDraft = false)
                false
            }
        }
    }

    private fun ensureLifecycleMonitoring() {
        val needsLifecycle = synchronized(lock) { lifecycleSubscription == null }
        if (!needsLifecycle) return
        val subscription = ActivityLifecycleCallbacks.subscribe(
            owner = owner,
            id = "book-shield.document.monitor",
            priority = 200,
            resumed = { activity -> onHostResumed(activity) },
        ) ?: return
        synchronized(lock) {
            if (lifecycleSubscription == null) lifecycleSubscription = subscription else subscription.close()
        }
    }

    private fun onHostResumed(activity: Activity) {
        var abandoned: PendingExternal? = null
        val external = synchronized(lock) {
            val pending = pendingExternal
            val ownerActivity = pending?.activity?.get()
            when {
                pending == null -> null
                ownerActivity === activity -> pending.also { pendingExternal = null }
                ownerActivity == null || ownerActivity.isFinishing || ownerActivity.isDestroyed -> {
                    pendingExternal = null
                    abandoned = pending
                    null
                }
                else -> null
            }
        }
        abandoned?.let { BookShieldExternalEditor.cleanup(activity, it.session) }
        if (external != null) {
            if (!worker.execute { finishExternalEdit(activity, external) }) {
                BookShieldExternalEditor.cleanup(activity, external.session)
                deliver(activity, external.callback, false, "读取已取消，请重新打开设置")
            }
        }
    }

    private fun finishExternalEdit(activity: Activity, pending: PendingExternal) {
        val text = BookShieldExternalEditor.readText(pending.session).getOrElse { throwable ->
            BookShieldExternalEditor.cleanup(activity, pending.session)
            deliver(
                activity,
                pending.callback,
                false,
                "读取外部编辑结果失败：${throwable.message ?: throwable.javaClass.simpleName}",
            )
            return
        }
        BookShieldExternalEditor.cleanup(activity, pending.session)
        if (text == pending.session.originalText) {
            deliver(activity, pending.callback, true, "未检测到外部修改")
            return
        }
        synchronized(writes) {
            val ticket = synchronized(lock) {
                if (!editVersion.canReplaceDraft(pending.ticket)) null else {
                    editVersion.edited()
                    draftText = text
                    dirty = true
                    localError = null
                    editVersion.capture()
                }
            }
            if (ticket == null) {
                deliver(activity, pending.callback, false, "规则或草稿已更新，未覆盖为旧的外部编辑结果")
                return
            }
            BookShieldSessionInputs.beginAuthoritativeChange()
            when (val result = BookShieldRuleRepository.saveText(activity.applicationContext, text)) {
                is BookShieldRuleOperationResult.Success -> {
                    acceptSuccess(activity, result, replaceDraft = true, ticket = ticket)
                    deliver(activity, pending.callback, true, "外部修改已校验并生效")
                }
                is BookShieldRuleOperationResult.Failure -> {
                    acceptFailure(result, replaceDraft = false, ticket = ticket)
                    deliver(activity, pending.callback, false, "外部修改未生效：${result.error.displayMessage()}")
                }
            }
        }
    }

    private fun acceptSuccess(
        context: Context,
        result: BookShieldRuleOperationResult.Success,
        replaceDraft: Boolean,
        ticket: DocumentEditVersion.Ticket? = null,
    ) {
        val revision = BookShieldRuleRepository.runtimeSnapshot(context).revision
        synchronized(lock) {
            if (ticket != null && !editVersion.belongsToDocument(ticket)) return
            if ((ticket == null || editVersion.canReplaceDraft(ticket)) && (replaceDraft || !dirty)) {
                draftText = result.text
                draftRevision = revision
                dirty = false
                localError = null
            } else {
                localError = EXTERNAL_CHANGE_MESSAGE
            }
        }
        BookShieldSessionInputs.reconcile(context)
    }

    private fun acceptFailure(result: BookShieldRuleOperationResult.Failure, replaceDraft: Boolean, ticket: DocumentEditVersion.Ticket? = null) {
        synchronized(lock) {
            if (ticket != null && !editVersion.canReplaceDraft(ticket)) return
            if ((replaceDraft || !dirty) && result.candidateText != null) {
                draftText = result.candidateText
                dirty = true
            }
            localError = result.error.displayMessage()
        }
    }

    private fun clearRuntimeState() {
        val external = synchronized(lock) {
            editVersion.invalidate()
            lifecycleSubscription?.close()
            lifecycleSubscription = null
            val pending = pendingExternal
            pendingExternal = null
            draftText = null
            draftRevision = -1L
            dirty = false
            localError = null
            pending
        }
        external?.let { BookShieldExternalEditor.discard(it.session) }
    }

    private fun deliver(
        activity: Activity,
        callback: (SettingsDocumentActionResult) -> Unit,
        success: Boolean,
        message: String,
    ) {
        activity.runOnUiThread { callback(SettingsDocumentActionResult(success, message)) }
    }

    private data class PendingExternal(
        val activity: WeakReference<Activity>,
        val callback: (SettingsDocumentActionResult) -> Unit,
        val session: BookShieldExternalEditSession,
        val ticket: DocumentEditVersion.Ticket,
    )

    private data class ControllerViewState(
        val text: String,
        val dirty: Boolean,
        val error: String?,
    )

}
