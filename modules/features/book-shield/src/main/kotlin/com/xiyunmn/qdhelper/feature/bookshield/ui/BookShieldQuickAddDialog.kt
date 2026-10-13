package com.xiyunmn.qdhelper.feature.bookshield.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.bookshield.model.BookInfo
import com.xiyunmn.qdhelper.feature.bookshield.policy.BookListFilter
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.shared.compose.ModuleComposeContext
import com.xiyunmn.qdhelper.shared.compose.ModuleCompositionOwner
import com.xiyunmn.qdhelper.shared.compose.ModuleFloatingWindow
import com.xiyunmn.qdhelper.shared.compose.moduleWindowBrightness
import java.lang.ref.WeakReference

internal data class QuickShieldRule(val kind: Kind, val value: String) {
    enum class Kind(val label: String) { TITLE("完整书名"), AUTHOR("作者"), ID("Book ID") }
    fun present(context: Context): Boolean = when (kind) {
        Kind.TITLE -> BookListFilter.hasFullTitle(context, value)
        Kind.AUTHOR -> BookListFilter.hasAuthor(context, value)
        Kind.ID -> BookListFilter.hasBookId(context, value.toLong())
    }
    fun save(context: Context, remove: Boolean): Boolean = when (kind) {
        Kind.TITLE -> if (remove) BookListFilter.removeFullTitle(context, value) else BookListFilter.addFullTitle(context, value)
        Kind.AUTHOR -> if (remove) BookListFilter.removeAuthor(context, value) else BookListFilter.addAuthor(context, value)
        Kind.ID -> if (remove) BookListFilter.removeBookId(context, value.toLong()) else BookListFilter.addBookId(context, value.toLong())
    }
}

internal fun quickShieldRules(info: BookInfo): List<QuickShieldRule> = buildList {
    info.title?.trim()?.takeIf(String::isNotEmpty)?.let { add(QuickShieldRule(QuickShieldRule.Kind.TITLE, it)) }
    info.author?.trim()?.takeIf(String::isNotEmpty)?.let { add(QuickShieldRule(QuickShieldRule.Kind.AUTHOR, it)) }
    info.id?.takeIf { it > 0 }?.let { add(QuickShieldRule(QuickShieldRule.Kind.ID, it.toString())) }
}

internal object BookShieldQuickAddDialog {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ManagedExecutor("feature.book-shield.quick-add-dialog", "QDHelper-BookShieldQuickAdd")
    private var current = WeakReference<QuickShieldSession>(null)

    init { FeatureResources.register("book-shield-quick-add-dialog") {
        if (Looper.myLooper() == Looper.getMainLooper()) current.get()?.close()
        else main.post { current.get()?.close() }
    } }

    fun show(activity: Activity, info: BookInfo, source: View): QuickShieldSession? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val rules = quickShieldRules(info)
        if (rules.isEmpty()) { Toast.makeText(activity, "未读取到可屏蔽信息", Toast.LENGTH_SHORT).show(); return null }
        var session: QuickShieldSession? = null
        return try {
            current.get()?.close()
            session = QuickShieldSession(activity, rules, moduleWindowBrightness(source, activity))
            current = WeakReference(session)
            session.show()
            session
        } catch (error: Exception) {
            session?.close()
            FeatureHooks.w("Quick shield dialog unavailable: ${error.javaClass.simpleName}")
            Toast.makeText(activity, "无法打开快速屏蔽，请重试", Toast.LENGTH_SHORT).show()
            null
        }
    }

    fun change(session: QuickShieldSession, rule: QuickShieldRule, remove: Boolean) {
        // Accepted file writes may finish after dismissal; never retain the Activity in the worker.
        val context = session.activity.applicationContext
        val receiver = WeakReference(session)
        val accepted = worker.execute {
            val message = try {
                val saved = rule.save(context, remove)
                val enabled = !saved || remove || SettingsRuntime.putBoolean(context, SettingIds.KEY_BOOK_SHIELD_ENABLED, true)
                when {
                    !saved -> "保存失败"
                    !enabled -> "规则已加入，但启用书籍屏蔽失败"
                    remove -> "已移出屏蔽规则"
                    else -> "已加入屏蔽规则"
                }
            } catch (error: Exception) {
                FeatureHooks.w("Quick shield rule write failed: ${error.javaClass.simpleName}")
                "保存失败"
            }
            main.post { receiver.get()?.completed(message) }
        }
        if (!accepted) session.completed("保存未启动，请重新打开弹窗")
    }
}

internal class QuickShieldSession(val activity: Activity, val rules: List<QuickShieldRule>, brightness: Float) : AutoCloseable {
    private val owner = ModuleCompositionOwner()
    private val context = ModuleComposeContext.create(activity)
    internal val view = ComposeView(context)
    internal val dialog = ModuleFloatingWindow(context, brightness)
    private var subscription: AutoCloseable? = null
    private var disposed = false
    var active by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var membership by mutableStateOf(rules.map { it.present(activity.applicationContext) })
        private set

    fun show() {
        owner.attach(view)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        dialog.setContentView(view)
        owner.attach(dialog.window!!.decorView)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { dispose() }
        subscription = ActivityLifecycleCallbacks.subscribe(OwnerId("book-shield"), "book-shield.quick-add.window",
            resumed = { if (it === activity) resume() }, paused = { if (it === activity) pause() },
            destroyed = { if (it === activity) close() })
        view.setContent { QuickShieldContent(this) }
        dialog.show()
        resume()
    }

    fun refresh() { if (!disposed) membership = rules.map { it.present(activity.applicationContext) } }
    private fun resume() { if (!disposed) { active = true; refresh(); owner.resume() } }
    private fun pause() { if (!disposed) { active = false; owner.pause() } }
    fun change(index: Int) {
        if (disposed || busy) return
        refresh()
        busy = true
        BookShieldQuickAddDialog.change(this, rules[index], membership[index])
    }
    fun completed(message: String) {
        if (disposed || activity.isDestroyed || activity.isFinishing) return
        busy = false
        refresh()
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
    fun copy(index: Int) {
        if (disposed) return
        val rule = rules[index]
        val copied = runCatching {
            checkNotNull(activity.getSystemService(ClipboardManager::class.java))
                .setPrimaryClip(ClipData.newPlainText(rule.kind.label, rule.value))
        }.isSuccess
        Toast.makeText(activity, if (copied) "已复制${rule.kind.label}" else "复制失败", Toast.LENGTH_SHORT).show()
    }
    override fun close() {
        dialog.dismissImmediately()
        dispose()
    }
    fun dismiss() { dialog.dismiss() }
    private fun dispose() {
        if (disposed) return
        disposed = true
        active = false
        subscription?.close()
        subscription = null
        owner.close()
        view.disposeComposition()
    }
}
