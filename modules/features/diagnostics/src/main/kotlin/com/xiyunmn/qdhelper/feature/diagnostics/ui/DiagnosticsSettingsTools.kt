package com.xiyunmn.qdhelper.feature.diagnostics.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xiyunmn.qdhelper.feature.diagnostics.cookie.CookieDebugInfo
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.host.api.gateway.ResolverDiagnosticsGateway
import com.xiyunmn.qdhelper.settings.api.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

internal class ResolverSettingsTool(private val gateway: ResolverDiagnosticsGateway) : SettingsToolController {
    override val refreshIntervalMillis = 1500L
    private val rescanning = AtomicBoolean(false)
    override fun read(context: Context): SettingsToolState {
        val snapshot = gateway.snapshot()
        return SettingsToolState(
            text = "已解析 ${snapshot.success} / ${snapshot.total}\n${snapshot.environment}\n指纹：${snapshot.fingerprint}\n" + (snapshot.unavailableReason ?: if (snapshot.prewarmAttempted) "已执行预热" else "尚未预热"),
            entries = snapshot.rules.map { SettingsToolEntry(it.id, (if (it.resolved) "已解析 · " else "未解析 · ") + it.id, "${it.purpose}\n匹配 ${it.count} 个${if (it.multiple) "（多个目标）" else ""}\n${it.matchedNames.joinToString("\n")}", if (it.resolved) SettingsToolStatus.Success else SettingsToolStatus.Warning) },
            commands = listOf(SettingsToolCommand("rescan", "重新解析")),
            status = when {
                snapshot.unavailableReason != null -> SettingsToolStatus.Error
                !snapshot.prewarmAttempted || snapshot.success < snapshot.total -> SettingsToolStatus.Warning
                else -> SettingsToolStatus.Success
            },
        )
    }
    override fun execute(activity: Activity, command: String, values: Map<String, String>, result: (SettingsToolResult) -> Unit): AutoCloseable? {
        if (!rescanning.compareAndSet(false, true)) { result(SettingsToolResult(message = "正在解析，请稍候")); return null }
        gateway.rescan(activity.classLoader) { hits, failures, reason -> rescanning.set(false); result(SettingsToolResult(message = reason?.let { "DexKit 不可用：$it" } ?: "解析完成：成功 $hits，失败 $failures")) }
        return null
    }
}

internal object CookieSettingsTool : SettingsToolController {
    private val worker = ManagedExecutor("feature.diagnostics.settings-cookie", "QDHelper-SettingsCookie")
    override val initialCommand = "load"
    override fun read(context: Context) = SettingsToolState("正在读取账号、设备身份与宿主诊断…")
    override fun execute(activity: Activity, command: String, values: Map<String, String>, result: (SettingsToolResult) -> Unit): AutoCloseable? {
        val host = WeakReference(activity)
        val app = activity.applicationContext
        val cancelled = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val accepted = worker.execute {
            runCatching { CookieDebugInfo.prepareHostCookieGetter(app) }
            handler.post {
                val current = host.get()
                if (!cancelled.get() && current != null && !current.isDestroyed && !current.isFinishing) {
                    val info = CookieDebugInfo.read(current)
                    result(SettingsToolResult(SettingsToolState(info.toDisplayText(), fullText = info.toFullText())))
                }
            }
        }
        if (!accepted) result(SettingsToolResult(SettingsToolState(error = "读取未启动，请重新打开设置")))
        return AutoCloseable { cancelled.set(true); host.clear(); handler.removeCallbacksAndMessages(null) }
    }
}
