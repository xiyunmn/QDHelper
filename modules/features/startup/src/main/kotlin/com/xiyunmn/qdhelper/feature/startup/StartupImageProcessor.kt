package com.xiyunmn.qdhelper.feature.startup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import java.io.File
import java.io.FileOutputStream

/**
 * 启动图相册选图的图片处理：EXIF 自动摆正 → 交互旋转/裁剪 → 落地为模块私有 JPEG。
 *
 * 全部基于 AOSP 原生 API（BitmapFactory / android.media.ExifInterface / Matrix），零 androidx 依赖。
 * 解码阶段用 inSampleSize 限制到 [MAX_DECODE_DIMEN] 以内防 OOM；落地阶段按 [MAX_SAVE_DIMEN] 缩到
 * 竖版启动图合理尺寸再压缩，避免把超大原图写进宿主启动图缓存。
 */
internal object StartupImageProcessor {
    private const val MAX_DECODE_DIMEN = 4096
    private const val MAX_SAVE_HEIGHT = 2400
    private const val SAVE_QUALITY = 92
    // 三源存储隔离：相册选图独占 qdhelper/splash/local 子目录，pruneOldPicks 只清理该子目录，
    // 绝不触碰直链/官方的存储（它们是纯 URL/用户本地路径字符串，模块不往私有目录落地其图片）。
    private const val LOCAL_SPLASH_DIR = "qdhelper/splash/local"

    /**
     * 解码 [uri] 并按 EXIF 方向自动摆正，返回可供裁剪的位图；失败返回 null。
     */
    fun decodeCorrected(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            FeatureHooks.w("StartupImageProcessor: decode bounds invalid")
            return null
        }
        var sample = 1
        while (width / sample > MAX_DECODE_DIMEN || height / sample > MAX_DECODE_DIMEN) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        }.getOrElse { throwable ->
            FeatureHooks.w("StartupImageProcessor: decode failed, error=${throwable.javaClass.name}")
            null
        } ?: return null
        return applyOrientation(context, uri, bitmap)
    }

    /**
     * 顺时针旋转 90°；返回新位图，回收原位图。
     */
    fun rotate90(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) {
            source.recycle()
        }
        return rotated
    }

    /**
     * 按位图坐标系裁剪矩形 [cropInBitmap]，缩到竖版启动图合理高度后压缩为 JPEG 写入模块私有目录。
     * 返回落地文件；失败返回 null。
     */
    fun cropAndSave(context: Context, source: Bitmap, cropInBitmap: RectF): File? {
        val left = cropInBitmap.left.toInt().coerceIn(0, source.width - 1)
        val top = cropInBitmap.top.toInt().coerceIn(0, source.height - 1)
        val right = cropInBitmap.right.toInt().coerceIn(left + 1, source.width)
        val bottom = cropInBitmap.bottom.toInt().coerceIn(top + 1, source.height)
        val cropW = right - left
        val cropH = bottom - top
        if (cropW <= 0 || cropH <= 0) {
            return null
        }
        return runCatching {
            val cropped = Bitmap.createBitmap(source, left, top, cropW, cropH)
            val scaled = scaleDown(cropped, MAX_SAVE_HEIGHT)
            val dir = splashDir(context)
            if (!dir.isDirectory && !dir.mkdirs()) {
                error("cannot create splash dir")
            }
            val target = File(dir, "splash_custom_${System.currentTimeMillis()}.jpg")
            FileOutputStream(target).use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, SAVE_QUALITY, output)
            }
            if (scaled !== cropped) {
                scaled.recycle()
            }
            cropped.recycle()
            if (!target.isFile || target.length() <= 0L) {
                target.delete()
                error("saved file empty")
            }
            target
        }.getOrElse { throwable ->
            FeatureHooks.w("StartupImageProcessor: crop/save failed, error=${throwable.javaClass.name}")
            null
        }
    }

    /**
     * 清理模块私有启动图目录里旧的选图文件，只保留 [keep]（可空）。选图目录只放模块自己落地的
     * 处理结果，删除范围严格限定在该私有目录内。
     */
    fun pruneOldPicks(context: Context, keep: File?) {
        runCatching {
            val dir = splashDir(context)
            if (!dir.isDirectory) {
                return
            }
            val keepPath = keep?.canonicalPath
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.canonicalPath != keepPath) {
                    file.delete()
                }
            }
        }
    }

    private fun splashDir(context: Context): File {
        return File(context.filesDir, LOCAL_SPLASH_DIR)
    }

    private fun scaleDown(source: Bitmap, maxHeight: Int): Bitmap {
        if (source.height <= maxHeight) {
            return source
        }
        val ratio = maxHeight.toFloat() / source.height.toFloat()
        val targetW = (source.width * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, maxHeight, true)
    }

    private fun applyOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = readExifOrientation(context, uri)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            val corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (corrected !== bitmap) {
                bitmap.recycle()
            }
            corrected
        }.getOrDefault(bitmap)
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }
}
