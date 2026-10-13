package com.xiyunmn.qdhelper.feature.reader

import android.content.Context
import android.view.View
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object ReaderBackgroundFeature : InstallableFeature {
    override val name: String = "ReaderBackgroundFeature"

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!HostSymbols.isMainProcess(processName)) return
        val entityClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.READER_THEME_ENTITY, classLoader)
        val detailClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.QD_READER_THEME_DETAIL_ACTIVITY, classLoader)
        if (entityClass == null || detailClass == null) {
            FeatureHooks.w("Reader background host classes unavailable: phase=$phase")
            return
        }
        val access = ReaderThemeAccess.resolve(entityClass, detailClass)
        if (access == null) {
            FeatureHooks.w("Reader background entity access unresolved: phase=$phase")
            return
        }

        val download = installDownloadUnlock(module, detailClass, access)
        val button = installButtonUnlock(module, detailClass, access)
        val click = installUseClickUnlock(module, detailClass, access)
        val detailSave = installDetailPreviewSave(module, detailClass, entityClass, access)
        val listSave = installListSaveHook(
            module,
            classLoader,
            HostSymbols.Classes.QD_READER_THEME_LIST_ADAPTER,
            entityClass,
            access,
            "list",
        )
        val moreSave = installListSaveHook(
            module,
            classLoader,
            HostSymbols.Classes.QD_READER_THEME_MORE_ADAPTER,
            entityClass,
            access,
            "more",
        )
        FeatureHooks.i(
            "Reader background phase finished: phase=$phase, process=$processName, " +
                "download=$download, button=$button, click=$click, " +
                "saveDetail=$detailSave, saveList=$listSave, saveMore=$moreSave",
        )
    }

    private fun installDownloadUnlock(
        module: HookModule,
        detailClass: Class<*>,
        access: ReaderThemeAccess,
    ): Boolean {
        val method = detailClass.uniqueNoArgVoid("checkNetworkForDownload") ?: return false
        return FeatureHooks.hookAround(
            module,
            method,
            "reader.background:download eligibility",
            "qdhelper.reader_background.download_eligibility",
        ) { chain ->
            val owner = chain.thisObject
            if (!SettingsRuntime.isReaderBackgroundLocalUnlockEnabled(owner as? Context)) {
                return@hookAround chain.proceed()
            }
            access.withHaveStatus(owner, LOCAL_HAVE_STATUS) { chain.proceed() }
        }
    }

    private fun installButtonUnlock(
        module: HookModule,
        detailClass: Class<*>,
        access: ReaderThemeAccess,
    ): Boolean {
        val method = detailClass.uniqueNoArgVoid("updateDownloadButton") ?: return false
        return FeatureHooks.hookAround(
            module,
            method,
            "reader.background:member button",
            "qdhelper.reader_background.member_button",
        ) { chain ->
            val owner = chain.thisObject
            val context = owner as? Context
            if (!SettingsRuntime.isReaderBackgroundLocalUnlockEnabled(context)) {
                return@hookAround chain.proceed()
            }
            access.withNormalThemeType(owner) { chain.proceed() }
        }
    }

    private fun installUseClickUnlock(
        module: HookModule,
        detailClass: Class<*>,
        access: ReaderThemeAccess,
    ): Boolean {
        val method = FeatureHooks.allDeclaredMethods(detailClass).singleOrNull { candidate ->
            candidate.name == "onClick" &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.contentEquals(arrayOf(View::class.java))
        } ?: return false
        return FeatureHooks.hookAround(
            module,
            method,
            "reader.background:use click",
            "qdhelper.reader_background.use_click",
        ) { chain ->
            val clicked = chain.getArg(0) as? View
            if (!SettingsRuntime.isReaderBackgroundLocalUnlockEnabled(clicked?.context) ||
                !ReaderBackgroundViewPolicy.isUseThemeButton(clicked)
            ) {
                return@hookAround chain.proceed()
            }
            access.withNormalThemeType(chain.thisObject) { chain.proceed() }
        }
    }

    private fun installDetailPreviewSave(
        module: HookModule,
        detailClass: Class<*>,
        entityClass: Class<*>,
        access: ReaderThemeAccess,
    ): Boolean {
        val method = FeatureHooks.allDeclaredMethods(detailClass).singleOrNull { candidate ->
            val parameters = candidate.parameterTypes
            Modifier.isStatic(candidate.modifiers) &&
                candidate.returnType == Void.TYPE &&
                parameters.size == 5 &&
                parameters[0] == detailClass &&
                View::class.java.isAssignableFrom(parameters[2]) &&
                !parameters[3].isPrimitive &&
                parameters[4] == Int::class.javaPrimitiveType
        } ?: return false
        return FeatureHooks.hookAfter(
            module,
            method,
            "reader.background:detail preview bind",
            "qdhelper.reader_background.detail_preview_bind",
        ) { chain, _ ->
            val page = chain.getArg(2) as? View ?: return@hookAfter
            val entity = chain.getArg(3)?.takeIf(entityClass::isInstance) ?: return@hookAfter
            val image = page.findNamedView("imageView") ?: return@hookAfter
            ReaderBackgroundImageSaver.bind(image, access.saveTarget(entity, detail = true))
        }
    }

    private fun installListSaveHook(
        module: HookModule,
        classLoader: ClassLoader,
        adapterName: String,
        entityClass: Class<*>,
        access: ReaderThemeAccess,
        suffix: String,
    ): Boolean {
        val adapterClass = FeatureHooks.findClassOrNull(adapterName, classLoader) ?: return false
        val bind = FeatureHooks.allDeclaredMethods(adapterClass).singleOrNull { candidate ->
            candidate.name == "onBindContentItemViewHolder" &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.size == 2 &&
                !candidate.parameterTypes[0].isPrimitive &&
                candidate.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return false
        val itemGetter = FeatureHooks.methodsInHierarchy(adapterClass).singleOrNull { candidate ->
            candidate.name == "getItem" &&
                candidate.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                entityClass.isAssignableFrom(candidate.returnType)
        } ?: return false
        return FeatureHooks.hookAfter(
            module,
            bind,
            "reader.background:$suffix image bind",
            "qdhelper.reader_background.${suffix}_image_bind",
        ) { chain, _ ->
            val position = chain.getArg(1) as? Int ?: return@hookAfter
            val entity = runCatching { itemGetter.invoke(chain.thisObject, position) }
                .getOrNull()
                ?.takeIf(entityClass::isInstance)
                ?: return@hookAfter
            val itemView = chain.getArg(0).itemViewOrNull() ?: return@hookAfter
            val image = itemView.findNamedView("ivThemePic") ?: return@hookAfter
            ReaderBackgroundImageSaver.bind(image, access.saveTarget(entity, detail = false))
        }
    }

    private fun Class<*>.uniqueNoArgVoid(name: String): Method? =
        FeatureHooks.allDeclaredMethods(this).singleOrNull { candidate ->
            candidate.name == name && candidate.returnType == Void.TYPE && candidate.parameterTypes.isEmpty()
        }

    private fun View.findNamedView(name: String): View? {
        val resourceId = resources.getIdentifier(name, "id", context.packageName)
        return resourceId.takeIf { it != 0 }?.let(::findViewById)
    }

    private fun Any?.itemViewOrNull(): View? {
        val holder = this ?: return null
        var type: Class<*>? = holder.javaClass
        while (type != null && type != Any::class.java) {
            val field = FeatureHooks.allDeclaredFields(type).singleOrNull { candidate ->
                candidate.name == "itemView" && View::class.java.isAssignableFrom(candidate.type)
            }
            if (field != null) return FeatureHooks.read(field, holder) as? View
            type = type.superclass
        }
        return null
    }

    private const val LOCAL_HAVE_STATUS = 1
}

internal object ReaderBackgroundViewPolicy {
    fun isUseThemeButton(view: View?): Boolean {
        if (view == null || view.id == View.NO_ID) return false
        return runCatching { view.resources.getResourceEntryName(view.id) == "btnUseTheme" }.getOrDefault(false)
    }
}

internal class ReaderThemeAccess(
    private val detailEntityField: java.lang.reflect.Field,
    private val getHaveStatus: Method,
    private val setHaveStatus: Method,
    private val getThemeType: Method,
    private val setThemeType: Method,
    private val getThemeId: Method,
    private val getThemeName: Method,
    private val getImageUrl: Method,
    private val getPreviewImage: Method,
) {
    fun <T> withHaveStatus(owner: Any?, value: Int, block: () -> T): T {
        val entity = entity(owner) ?: return block()
        val original = (FeatureHooks.invoke(getHaveStatus, entity) as? Number)?.toInt() ?: return block()
        if (original != 0) return block()
        FeatureHooks.invoke(setHaveStatus, entity, value)
        return try {
            block()
        } finally {
            FeatureHooks.invoke(setHaveStatus, entity, original)
        }
    }

    fun <T> withNormalThemeType(owner: Any?, block: () -> T): T {
        val entity = entity(owner) ?: return block()
        val original = (FeatureHooks.invoke(getThemeType, entity) as? Number)?.toLong() ?: return block()
        if (original != MEMBER_THEME_TYPE) return block()
        FeatureHooks.invoke(setThemeType, entity, NORMAL_THEME_TYPE)
        return try {
            block()
        } finally {
            FeatureHooks.invoke(setThemeType, entity, original)
        }
    }

    fun saveTarget(entity: Any, detail: Boolean): ReaderBackgroundSaveTarget? {
        val url = listOfNotNull(
            if (detail) FeatureHooks.invoke(getPreviewImage, entity) as? String else null,
            FeatureHooks.invoke(getImageUrl, entity) as? String,
        ).firstOrNull { it.isNotBlank() } ?: return null
        return ReaderBackgroundSaveTarget(
            url = url.trim(),
            themeId = (FeatureHooks.invoke(getThemeId, entity) as? Number)?.toLong() ?: 0L,
            themeName = (FeatureHooks.invoke(getThemeName, entity) as? String).orEmpty().trim(),
        )
    }

    private fun entity(owner: Any?): Any? = owner?.let { FeatureHooks.read(detailEntityField, it) }

    companion object {
        fun resolve(entityClass: Class<*>, detailClass: Class<*>): ReaderThemeAccess? {
            val detailField = FeatureHooks.allDeclaredFields(detailClass).singleOrNull { field ->
                field.type == entityClass
            } ?: return null
            fun method(name: String, returnType: Class<*>, vararg parameterTypes: Class<*>): Method? =
                FeatureHooks.allDeclaredMethods(entityClass).singleOrNull { candidate ->
                    candidate.name == name &&
                        candidate.returnType == returnType &&
                        candidate.parameterTypes.contentEquals(parameterTypes)
                }
            return ReaderThemeAccess(
                detailEntityField = detailField,
                getHaveStatus = method("getHaveStatus", Int::class.javaPrimitiveType!!) ?: return null,
                setHaveStatus = method("setHaveStatus", Void.TYPE, Int::class.javaPrimitiveType!!) ?: return null,
                getThemeType = method("getThemeType", Long::class.javaPrimitiveType!!) ?: return null,
                setThemeType = method("setThemeType", Void.TYPE, Long::class.javaPrimitiveType!!) ?: return null,
                getThemeId = method("getThemeId", Long::class.javaPrimitiveType!!) ?: return null,
                getThemeName = method("getThemeName", String::class.java) ?: return null,
                getImageUrl = method("getImageUrl", String::class.java) ?: return null,
                getPreviewImage = method("getPreviewImage", String::class.java) ?: return null,
            )
        }

        private const val MEMBER_THEME_TYPE = 102L
        private const val NORMAL_THEME_TYPE = 101L
    }
}
