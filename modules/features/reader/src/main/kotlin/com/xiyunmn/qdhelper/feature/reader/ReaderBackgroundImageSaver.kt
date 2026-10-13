package com.xiyunmn.qdhelper.feature.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.ViewLongPressCallbacks
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.shared.media.ImageGalleryStore
import com.xiyunmn.qdhelper.shared.media.RemoteImageDownloader
import java.util.Collections
import java.util.WeakHashMap

internal data class ReaderBackgroundSaveTarget(
    val url: String,
    val themeId: Long,
    val themeName: String,
) {
    val fileStem: String
        get() = "reader_background_${themeId.takeIf { it != 0L } ?: "unknown"}_${themeName.ifBlank { "theme" }}"
}

internal object ReaderBackgroundImageSaver {
    private val worker = ManagedExecutor(
        ownerKey = "feature.reader.background-save",
        threadName = "QDHelper-reader-background-save",
        onFailure = { throwable -> FeatureHooks.e("Reader background save worker failed", throwable) },
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registrations = Collections.synchronizedMap(WeakHashMap<View, AutoCloseable>())
    private val savingViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
    )

    init {
        FeatureResources.register("reader-background-image-saver") { clear() }
    }

    fun bind(view: View, target: ReaderBackgroundSaveTarget?) {
        registrations.remove(view)?.let { registration -> runCatching { registration.close() } }
        if (target == null) return
        val result = ViewLongPressCallbacks.register(
            target = view,
            registration = LongPressRegistration(
                owner = OwnerId("reader"),
                id = "reader-background-save",
                priority = 120,
            ),
        ) { pressed, _ ->
            val pressedView = pressed as? View ?: return@register LongPressResult.Pass
            if (!SettingsRuntime.isReaderBackgroundLongPressSaveEnabled(pressedView.context)) {
                return@register LongPressResult.Pass
            }
            save(pressedView, target)
            LongPressResult.Return(true)
        }
        if (result is LongPressRegistrationResult.Registered) {
            registrations[view] = result.registration
        }
    }

    private fun save(view: View, target: ReaderBackgroundSaveTarget) {
        if (!savingViews.add(view)) {
            toast(view.context, "正在保存背景图")
            return
        }
        val context = view.context.applicationContext ?: view.context
        val accepted = worker.execute {
            val saved = runCatching {
                val data = RemoteImageDownloader.download(target.url)
                data != null && ImageGalleryStore.save(context, data, target.fileStem)
            }.onFailure { throwable ->
                FeatureHooks.e("Reader background save failed unexpectedly", throwable)
            }.getOrDefault(false)
            if (saved) {
                FeatureHooks.i("Reader background saved to gallery: themeId=${target.themeId}")
            } else {
                FeatureHooks.w("Reader background save failed: themeId=${target.themeId}")
            }
            mainHandler.post {
                savingViews.remove(view)
                toast(view.context, if (saved) "已保存到相册" else "保存失败")
            }
        }
        if (!accepted) savingViews.remove(view)
    }

    private fun clear() {
        val active = synchronized(registrations) {
            registrations.values.toList().also { registrations.clear() }
        }
        active.forEach { registration -> runCatching { registration.close() } }
        savingViews.clear()
    }

    private fun toast(context: Context, message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }
}
