package com.xiyunmn.qdhelper.feature.contenttools

import android.content.Context
import android.content.Intent
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.foundation.runtime.RuntimeCapabilities
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import org.json.JSONObject

/** Only the RoleCard gallery request owns this intent flag; other gallery callers retain their choice. */
object RoleCardWatermarkFeature : InstallableFeature {
    override val name = "RoleCardWatermarkFeature"
    private val requestDepth = ThreadLocal<Int>()

    override fun install(module: HookModule, classLoader: ClassLoader, processName: String, phase: String) {
        if (!HostSymbols.isMainProcess(processName)) return
        val owner = FeatureHooks.findClassOrNull(HostSymbols.Classes.ACTION_URL_PROCESS, classLoader)
        val entry = owner?.let { FeatureHooks.findDeclaredMethodOrNull(it, "processImageGallery",
            Context::class.java, JSONObject::class.java, String::class.java) }
            ?.takeIf { it.returnType == Int::class.javaPrimitiveType }
        val flagInstalled = FeatureHooks.hookAround(module,
            Intent::class.java.getDeclaredMethod("putExtra", String::class.java, Boolean::class.javaPrimitiveType),
            "content-tools.role-card-watermark.flag", "qdhelper.watermark.role-card.flag") { chain ->
            val intent = chain.thisObject as? Intent
            if ((requestDepth.get() ?: 0) > 0 && chain.args[0] == "draw_water_mark" &&
                intent?.component?.className == HostSymbols.Classes.QD_UI_GALLERY_ACTIVITY) {
                chain.proceed(arrayOf("draw_water_mark", false))
            } else chain.proceed()
        }
        val entryInstalled = flagInstalled && FeatureHooks.hookAround(module, entry,
            "content-tools.role-card-watermark.entry", "qdhelper.watermark.role-card.entry") { chain ->
            val roleCard = (chain.getArg(2) as? String).equals("RoleCard", ignoreCase = true)
            if (!roleCard || !SettingsRuntime.isRoleCardWatermarkBlockEnabled(HostSessionStore.contextOrNull())) {
                chain.proceed()
            } else {
                val previous = requestDepth.get() ?: 0
                requestDepth.set(previous + 1)
                try { chain.proceed() } finally {
                    if (previous == 0) requestDepth.remove() else requestDepth.set(previous)
                }
            }
        }
        RuntimeCapabilities.record("content.role-card-watermark", "角色卡大图去水印", entryInstalled,
            if (entryInstalled) "RoleCard来源与图库水印开关已接入" else "当前宿主的角色卡图库入口未完整匹配")
        FeatureResources.register("RoleCardWatermarkFeature") {
            requestDepth.remove()
            RuntimeCapabilities.removePrefix("content.role-card-watermark")
        }
    }
}
