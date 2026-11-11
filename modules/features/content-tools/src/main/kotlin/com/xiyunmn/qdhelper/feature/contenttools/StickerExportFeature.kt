package com.xiyunmn.qdhelper.feature.contenttools

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.*
import com.xiyunmn.qdhelper.feature.contenttools.media.MediaExportPanel
import com.xiyunmn.qdhelper.feature.contenttools.media.StickerExportItem
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.foundation.runtime.RuntimeCapabilities
import com.xiyunmn.qdhelper.hook.view.LongPressRegistration
import com.xiyunmn.qdhelper.hook.view.LongPressRegistrationResult
import com.xiyunmn.qdhelper.hook.view.LongPressResult
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.MediaExportTargetKeys
import java.util.WeakHashMap

object StickerExportFeature : InstallableFeature {
    override val name = "StickerExport"
    private const val KEY = "content.sticker-export"
    private val registrations = WeakHashMap<View, AutoCloseable>()
    private var cleanup: AutoCloseable? = null
    private var lifecycle: AutoCloseable? = null

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        if (!HostSymbols.isMainProcess(processName)) return
        val context = HostSessionStore.contextOrNull() ?: return
        if (!SettingsRuntime.isStickerExportEnabled(context)) return
        if (cleanup == null) cleanup = FeatureResources.register(KEY) {
            registrations.values.toList().forEach(AutoCloseable::close)
            registrations.clear()
            lifecycle?.close()
            lifecycle = null
            cleanup = null
            MediaExportPanel.closeAll()
            RuntimeCapabilities.removePrefix(KEY)
        }
        if (lifecycle == null) lifecycle = ActivityLifecycleCallbacks.subscribe(OwnerId("content-tools"), KEY,
            destroyed = { activity ->
                registrations.keys.filter { activityOf(it.context) === activity }.forEach { registrations.remove(it)?.close() }
            })
        val bind = HostTargetResolver.resolveMethod(context, classLoader, processName, MediaExportTargetKeys.stickerDetailBind)
        val titleId = context.resources.getIdentifier("tvTitle", "id", HostSymbols.PACKAGE)
        val bean = bind?.parameterTypes?.singleOrNull()
        val face = FeatureHooks.findClassOrNull("com.qidian.QDReader.repository.entity.dressup.StickersBean\$FaceListBean", classLoader)
        val getName = bean?.methods?.singleOrNull { it.name == "getName" && it.parameterCount == 0 && it.returnType == String::class.java }
        val getFaces = bean?.methods?.singleOrNull { it.name == "getFaceList" && it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
        val getImage = face?.methods?.singleOrNull { it.name == "getImage" && it.parameterCount == 0 && it.returnType == String::class.java }
        val getText = face?.methods?.singleOrNull { it.name == "getText" && it.parameterCount == 0 && it.returnType == String::class.java }
        if (bind == null || face == null || titleId == 0 || getName == null || getFaces == null || getImage == null || getText == null) {
            RuntimeCapabilities.record(KEY, "表情包批量导出", false, "详情数据入口未完整匹配")
            return
        }
        val installed = FeatureHooks.hookAfter(module, bind, name, "$KEY.bind") { call, _ ->
            if (Looper.myLooper() != Looper.getMainLooper() || !SettingsRuntime.isStickerExportEnabled()) return@hookAfter
            val activity = call.thisObject as? Activity ?: return@hookAfter
            val value = call.args.firstOrNull() ?: return@hookAfter
            val title = activity.findViewById<View>(titleId) ?: return@hookAfter
            val packageName = (FeatureHooks.invoke(getName, value) as? String).orEmpty().ifBlank { "表情包" }
            val faces = FeatureHooks.invoke(getFaces, value) as? List<*> ?: return@hookAfter
            val items = faces.mapIndexedNotNull { index, item ->
                item?.takeIf(face::isInstance)?.let {
                    StickerExportItem(
                        (FeatureHooks.invoke(getText, it) as? String).orEmpty().ifBlank { "表情 ${index + 1}" },
                        (FeatureHooks.invoke(getImage, it) as? String).orEmpty(),
                    )
                }
            }
            registrations.remove(title)?.close()
            val registered = ViewLongPressCallbacks.register(title, LongPressRegistration(OwnerId("content-tools"), KEY, priority = 50)) { target, _ ->
                val view = target as? View ?: return@register LongPressResult.Pass
                if (!SettingsRuntime.isStickerExportEnabled(view.context)) return@register LongPressResult.Pass
                val owner = activityOf(view.context) ?: return@register LongPressResult.Pass
                if (items.isEmpty()) Toast.makeText(owner, "当前表情包没有可导出的图片", Toast.LENGTH_SHORT).show()
                else MediaExportPanel.showStickers(owner, view, packageName, items) { SettingsRuntime.isStickerExportEnabled() }
                LongPressResult.Return(true)
            }
            if (registered is LongPressRegistrationResult.Registered) registrations[title] = registered.registration
        }
        RuntimeCapabilities.record(KEY, "表情包批量导出", installed,
            if (installed) "详情页名称长按入口已接入" else "详情页入口未安装")
    }

    private fun activityOf(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
        return null
    }
}
