package com.xiyunmn.qdhelper.feature.pagecustomization

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.ActivityLifecycleCallbacks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureResources
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.PageCustomizationTargetKeys

object PageCustomizationFeature : InstallableFeature {
    override val name: String = "PageCustomizationFeature"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val subscriptions = mutableListOf<AutoCloseable>()

    init {
        FeatureResources.register("page-customization") {
            synchronized(subscriptions) {
                subscriptions.forEach { runCatching(it::close) }
                subscriptions.clear()
            }
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
        installedHooks += installMineDataHooks(module, classLoader, processName, phase)
        installedHooks += MinePageAutoRefreshCleaner.install(module, classLoader, processName, phase)
        installedHooks += installSelectionDataHooks(module, classLoader, phase)
        installedHooks += installSelectionLoadMoreGate(module, classLoader, phase)
        installedHooks += BookDetailPageCustomization.install(module, classLoader, phase)
        subscribeActivityCallbacks()
        FeatureHooks.i(
            "Page customization phase finished: phase=$phase, process=$processName, installedHooks=$installedHooks",
        )
    }

    fun applyFromSettings(activity: Activity) {
        handlePendingPageScan(UiFeatureSupport.rootViewOf(activity))
    }

    private fun subscribeActivityCallbacks() {
        synchronized(subscriptions) {
            if (subscriptions.isNotEmpty()) return
            ActivityLifecycleCallbacks.subscribe(
                owner = OwnerId("page-customization"),
                id = "page-customization.pending-scan.resume",
                resumed = ::handleActivity,
            )?.let(subscriptions::add)
            ActivityLifecycleCallbacks.subscribe(
                owner = OwnerId("page-customization"),
                id = "page-customization.pending-scan.focus",
                windowFocusChanged = { activity, hasFocus -> if (hasFocus) handleActivity(activity) },
            )?.let(subscriptions::add)
        }
    }

    private fun handleActivity(activity: Activity) {
        if (activity.javaClass.name == HostSymbols.Classes.MAIN_GROUP_ACTIVITY) {
            handlePendingPageScan(UiFeatureSupport.rootViewOf(activity))
        }
    }

    private fun installMineDataHooks(
        module: HookModule,
        classLoader: ClassLoader,
        processName: String,
        phase: String,
    ): Int {
        var installedHooks = 0
        val holderClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.ACCOUNT_FUNCTION_HOLDER, classLoader)
        if (holderClass == null) {
            FeatureHooks.w("Page custom mine hook skipped: AccountFunctionHolder not found")
        } else {
            val method = HostTargetResolver.resolveMethod(
                context = HostSessionStore.contextOrNull(),
                classLoader = classLoader,
                processName = processName,
                key = PageCustomizationTargetKeys.mineHolderBindData,
            )
            if (method == null) {
                FeatureHooks.w("Page custom mine hook skipped: bindData(bean) not found")
            } else {
                installedHooks += UiFeatureSupport.installAround(
                    module = module,
                    id = "ui.pageCustom.mine.bindData",
                    method = method,
                    hookId = "qdhelper.ui.page_custom_mine_bind_data",
                    phase = phase,
                ) { chain ->
                    val context = UiFeatureSupport.rootViewOf(chain.thisObject)?.context
                        ?: HostSessionStore.contextOrNull()
                    val data = chain.args.getOrNull(0)
                    PageCustomization.consumePendingMineScanFromSource(context, data)
                        ?.let { notifyPageScanResult(context, it) }
                    PageCustomization.filterMineSource(context, data)
                    chain.proceed()
                }
            }
        }

        val fragmentClass = FeatureHooks.findClassOrNull(
            HostSymbols.Classes.USER_ACCOUNT_REBORN_FRAGMENT,
            classLoader,
        )
        if (fragmentClass == null) {
            FeatureHooks.w("Page custom mine source hook skipped: QDUserAccountRebornFragment not found")
        } else {
            val method = HostTargetResolver.resolveMethod(
                context = HostSessionStore.contextOrNull(),
                classLoader = classLoader,
                processName = processName,
                key = PageCustomizationTargetKeys.mineProcessAccountItem,
            )
            if (method == null) {
                FeatureHooks.w("Page custom mine source hook skipped: processAccountItem(bean) not found")
            } else {
                installedHooks += UiFeatureSupport.installAround(
                    module = module,
                    id = "ui.pageCustom.mine.processAccountItem",
                    method = method,
                    hookId = "qdhelper.ui.page_custom_mine_process_account_item",
                    phase = phase,
                ) { chain ->
                    val context = UiFeatureSupport.rootViewOf(chain.thisObject)?.context
                        ?: HostSessionStore.contextOrNull()
                    val data = chain.args.getOrNull(0)
                    val result = chain.proceed()
                    (PageCustomization.consumePendingMineScanFromSource(context, result)
                        ?: PageCustomization.consumePendingMineScanFromSource(context, data))
                        ?.let { notifyPageScanResult(context, it) }
                    PageCustomization.filterMinePageResult(context, result)
                }
            }
        }
        return installedHooks
    }

    private fun installSelectionDataHooks(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val adapterClass = FeatureHooks.findClassOrNull(HostSymbols.Classes.MORPHING_ADAPTER, classLoader)
            ?: return 0.also { FeatureHooks.w("Page custom selection hook skipped: MorphingAdapter not found") }
        var installedHooks = 0
        listOf("updateList", "updateListNoDiff", "appendList", "addItems").forEach { methodName ->
            FeatureHooks.declaredMethodsNamed(adapterClass, methodName)
                .filter { candidate ->
                    candidate.parameterTypes.size == 1 &&
                        List::class.java.isAssignableFrom(candidate.parameterTypes[0])
                }
                .forEach { method ->
                    installedHooks += UiFeatureSupport.installAround(
                        module = module,
                        id = "ui.pageCustom.selection.$methodName",
                        method = method,
                        hookId = "qdhelper.ui.page_custom_selection_$methodName",
                        phase = phase,
                    ) { chain ->
                        val result = SelectionPageCustomization.processAdapterList(
                            context = HostSessionStore.contextOrNull(),
                            adapter = chain.thisObject,
                            source = chain.args.getOrNull(0),
                            isAppend = methodName == "appendList" || methodName == "addItems",
                        ) { scanResult ->
                            notifyPageScanResult(HostSessionStore.contextOrNull(), scanResult)
                        }
                        val replacement = result.replacementList
                        if (replacement != null) {
                            chain.proceed(arrayOf(replacement))
                        } else {
                            chain.proceed()
                        }
                    }
                }
        }
        return installedHooks
    }

    private fun installSelectionLoadMoreGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(HostSymbols.Classes.MORPHING_FRAGMENT, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, "fetchMorePageData")
        if (method == null) {
            FeatureHooks.w("Selection load-more gate skipped: fetchMorePageData not found")
            return 0
        }
        return UiFeatureSupport.installReturnValue(
            module = module,
            id = "ui.selection.loadMoreGate",
            method = method,
            hookId = "qdhelper.ui.selection_load_more_gate",
            phase = phase,
            shouldBlock = { SettingsRuntime.isSelectionGuessWaterfallHidden(HostSessionStore.contextOrNull()) },
            action = "stop-load-more",
        ) { chain ->
            SelectionPageCustomization.stopLoadMore(chain.thisObject)
            null
        }
    }

    private fun handlePendingPageScan(root: android.view.View?) {
        root ?: return
        PageCustomization.consumePendingScanIfMatches(root)?.let { result ->
            Toast.makeText(root.context, "已扫描到 ${result.count} 项", Toast.LENGTH_SHORT).show()
        }
    }

    private fun notifyPageScanResult(context: android.content.Context?, result: PageCustomScanResult) {
        context ?: return
        mainHandler.post {
            Toast.makeText(context, "已扫描到 ${result.count} 项", Toast.LENGTH_SHORT).show()
        }
    }
}
