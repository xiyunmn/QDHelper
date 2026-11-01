package com.xiyunmn.qdhelper.feature.contenttools

import android.content.Context
import android.graphics.Bitmap
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.foundation.runtime.RuntimeCapabilities
import com.xiyunmn.qdhelper.feature.api.runtime.HookChain
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.config.SettingsRuntime
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object UploadWatermarkFeature : InstallableFeature {
    override val name: String = "UploadWatermarkFeature"

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        if (!HostSymbols.isMainProcess(processName)) return
        var hooks = 0
        hooks += install(module, classLoader, phase, "image.uploadInk", "qdhelper.watermark.upload.image_helper", HostSymbols.Classes.IMAGE_UPLOAD_HELPER_COMPANION, true)
        hooks += install(module, classLoader, phase, "circle.uploadInk", "qdhelper.watermark.upload.circle", HostSymbols.Classes.CIRCLE_POST_EDIT_ACTIVITY, false)
        hooks += install(module, classLoader, phase, "capsule.uploadInk", "qdhelper.watermark.upload.capsule", HostSymbols.Classes.CAPSULE_EDIT_ACTIVITY, false)
        FeatureHooks.i("Upload watermark gates finished: phase=$phase, process=$processName, installedHooks=$hooks")
        RuntimeCapabilities.record("content.upload-watermark", "发帖图片去水印", hooks == 3,
            "上传入口已安装 $hooks/3；保留方向校正与JPEG输出")
    }

    private fun install(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
        id: String,
        hookId: String,
        className: String,
        withContext: Boolean,
    ): Int {
        val owner = FeatureHooks.findClassOrNull(className, classLoader) ?: return 0
        val parameterTypes = if (withContext) {
            arrayOf(Context::class.java, String::class.java, File::class.java)
        } else {
            arrayOf(String::class.java, File::class.java)
        }
        val method = FeatureHooks.findDeclaredMethodOrNull(owner, "addInk2BitmapFile", *parameterTypes) ?: return 0
        if (method.returnType != String::class.java) return 0
        val rotate = FeatureHooks.findDeclaredMethodOrNull(owner, "getRotateBitmap", String::class.java)
            ?.takeIf { it.returnType == Bitmap::class.java }
        val success = FeatureHooks.hookAround(module, method, "content-tools.upload-watermark.$id", hookId) { chain ->
            if (!SettingsRuntime.isUploadWatermarkBlockEnabled(HostSessionStore.contextOrNull())) return@hookAround chain.proceed()
            bypass(chain, id, withContext, rotate)
        }
        if (success) FeatureHooks.i("Upload watermark gate installed $id: phase=$phase")
        return if (success) 1 else 0
    }

    private fun bypass(chain: HookChain, id: String, withContext: Boolean, rotate: Method?): String {
        val sourcePath = chain.getArg(if (withContext) 1 else 0) as? String
        val outputFile = chain.getArg(if (withContext) 2 else 1) as? File
        // A failed preparation must never re-enter the host's watermark compositor.
        if (sourcePath.isNullOrBlank()) return sourcePath.orEmpty()
        val source = File(sourcePath)
        if (outputFile == null || rotate == null || !source.isFile) {
            fallback(id, "source-or-rotation-unavailable")
            return sourcePath
        }
        return runCatching {
            if (source.canonicalPath == outputFile.canonicalPath) return sourcePath
            val bitmap = FeatureHooks.invoke(rotate, chain.thisObject, sourcePath) as? Bitmap
                ?: error("image decode failed")
            writeJpeg(bitmap, outputFile)
            RuntimeCapabilities.removePrefix("content.upload-watermark.fallback.$id")
            outputFile.absolutePath
        }.getOrElse { error ->
            fallback(id, error.javaClass.simpleName)
            sourcePath
        }
    }

    private fun writeJpeg(bitmap: Bitmap, output: File) {
        val parent = requireNotNull(output.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "output directory unavailable" }
        val temporary = File.createTempFile(".qdhelper-noink-", ".jpg", parent)
        try {
            FileOutputStream(temporary).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)) { "image encode failed" }
                stream.fd.sync()
            }
            check(temporary.length() > 0) { "empty image output" }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun fallback(id: String, reason: String) {
        if (RuntimeCapabilities.record("content.upload-watermark.fallback.$id", "上传图片处理", false,
                "本次使用原文件，未调用加水印流程；$id: $reason")) {
            FeatureHooks.w("Upload watermark preparation uses original source: $id, reason=$reason")
        }
    }
}
