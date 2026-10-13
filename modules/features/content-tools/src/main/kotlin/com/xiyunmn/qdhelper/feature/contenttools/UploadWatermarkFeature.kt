package com.xiyunmn.qdhelper.feature.contenttools

import android.content.Context
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.feature.api.runtime.HookChain
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.config.SettingsRuntime
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object UploadWatermarkFeature : InstallableFeature {
    override val name: String = "UploadWatermarkFeature"

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        var hooks = 0
        hooks += install(module, classLoader, phase, "image.uploadInk", "qdhelper.watermark.upload.image_helper", HostSymbols.Classes.IMAGE_UPLOAD_HELPER_COMPANION, true)
        hooks += install(module, classLoader, phase, "circle.uploadInk", "qdhelper.watermark.upload.circle", HostSymbols.Classes.CIRCLE_POST_EDIT_ACTIVITY, false)
        hooks += install(module, classLoader, phase, "capsule.uploadInk", "qdhelper.watermark.upload.capsule", HostSymbols.Classes.CAPSULE_EDIT_ACTIVITY, false)
        FeatureHooks.i("Upload watermark gates finished: phase=$phase, process=$processName, installedHooks=$hooks")
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
        val success = FeatureHooks.hookAround(module, method, "content-tools.upload-watermark.$id", hookId) { chain ->
            if (!SettingsRuntime.isUploadWatermarkBlockEnabled(HostSessionStore.contextOrNull())) return@hookAround chain.proceed()
            bypass(chain, id, withContext)
        }
        if (success) FeatureHooks.i("Upload watermark gate installed $id: phase=$phase")
        return if (success) 1 else 0
    }

    private fun bypass(chain: HookChain, id: String, withContext: Boolean): Any? {
        val sourcePath = chain.getArg(if (withContext) 1 else 0) as? String
        val outputFile = chain.getArg(if (withContext) 2 else 1) as? File
        if (sourcePath.isNullOrBlank() || outputFile == null) return chain.proceed()
        val source = File(sourcePath)
        if (!source.isFile) return chain.proceed()
        return runCatching {
            copyFile(source, outputFile)
            outputFile.absolutePath
        }.getOrElse { error ->
            FeatureHooks.e("Upload watermark bypass fallback $id", error)
            chain.proceed()
        }
    }

    private fun copyFile(source: File, output: File) {
        if (source.canonicalPath == output.canonicalPath) return
        output.parentFile?.takeIf { !it.isDirectory }?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(output).use(input::copyTo)
        }
    }
}
