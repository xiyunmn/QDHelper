package com.xiyunmn.qdhelper.feature.contenttools.media

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.shared.compose.ModuleComposeContext
import com.xiyunmn.qdhelper.shared.compose.ModuleCompositionOwner
import com.xiyunmn.qdhelper.shared.compose.ModuleFloatingWindow
import com.xiyunmn.qdhelper.shared.compose.moduleWindowBrightness
import com.xiyunmn.qdhelper.shared.io.HttpStatusException
import com.xiyunmn.qdhelper.shared.io.SizeLimitExceededException
import com.xiyunmn.qdhelper.shared.media.ImageGalleryStore
import com.xiyunmn.qdhelper.shared.media.RemoteImageDownloader
import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal data class StickerExportItem(val name: String, val url: String)

internal object MediaExportPanel {
    internal val main = Handler(Looper.getMainLooper())
    private var current = WeakReference<MediaExportSession>(null)

    init { FeatureResources.register("content-tools.media-export-panel", ::closeAll) }

    fun showStickers(activity: Activity, source: View, title: String, items: List<StickerExportItem>, enabled: () -> Boolean) {
        show(activity, source, title, items.toList(), null, enabled)
    }

    fun showVoice(activity: Activity, source: View, audioUrl: String, enabled: () -> Boolean) {
        show(activity, source, "导出章评语音", emptyList(), audioUrl, enabled)
    }

    private fun show(activity: Activity, source: View, title: String, items: List<StickerExportItem>, audioUrl: String?, enabled: () -> Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(activity, source, title, items, audioUrl, enabled) }
            return
        }
        if (activity.isFinishing || activity.isDestroyed || !enabled()) return
        if (current.get()?.open == true) {
            Toast.makeText(activity, "请先关闭当前导出窗口", Toast.LENGTH_SHORT).show()
            return
        }
        var session: MediaExportSession? = null
        try {
            session = MediaExportSession(activity, source, title, items, audioUrl, enabled)
            current = WeakReference(session)
            session.show()
        } catch (error: Exception) {
            session?.close()
            FeatureHooks.w("Media export panel failed: ${error.javaClass.simpleName}")
            Toast.makeText(activity, "暂时无法打开导出窗口", Toast.LENGTH_SHORT).show()
        }
    }

    fun closeAll() {
        if (Looper.myLooper() == Looper.getMainLooper()) current.get()?.close()
        else main.post { current.get()?.close() }
    }
}

internal class MediaExportSession(
    val activity: Activity, source: View, val title: String, val items: List<StickerExportItem>,
    val audioUrl: String?, private val enabled: () -> Boolean,
) : AutoCloseable {
    private val owner = ModuleCompositionOwner()
    private val context = ModuleComposeContext.create(activity)
    private val view = ComposeView(context)
    private val dialog = ModuleFloatingWindow(context, moduleWindowBrightness(source, activity), source)
    private val cancelled = AtomicBoolean(false)
    private val worker = ManagedExecutor("content-tools.media-export", "QDHelper-MediaExport")
    private var subscription: AutoCloseable? = null
    @Volatile var open = true
        private set
    var active by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var started by mutableStateOf(false)
        private set
    var ready by mutableStateOf(audioUrl != null || items.isNotEmpty())
        private set
    var filename by mutableStateOf("章评语音_${System.currentTimeMillis()}")
    var status by mutableStateOf(if (audioUrl == null) "共 ${items.size} 张，点击导出后开始下载" else "点击导出后下载，保存到 Music/QDHelper")
        private set
    var completed by mutableIntStateOf(0)
        private set
    var succeeded by mutableIntStateOf(0)
        private set
    var failures by mutableStateOf<List<String>>(emptyList())
        private set

    fun show() {
        owner.attach(view)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        dialog.setContentView(view)
        owner.attach(dialog.window!!.decorView)
        dialog.onDismissStarted = { cancelled.set(true); worker.close() }
        dialog.setOnDismissListener { dispose() }
        subscription = ActivityLifecycleCallbacks.subscribe(OwnerId("content-tools"), "content-tools.media-export.window",
            resumed = { if (it === activity && open) { active = true; owner.resume() } },
            paused = { if (it === activity && open) { active = false; owner.pause() } },
            destroyed = { if (it === activity) close() })
        view.setContent { MediaExportContent(this) }
        dialog.show()
        active = true
        owner.resume()
    }

    fun start() {
        if (!open || busy || started || !ready) return
        if (!enabled()) { ready = false; status = "功能已关闭"; return }
        busy = true
        started = true
        status = "正在导出…"
        val app = activity.applicationContext
        val receiver = WeakReference(this)
        val token = cancelled
        val stickers = items
        val audio = audioUrl
        val name = filename
        val accepted = worker.execute {
            fun proceed() = !token.get() && !Thread.currentThread().isInterrupted && receiver.get()?.isEnabled() == true
            if (!AudioExportStore.hasPermission(app)) {
                MediaExportPanel.main.post { receiver.get()?.let { session ->
                    if (session.open) {
                        session.busy = false
                        session.status = "宿主没有存储权限，请在系统设置中授予后重试"
                    }
                } }
                return@execute
            }
            var done = 0
            var saved = 0
            val failed = mutableListOf<String>()
            var stopped = false
            var detail: String? = null
            if (audio != null) {
                try {
                    if (!proceed()) throw CancellationException()
                    AudioExportStore.downloadAndSave(app, audio, name, ::proceed)
                    saved = 1
                    done = 1
                } catch (_: CancellationException) { stopped = true }
                catch (error: Exception) {
                    FeatureHooks.w("Audio export failed: ${error.javaClass.simpleName}")
                    failed += name
                    done = 1
                    detail = when (error) {
                        is IllegalArgumentException, is IllegalStateException -> error.message
                        is HttpStatusException -> "音频下载失败（HTTP ${error.statusCode}）"
                        is SizeLimitExceededException -> "语音超过 64 MiB，无法导出"
                        else -> "下载或保存语音失败，请稍后重试"
                    }
                }
            } else {
                for (item in stickers) {
                    if (!proceed()) { stopped = true; break }
                    var success = false
                    try {
                        val data = RemoteImageDownloader.download(item.url)
                        if (!proceed()) { stopped = true; break }
                        if (data != null) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size, bounds)
                            success = bounds.outWidth > 0 && bounds.outHeight > 0 &&
                                ImageGalleryStore.save(app, data, "sticker_${done + 1}_${item.name}")
                        }
                    } catch (error: Exception) {
                        FeatureHooks.w("Sticker export failed: ${error.javaClass.simpleName}")
                    }
                    done++
                    if (success) saved++ else failed += item.name
                    val progress = done
                    val count = saved
                    val errors = failed.toList()
                    MediaExportPanel.main.post { receiver.get()?.progress(progress, count, errors, false, false) }
                }
            }
            val totalDone = done
            val totalSaved = saved
            val errors = failed.toList()
            val wasStopped = stopped
            val message = detail
            MediaExportPanel.main.post { receiver.get()?.let { session ->
                session.progress(totalDone, totalSaved, errors, true, wasStopped)
                if (session.open && message != null) session.status += " · $message"
            } }
        }
        if (!accepted) { busy = false; status = "导出未启动，请重新打开窗口" }
    }

    private fun isEnabled() = open && enabled()

    private fun progress(done: Int, saved: Int, failed: List<String>, finished: Boolean, stopped: Boolean) {
        if (!open) return
        completed = done
        succeeded = saved
        failures = failed
        if (finished) {
            busy = false
            status = if (stopped) "已停止 · 成功 $saved，失败 ${failed.size}"
                else "导出结束 · 成功 $saved，失败 ${failed.size}"
        } else status = "正在导出 $done / ${items.size} · 成功 $saved，失败 ${failed.size}"
    }

    fun dismiss() { dialog.dismiss() }
    override fun close() { dialog.dismissImmediately(); dispose() }
    private fun dispose() {
        if (!open) return
        open = false
        active = false
        cancelled.set(true)
        worker.close()
        subscription?.close()
        subscription = null
        owner.close()
        view.disposeComposition()
    }
}
