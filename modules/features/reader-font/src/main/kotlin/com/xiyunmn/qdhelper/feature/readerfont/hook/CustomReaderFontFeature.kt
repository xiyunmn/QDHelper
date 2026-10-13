package com.xiyunmn.qdhelper.feature.readerfont.hook

import android.graphics.Typeface
import android.view.View
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.readerfont.host.CustomReaderFontTypefaceProvider
import com.xiyunmn.qdhelper.feature.readerfont.importflow.CustomReaderFontImporter
import com.xiyunmn.qdhelper.feature.readerfont.ui.CustomReaderFontPanelInjector
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.shared.fonts.FontLibraryStore
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.ReaderFontTargetKeys
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost

object CustomReaderFontFeature : InstallableFeature {
    override val name: String = "CustomReaderFontFeature"

    private const val READER_FONT_MANAGER = QDReaderHost.Classes.READER_FONT_MANAGER
    private const val READER_MENU_FONT_PANEL = QDReaderHost.Classes.READER_FONT_PANEL
    private const val READER_MENU_FONT_ADAPTER = QDReaderHost.Classes.READER_FONT_PANEL_ADAPTER
    private const val READER_FONT_SELECTION_MATCHER = QDReaderHost.Classes.READER_FONT_MATCHER
    private const val CUSTOM_FONT_ID_PREFIX = "qdhelper_custom_reader_font:"

    init {
        FeatureResources.register("CustomReaderFontFeature") {
            CustomReaderFontTypefaceProvider.clearCache()
        }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        if (!QDReaderHost.isMainProcess(processName)) {
            FeatureHooks.i("Custom reader font skipped: process=$processName, phase=$phase")
            return
        }
        var installedHooks = 0
        registerActivityResultHandler()
        installedHooks += hookReaderFontManager(module, classLoader)
        installedHooks += hookReaderEngineTypefaceGetter(module, classLoader, processName)
        installedHooks += hookReaderFontSelectionMatcher(module, classLoader)
        installedHooks += hookReaderFontPanel(module, classLoader)
        installedHooks += hookReaderFontAdapter(module, classLoader)
        FeatureHooks.i("Custom reader font phase finished: phase=$phase, installedHooks=$installedHooks")
    }

    private fun registerActivityResultHandler() {
        val result = ActivityCallbacks.registerResult(
            OwnerId("reader-font"),
            CustomReaderFontImporter.REQUEST_CODE,
        ) { activity, resultCode, data ->
            CustomReaderFontImporter.handleActivityResult(
                activity,
                CustomReaderFontImporter.REQUEST_CODE,
                resultCode,
                data,
            )
        }
        when (result) {
            is com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry.RegistrationResult.Registered ->
                FeatureHooks.i("Custom reader font activityResult handler registered")

            is com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry.RegistrationResult.AlreadyRegistered ->
                FeatureHooks.i("Custom reader font activityResult handler already registered")

            is com.xiyunmn.qdhelper.hook.activity.ActivityResultRegistry.RegistrationResult.RequestCodeConflict ->
                FeatureHooks.w("Custom reader font activityResult registration rejected: $result")
        }
    }

    private fun hookReaderFontManager(module: HookModule, classLoader: ClassLoader): Int {
        val clazz = FeatureHooks.findClassOrNull(READER_FONT_MANAGER, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, "loadFontTypeSync", String::class.java)
            ?: FeatureHooks.declaredMethodsNamed(clazz, "loadFontTypeSync").firstOrNull { candidate ->
                candidate.parameterTypes.size == 1 && candidate.returnType == Typeface::class.java
            }
            ?: return 0
        return hookTypefaceReturn(module, method, "customReaderFont.manager.loadFontTypeSync")
    }

    private fun hookReaderEngineTypefaceGetter(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
    ): Int {
        val method = HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = processName,
            key = ReaderFontTargetKeys.engineTypefaceGetter,
        )
            ?: return 0
        return hookTypefaceReturn(module, method, "customReaderFont.engine.O")
    }

    private fun hookReaderFontPanel(module: HookModule, classLoader: ClassLoader): Int {
        val clazz = FeatureHooks.findClassOrNull(READER_MENU_FONT_PANEL, classLoader) ?: return 0
        var count = 0
        FeatureHooks.findDeclaredMethodOrNull(clazz, "setupThemeStyle")
            ?.let { method ->
                if (FeatureHooks.hookAfter(module, method, "CustomReaderFont.ReadMenuFontPanel.setupThemeStyle", "custom_reader_font.panel.theme") { chain, _ ->
                        injectPanelLater(chain.thisObject)
                    }) {
                    count += 1
                }
            }
        FeatureHooks.declaredMethodsNamed(clazz, "show")
            .filter { method -> method.returnType == Void.TYPE }
            .forEachIndexed { index, method ->
                if (FeatureHooks.hookAfter(module, method, "CustomReaderFont.ReadMenuFontPanel.show#$index", "custom_reader_font.panel.show.$index") { chain, _ ->
                        showPanelLater(chain.thisObject)
                    }) {
                    count += 1
                }
            }
        FeatureHooks.declaredMethodsNamed(clazz, "dismiss")
            .filter { method -> method.returnType == Void.TYPE }
            .forEachIndexed { index, method ->
                if (FeatureHooks.hookAfter(module, method, "CustomReaderFont.ReadMenuFontPanel.dismiss#$index", "custom_reader_font.panel.dismiss.$index") { chain, _ ->
                        (chain.thisObject as? View)?.let(CustomReaderFontPanelInjector::restoreHostBottomBar)
                    }) {
                    count += 1
                }
            }
        FeatureHooks.declaredMethodsNamed(clazz, "onFontList")
            .filter { method -> method.returnType == Void.TYPE }
            .forEachIndexed { index, method ->
                if (FeatureHooks.hookAfter(module, method, "CustomReaderFont.ReadMenuFontPanel.onFontList#$index", "custom_reader_font.panel.onFontList.$index") { chain, _ ->
                        injectPanelLater(chain.thisObject)
                    }) {
                    count += 1
                }
            }
        FeatureHooks.declaredMethodsNamed(clazz, "setFontType")
            .filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes.getOrNull(1) == Integer.TYPE
            }
            .forEachIndexed { index, method ->
                if (FeatureHooks.hookAround(module, method, "CustomReaderFont.ReadMenuFontPanel.setFontType#$index", "custom_reader_font.panel.setFontType.$index") { chain ->
                        val panel = chain.thisObject as? View ?: return@hookAround chain.proceed()
                        val fontItem = chain.args.getOrNull(0)
                        val customPath = customFontPath(fontItem)
                        if (customPath != null) {
                            CustomReaderFontPanelInjector.selectCustomFont(panel, customPath)
                            return@hookAround null
                        }
                        if (isSelectableHostFontItem(fontItem)) {
                            CustomReaderFontPanelInjector.clearCustomSelection(panel)
                        }
                        chain.proceed()
                    }) {
                    count += 1
                }
            }
        return count
    }

    private fun hookReaderFontSelectionMatcher(module: HookModule, classLoader: ClassLoader): Int {
        val clazz = FeatureHooks.findClassOrNull(READER_FONT_SELECTION_MATCHER, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(
            clazz,
            "b",
            FeatureHooks.findClassOrNull(QDReaderHost.Classes.READER_FONT_ITEM, classLoader) ?: return 0,
        ) ?: FeatureHooks.allDeclaredMethods(clazz).firstOrNull { candidate ->
            candidate.returnType == java.lang.Boolean.TYPE && candidate.parameterTypes.size == 1
        }
            ?: return 0
        return if (FeatureHooks.hookAround(module, method, "CustomReaderFont.readerFontSelectionMatcher", "custom_reader_font.selection_matcher") { chain ->
                val localState = localFontSelectionState(chain.args.getOrNull(0))
                localState ?: chain.proceed()
            }) {
            1
        } else {
            0
        }
    }

    private fun hookReaderFontAdapter(module: HookModule, classLoader: ClassLoader): Int {
        val clazz = FeatureHooks.findClassOrNull(READER_MENU_FONT_ADAPTER, classLoader) ?: return 0
        var count = 0
        FeatureHooks.allDeclaredMethods(clazz)
            .filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 3 &&
                    method.parameterTypes.getOrNull(1) == Integer.TYPE &&
                    method.parameterTypes.firstOrNull()?.name?.endsWith("RecyclerHolder") == true
            }
            .forEachIndexed { index, method ->
                if (FeatureHooks.hookAfter(module, method, "CustomReaderFont.FontAdapter.bind#$index", "custom_reader_font.adapter.bind.$index") { chain, _ ->
                        CustomReaderFontPanelInjector.bindAdapterItem(
                            holder = chain.args.getOrNull(0),
                            item = chain.args.getOrNull(2),
                        )
                    }) {
                    count += 1
                }
            }
        return count
    }

    private fun injectPanelLater(target: Any?) {
        val view = target as? View ?: return
        view.post {
            CustomReaderFontPanelInjector.inject(view)
        }
    }

    private fun showPanelLater(target: Any?) {
        val view = target as? View ?: return
        CustomReaderFontPanelInjector.schedulePanelShown(view)
    }

    private fun isSelectableHostFontItem(item: Any?): Boolean {
        if (item == null) {
            return false
        }
        val id = callString(item, "getId").orEmpty()
        if (id.startsWith("qdhelper_custom_reader_font:")) {
            return false
        }
        val notDown = callBoolean(item, "isNotDown")
        val needUpdate = callBoolean(item, "needUpdate")
        return notDown != true && needUpdate != true
    }

    private fun customFontPath(item: Any?): String? {
        if (item == null) {
            return null
        }
        val id = callString(item, "getId").orEmpty()
        if (!id.startsWith(CUSTOM_FONT_ID_PREFIX)) {
            return null
        }
        return callString(item, "getFileName")?.takeIf { it.isNotBlank() }
    }

    private fun localFontSelectionState(item: Any?): Boolean? {
        if (item == null) {
            return null
        }
        val context = HostSessionStore.contextOrNull() ?: return null
        val id = callString(item, "getId").orEmpty()
        val customPath = if (id.startsWith(CUSTOM_FONT_ID_PREFIX)) {
            callString(item, "getFileName")?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (!SettingsRuntime.isCustomReaderFontEnabled(context)) {
            return if (customPath != null) false else null
        }
        val selectedPath = SettingsRuntime.customReaderFontPath(context).takeIf { it.isNotBlank() }
            ?: return if (customPath != null) false else null
        return customPath != null && FontLibraryStore.normalizePath(customPath) == FontLibraryStore.normalizePath(selectedPath)
    }

    private fun callString(target: Any, methodName: String): String? {
        return runCatching {
            target.javaClass.methods.firstOrNull { method -> method.name == methodName && method.parameterTypes.isEmpty() }
                ?.invoke(target) as? String
        }.getOrNull()
    }

    private fun callBoolean(target: Any, methodName: String): Boolean? {
        return runCatching {
            target.javaClass.methods.firstOrNull { method -> method.name == methodName && method.parameterTypes.isEmpty() }
                ?.invoke(target) as? Boolean
        }.getOrNull()
    }

    private fun hookTypefaceReturn(
        module: HookModule,
        method: java.lang.reflect.Method,
        id: String,
    ): Int {
        if (method.returnType != Typeface::class.java) {
            return 0
        }
        return if (FeatureHooks.hookAround(
                module = module,
                executable = method,
                feature = "CustomReaderFont.${method.declaringClass.name}.${method.name}",
                id = id,
            ) { chain ->
                CustomReaderFontTypefaceProvider.currentTypeface() ?: chain.proceed()
            }) {
            1
        } else {
            0
        }
    }
}
