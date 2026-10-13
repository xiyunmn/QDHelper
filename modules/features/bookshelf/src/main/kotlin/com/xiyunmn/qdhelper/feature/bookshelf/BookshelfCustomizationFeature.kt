package com.xiyunmn.qdhelper.feature.bookshelf

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols

object BookshelfCustomizationFeature : InstallableFeature {
    override val name: String = "BookshelfCustomizationFeature"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    init {
        FeatureResources.register("bookshelf-customization") {
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun install(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ) {
        var installedHooks = 0
        installedHooks += BookshelfCleaner.install(module, classLoader, phase)
        installedHooks += BookItemCleaner.install(module, classLoader, phase)
        installedHooks += BookshelfCardCleaner.install(module, classLoader, phase)
        installedHooks += DailyReadingGameAdCleaner.install(module, classLoader, phase)
        installedHooks += installFragmentApplyHooks(module, classLoader, phase)
        FeatureHooks.i(
            "Bookshelf customization phase finished: phase=$phase, process=$processName, installedHooks=$installedHooks",
        )
    }

    fun applyFromSettings(activity: Activity) {
        applyFromOwner(activity)
    }

    private fun installFragmentApplyHooks(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(HostSymbols.Classes.BOOKSHELF_REBORN_FRAGMENT, classLoader)
            ?: return 0
        var installedHooks = 0
        FeatureHooks.findDeclaredMethodOrNull(clazz, "onViewCreated", View::class.java, Bundle::class.java)
            ?.let { method ->
                installedHooks += UiFeatureSupport.installAfter(
                    module = module,
                    id = "ui.bookshelf.fragment.applyCustomUi.onViewCreated",
                    method = method,
                    hookId = "qdhelper.ui.bookshelf_apply_on_view_created",
                    phase = phase,
                ) { fragment -> applyFromOwner(fragment) }
            }
        return installedHooks
    }

    private fun applyFromOwner(owner: Any?): Boolean {
        val root = UiFeatureSupport.rootViewOf(owner) ?: return false
        scheduleApplyIfNeeded(root)
        return apply(root)
    }

    private fun scheduleApplyIfNeeded(root: View) {
        val context = HostSessionStore.contextOrNull()
        if (!SettingsRuntime.isBookshelfTopActivityAdHidden(context) &&
            !SettingsRuntime.isBookshelfLeftTopActivityCardHidden(context)
        ) {
            return
        }
        root.post { apply(root) }
        mainHandler.postDelayed({ apply(root) }, 300L)
        mainHandler.postDelayed({ apply(root) }, 1_200L)
    }

    private fun apply(root: View): Boolean {
        var changed = false
        changed = BookshelfCleaner.applyBookshelfTopActivityBackground(root) || changed
        changed = BookshelfCleaner.applyBookshelfLeftTopActivityCard(root) || changed
        return changed
    }
}
