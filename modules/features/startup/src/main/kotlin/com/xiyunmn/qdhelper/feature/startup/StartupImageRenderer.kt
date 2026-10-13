package com.xiyunmn.qdhelper.feature.startup

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import java.io.File

internal object StartupImageRenderer {
    /**
     * 渲染自定义启动图到给定的 ImageView。
     *
     * 返回 true 表示渲染成功；false 表示无可用图片或加载失败，调用者应继续执行原逻辑。
     */
    fun renderToImageView(activity: Activity, imageView: ImageView?): Boolean {
        if (imageView == null) {
            FeatureHooks.w("StartupImageRenderer: target ImageView is null")
            return false
        }

        val target = StartupImageStore.activeSplashTarget(activity) ?: return false
        val file = when (target.source) {
            StartupImageStore.SplashSource.LocalFile -> File(target.url)
            StartupImageStore.SplashSource.CustomUrl,
            StartupImageStore.SplashSource.Official -> {
                val cacheDir = runCatching {
                    StartupImageStore.splashCacheDir(activity)
                }.getOrElse { throwable ->
                    FeatureHooks.w("StartupImageRenderer: failed to resolve cache dir, error=${throwable.javaClass.name}")
                    return false
                }
                File(cacheDir + target.paperId + target.suffix)
            }
        }

        if (!file.isFile || !file.canRead()) {
            FeatureHooks.w("StartupImageRenderer: file not found or not readable: ${file.absolutePath}")
            return false
        }

        return runCatching {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap == null) {
                FeatureHooks.w("StartupImageRenderer: failed to decode bitmap from ${file.absolutePath}")
                return false
            }
            imageView.setImageBitmap(bitmap)
            // 宿主 initCustomSplashScreen() 本身只发起异步加载，真正上屏在 YWImageLoader.onSuccess 里：
            // mIvSplash.setVisibility(VISIBLE) + mIvSlogan.setVisibility(GONE)。接管后短路了原方法体，
            // 必须复刻这两个可见性副作用，否则图已 setImageBitmap 但 mIvSplash 仍不可见（看不到）。
            imageView.visibility = View.VISIBLE
            hideSlogan(activity)
            FeatureHooks.i("StartupImageRenderer: rendered custom splash from ${file.absolutePath}")
            true
        }.getOrElse { throwable ->
            FeatureHooks.w("StartupImageRenderer: render failed, error=${throwable.javaClass.name}")
            false
        }
    }

    /**
     * 复刻宿主 onSuccess 的 `mIvSlogan.setVisibility(GONE)`：自定义启动图分支下隐藏 slogan 层。
     * 按名精确定位（另有 mIvSplash/mIvBottom/mIvYingYongBao），找不到或未初始化则静默跳过。
     */
    private fun hideSlogan(activity: Activity) {
        runCatching {
            val field = FeatureHooks.allDeclaredFields(activity.javaClass).firstOrNull { field ->
                field.type == ImageView::class.java && field.name == "mIvSlogan"
            } ?: return
            (FeatureHooks.read(field, activity) as? ImageView)?.visibility = View.GONE
        }
    }
}
