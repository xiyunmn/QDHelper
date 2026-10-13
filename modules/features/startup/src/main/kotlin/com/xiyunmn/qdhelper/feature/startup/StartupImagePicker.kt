package com.xiyunmn.qdhelper.feature.startup

import com.xiyunmn.qdhelper.shared.ui.installModuleWatermark
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.shared.io.OemFilePickerLauncher

/**
 * 相册选图作为启动图：SAF 选图（零权限）→ 交互裁剪/旋转对话框 → 落地为模块私有 JPEG →
 * 写入相册启动图专用键（[SettingIds.KEY_LOCAL_SPLASH_IMAGE_PATH]）为本地绝对路径并互斥开启
 * 「相册启动图」开关（自动关闭自定义直链/官方）→ 触发后台同步到宿主启动图缓存。
 *
 * 结果回收依赖宿主 Activity 的 onActivityResult（[StartupFeature] 里 hook 后转发到 [handleActivityResult]）。
 */
object StartupImagePicker {
    const val REQUEST_CODE = 0x514449

    private val IMAGE_MIME_TYPES = arrayOf("image/jpeg", "image/png", "image/webp")

    fun open(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, IMAGE_MIME_TYPES)
            putExtra(Intent.EXTRA_TITLE, "选择启动图，建议竖版 1080×2400")
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (!OemFilePickerLauncher.startActivityForResult(activity, intent, REQUEST_CODE)) {
            Toast.makeText(activity, "无法打开图片选择器", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 清除相册启动图：删除模块私有目录里落地的选图文件，清空相册图路径键并关闭相册图开关。
     * 只删模块自己落地的私有文件，不动手填直链/官方配置；关闭开关后 [activeSplashTarget] 会回落到
     * 下一个已开启的源（若都关则无自定义启动图，宿主恢复原生）。
     */
    fun clear(activity: Activity) {
        val context = activity.applicationContext ?: activity
        StartupImageProcessor.pruneOldPicks(context, keep = null)
        SettingsRuntime.putString(context, SettingIds.KEY_LOCAL_SPLASH_IMAGE_PATH, "")
        SettingsRuntime.putBoolean(context, SettingIds.KEY_LOCAL_SPLASH_IMAGE_ENABLED, false)
        FeatureHooks.i("StartupImagePicker: cleared local splash image")
        Toast.makeText(activity, "已清除相册启动图", Toast.LENGTH_SHORT).show()
    }

    fun handleActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) {
            return false
        }
        if (resultCode != Activity.RESULT_OK) {
            Toast.makeText(activity, "未选择图片", Toast.LENGTH_SHORT).show()
            return true
        }
        val uri = data?.data
        if (uri == null) {
            Toast.makeText(activity, "未获取到图片", Toast.LENGTH_SHORT).show()
            return true
        }
        showCropDialog(activity, uri)
        return true
    }

    private fun showCropDialog(activity: Activity, uri: Uri) {
        val context = activity.applicationContext ?: activity
        val bitmap = StartupImageProcessor.decodeCorrected(context, uri)
        if (bitmap == null) {
            Toast.makeText(activity, "无法读取该图片", Toast.LENGTH_SHORT).show()
            return
        }
        var working: Bitmap = bitmap

        val cropView = StartupCropView(activity).apply {
            setBitmap(working)
        }

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.installModuleWatermark(darkSurface = true)
        root.addView(
            cropView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(buildBottomBar(activity, dialog, cropView, context) {
            working = StartupImageProcessor.rotate90(working)
            cropView.setBitmap(working)
        })

        dialog.setContentView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        dialog.setOnDismissListener {
            if (!working.isRecycled) {
                working.recycle()
            }
        }
        dialog.setCancelable(true)
        dialog.show()

        // 保存回调需要读到最新的 working 位图——用一个持有者转发。
        cropView.tag = { working }
    }

    private fun buildBottomBar(
        activity: Activity,
        dialog: Dialog,
        cropView: StartupCropView,
        context: android.content.Context,
        onRotate: () -> Unit,
    ): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)

            addView(barButton(activity, "取消") {
                dialog.dismiss()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(barButton(activity, "旋转 90°") {
                onRotate()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(barButton(activity, "使用") {
                confirm(activity, dialog, cropView, context)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun confirm(
        activity: Activity,
        dialog: Dialog,
        cropView: StartupCropView,
        context: android.content.Context,
    ) {
        val rect = cropView.cropRectInBitmap()
        val supplier = cropView.tag as? () -> Bitmap
        val source = supplier?.invoke()
        if (rect == null || source == null || source.isRecycled) {
            Toast.makeText(activity, "裁剪区域无效", Toast.LENGTH_SHORT).show()
            return
        }
        val saved = StartupImageProcessor.cropAndSave(context, source, rect)
        if (saved == null) {
            Toast.makeText(activity, "保存启动图失败", Toast.LENGTH_SHORT).show()
            return
        }
        StartupImageProcessor.pruneOldPicks(context, keep = saved)
        // 落地到相册图专用键（与手填直链、官方分离），并互斥开启相册启动图开关（自动关另两个源）。
        SettingsRuntime.putString(context, SettingIds.KEY_LOCAL_SPLASH_IMAGE_PATH, saved.absolutePath)
        SettingsRuntime.putExclusiveSplashToggle(context, SettingIds.KEY_LOCAL_SPLASH_IMAGE_ENABLED, true)
        FeatureHooks.i("StartupImagePicker: saved custom splash to ${saved.absolutePath}")
        Toast.makeText(activity, "已设为启动图，正在同步", Toast.LENGTH_SHORT).show()
        StartupImageAutoSync.schedule(context)
        dialog.dismiss()
    }

    private fun barButton(activity: Activity, text: String, onClick: () -> Unit): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            val v = (10 * resources.displayMetrics.density).toInt()
            setPadding(0, v, 0, v)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }
}
