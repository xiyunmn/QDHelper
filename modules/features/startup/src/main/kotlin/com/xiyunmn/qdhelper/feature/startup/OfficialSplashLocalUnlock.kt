package com.xiyunmn.qdhelper.feature.startup

import android.content.Context
import android.view.View
import android.widget.Toast
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.StartupTargetKeys
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Map

/** Locally selects official startup papers without calling the host ownership/toggle API. */
internal object OfficialSplashLocalUnlock {
    fun install(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val paperClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.BOOT_PAPER, classLoader) ?: return 0
        val viewModelClass = FeatureHooks.findClassOrNull(
            HostSymbols.Classes.SPLASH_CONFIG_SHARED_VIEW_MODEL,
            classLoader,
        ) ?: return 0
        val method = HostTargetResolver.resolveEarlyMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = StartupTargetKeys.splashConfigButtonBind,
        ) ?: return 0
        val paperAccess = BootPaperAccess.resolve(paperClass) ?: run {
            FeatureHooks.w("Official splash local unlock: BootPaper fields unresolved")
            return 0
        }
        val viewModelAccess = SplashConfigViewModelAccess.resolve(viewModelClass) ?: run {
            FeatureHooks.w("Official splash local unlock: ViewModel used-paper map unresolved")
            return 0
        }
        val success = FeatureHooks.hookAround(
            module = module,
            executable = method,
            feature = "startup.officialSplashLocalUnlock:${HostSymbols.classLabel(method.declaringClass.name)}.${method.name}",
            id = "qdhelper.startup.official_splash_local_unlock",
        ) { chain ->
            val button = chain.getArg(0) as? View
            val paper = chain.getArg(1)?.takeIf(paperClass::isInstance)
            val viewModel = chain.getArg(2)?.takeIf(viewModelClass::isInstance)
            val style = chain.getArg(3) as? Int
            val context = button?.context ?: HostSessionStore.contextOrNull()
            if (
                button == null || paper == null || viewModel == null || style == null ||
                !SettingsRuntime.isOfficialSplashImageLocalUnlockEnabled(context)
            ) {
                return@hookAround chain.proceed()
            }
            val info = paperAccess.read(paper) ?: return@hookAround chain.proceed()
            val selected = isSelected(context, info.paperId)
            val result = paperAccess.withPresentationState(paper, viewModel, viewModelAccess, selected) {
                chain.proceed()
            }
            installLocalClick(button, paper, viewModel, style, method, paperAccess, viewModelAccess)
            result
        }
        if (!success) return 0

        FeatureHooks.i(
            "Official splash local unlock installed: ${HostSymbols.classLabel(method.declaringClass.name)}.${method.name}, " +
                "phase=$phase",
        )
        return 1
    }

    private fun installLocalClick(
        button: View,
        paper: Any,
        viewModel: Any,
        style: Int,
        bindMethod: Method,
        paperAccess: BootPaperAccess,
        viewModelAccess: SplashConfigViewModelAccess,
    ) {
        button.isEnabled = true
        button.isClickable = true
        button.setOnClickListener {
            val context = button.context.applicationContext ?: button.context
            val info = paperAccess.read(paper)
            if (info == null || !StartupImageStore.isValidOfficialSelection(info.paperId, info.imageUrl)) {
                toast(context, "该启动图缺少可用图片直链")
                return@setOnClickListener
            }
            val wasSelected = isSelected(context, info.paperId)
            val saved = if (wasSelected) {
                SettingsRuntime.clearLocalOfficialSplashImage(context)
            } else {
                SettingsRuntime.selectLocalOfficialSplashImage(
                    context = context,
                    paperId = info.paperId,
                    imageUrl = info.imageUrl,
                    darkLogo = info.darkLogo,
                )
            }
            if (!saved) {
                toast(context, "本地启动图状态保存失败")
                return@setOnClickListener
            }
            if (wasSelected) {
                toast(context, "已在本地停用启动图")
            } else {
                val label = info.name.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()
                toast(context, "已在本地启用启动图$label")
                StartupImageAutoSync.schedule(context)
            }
            FeatureHooks.i(
                "Official splash local selection changed: selected=${!wasSelected}, paperId=${info.paperId}",
            )
            button.post {
                refreshButton(
                    button,
                    paper,
                    viewModel,
                    style,
                    bindMethod,
                    paperAccess,
                    viewModelAccess,
                )
            }
        }
    }

    private fun refreshButton(
        button: View,
        paper: Any,
        viewModel: Any,
        style: Int,
        bindMethod: Method,
        paperAccess: BootPaperAccess,
        viewModelAccess: SplashConfigViewModelAccess,
    ) {
        val info = paperAccess.read(paper) ?: return
        val selected = isSelected(button.context, info.paperId)
        paperAccess.withPresentationState(paper, viewModel, viewModelAccess, selected) {
            FeatureHooks.invoke(bindMethod, null, button, paper, viewModel, style)
        }
        // Reflection invocation may bypass hooks on some runtimes; always restore the local listener.
        installLocalClick(button, paper, viewModel, style, bindMethod, paperAccess, viewModelAccess)
    }

    private fun isSelected(context: Context?, paperId: Long): Boolean {
        return SettingsRuntime.isOfficialSplashImageLocalUnlockEnabled(context) &&
            StartupImageStore.isSameOfficialSelection(
                SettingsRuntime.localOfficialSplashImagePaperId(context),
                SettingsRuntime.localOfficialSplashImageUrl(context),
                paperId,
            )
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

private data class OfficialSplashPaper(
    val paperId: Long,
    val imageUrl: String,
    val darkLogo: Boolean,
    val name: String,
)

private class BootPaperAccess(
    private val paperIdField: Field,
    private val imageUrlField: Field,
    private val darkLogoField: Field,
    private val obtainField: Field,
    private val usedField: Field,
    private val nameField: Field,
) {
    fun read(paper: Any): OfficialSplashPaper? {
        val paperId = (FeatureHooks.read(paperIdField, paper) as? Number)?.toLong() ?: return null
        val imageUrl = (FeatureHooks.read(imageUrlField, paper) as? String)?.trim().orEmpty()
        val darkLogo = (FeatureHooks.read(darkLogoField, paper) as? Number)?.toInt() == 1
        val name = (FeatureHooks.read(nameField, paper) as? String)?.trim().orEmpty()
        return OfficialSplashPaper(paperId, imageUrl, darkLogo, name)
    }

    fun <T> withPresentationState(
        paper: Any,
        viewModel: Any,
        viewModelAccess: SplashConfigViewModelAccess,
        selected: Boolean,
        block: () -> T,
    ): T {
        val originalObtain = (FeatureHooks.read(obtainField, paper) as? Number)?.toInt() ?: return block()
        val originalUsed = (FeatureHooks.read(usedField, paper) as? Number)?.toInt() ?: return block()
        val desiredUsed = if (selected) 1 else 0
        val mapSnapshot = viewModelAccess.snapshot(viewModel, paperId = read(paper)?.paperId ?: 0L)
            ?: return block()
        val obtainChanged = originalObtain != 1 && FeatureHooks.write(obtainField, paper, 1)
        val usedChanged = originalUsed != desiredUsed && FeatureHooks.write(usedField, paper, desiredUsed)
        return try {
            block()
        } finally {
            if (usedChanged) FeatureHooks.write(usedField, paper, originalUsed)
            if (obtainChanged) FeatureHooks.write(obtainField, paper, originalObtain)
            mapSnapshot.restore()
        }
    }

    companion object {
        fun resolve(paperClass: Class<*>): BootPaperAccess? {
            val fields = FeatureHooks.allDeclaredFields(paperClass)
            fun field(serializedName: String, rawName: String, type: Class<*>): Field? =
                fields.singleOrNull { candidate ->
                    candidate.type == type && candidate.serializedNameOrNull() == serializedName
                } ?: fields.singleOrNull { candidate -> candidate.type == type && candidate.name == rawName }

            return BootPaperAccess(
                paperIdField = field("PaperId", "paperId", Long::class.javaPrimitiveType!!) ?: return null,
                imageUrlField = field("ImageUrl", "imageUrl", String::class.java) ?: return null,
                darkLogoField = field("IsDarkLogo", "isDarkLogo", Int::class.javaPrimitiveType!!) ?: return null,
                obtainField = field("IsObtain", "isObtain", Int::class.javaPrimitiveType!!) ?: return null,
                usedField = field("IsUsed", "isUsed", Int::class.javaPrimitiveType!!) ?: return null,
                nameField = field("Name", "name", String::class.java) ?: return null,
            )
        }
    }
}

private class SplashConfigViewModelAccess(private val usedPapersField: Field) {
    fun snapshot(viewModel: Any, paperId: Long): UsedPaperMapSnapshot? {
        if (paperId <= 0L) return null
        @Suppress("UNCHECKED_CAST")
        val map = FeatureHooks.read(usedPapersField, viewModel) as? MutableMap<Any?, Any?> ?: return null
        return UsedPaperMapSnapshot(
            map = map,
            key = paperId,
            hadValue = map.containsKey(paperId),
            value = map[paperId],
        )
    }

    companion object {
        fun resolve(viewModelClass: Class<*>): SplashConfigViewModelAccess? {
            val field = FeatureHooks.allDeclaredFields(viewModelClass).singleOrNull { candidate ->
                Map::class.java.isAssignableFrom(candidate.type)
            } ?: return null
            return SplashConfigViewModelAccess(field)
        }
    }
}

private data class UsedPaperMapSnapshot(
    val map: MutableMap<Any?, Any?>,
    val key: Long,
    val hadValue: Boolean,
    val value: Any?,
) {
    fun restore() {
        if (hadValue) map[key] = value else map.remove(key)
    }
}

private fun Field.serializedNameOrNull(): String? {
    val annotation = declaredAnnotations.firstOrNull { candidate ->
        candidate.annotationClass.java.name == "com.google.gson.annotations.SerializedName"
    } ?: return null
    val method = annotation.annotationClass.java.methods.singleOrNull { candidate ->
        candidate.name == "value" && candidate.parameterTypes.isEmpty() && candidate.returnType == String::class.java
    } ?: return null
    return runCatching { method.invoke(annotation) as? String }.getOrNull()
}
