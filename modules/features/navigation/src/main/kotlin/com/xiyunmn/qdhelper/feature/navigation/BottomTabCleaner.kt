package com.xiyunmn.qdhelper.feature.navigation

import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.NavigationTargetKeys
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols as QDReaderHost
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
/**
 * 底栏红点、我的角标和底栏显示顺序清理。
 */
object BottomTabCleaner {
    private const val MINE_TAB_TRACKER_POSITION = 4
    private const val MINE_TAB_PAGE_INDEX = 3

    // Layout and visibility changes can trigger nested host callbacks.
    @Volatile
    private var applying = false
    private val layouts = WeakHashMap<ViewGroup, WeakReference<NativeTabLayout>>()
    private val nativeRebuilt = WeakHashMap<View, Boolean>()

    fun closeLayouts() {
        layouts.values.mapNotNull { it.get() }.forEach { it.close() }
        layouts.clear()
        nativeRebuilt.clear()
    }

    // Authoritative tab identity comes from the adapter tab-param model, resolved by named host targets:
    // trackerPosition p() (shelf=1/selection=2/discover=3/mine=4) and the operation marker l()
    // (==2 only for the cloud activity tab). These are stable and, unlike the view title, do NOT
    // change when the host turns the current tab into a "scroll to top" button on scroll.
    private val trackerParamKey = NavigationTargetKeys.trackerPosition
    private val operationMarkerParamKey = NavigationTargetKeys.operationMarker

    fun install(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        var installedHooks = 0
        installedHooks += installMainTabRedPointGate(module, classLoader, phase)
        installedHooks += installTabParamGetters(module, classLoader, phase)
        installedHooks += installAccountTabUnreadGates(module, classLoader, phase)
        installedHooks += installPagerTabRebuildHook(module, classLoader, phase)
        return installedHooks
    }

    private fun installMainTabRedPointGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.MAIN_GROUP_ACTIVITY, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(
            clazz,
            "setPageRedPoint",
            Int::class.javaPrimitiveType ?: Int::class.java,
            Boolean::class.javaPrimitiveType ?: Boolean::class.java,
        ) ?: return 0

        return UiFeatureSupport.installReturnValue(
            module = module,
            id = "ui.bottomTab.setPageRedPoint",
            method = method,
            hookId = "qdhelper.p2.ui.bottom_tab_set_red_point",
            phase = phase,
            shouldBlock = { chain ->
                val position = chain.args.getOrNull(0) as? Int ?: -1
                shouldHideBottomTabRedDot(position)
            },
            action = "clear-and-noop",
        ) { chain ->
            clearTabParams(chain.thisObject, mineOnly = !SettingsRuntime.isBottomTabRedDotHidden(HostSessionStore.contextOrNull()))
            null
        }
    }

    private fun installTabParamGetters(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.MAINTAB_PARAM, classLoader)
        var installedHooks = 0

        installedHooks += installTabParamGetter(
            module = module,
            method = resolveMethod(
                classLoader = classLoader,
                directClass = clazz,
                directName = "i",
                key = NavigationTargetKeys.redPoint,
            ),
            id = "ui.bottomTab.param.redPoint",
            hookId = "qdhelper.p2.ui.bottom_tab_param_red_point",
            phase = phase,
            action = "return-0",
        ) { 0 }
        installedHooks += installTabParamGetter(
            module = module,
            method = resolveMethod(
                classLoader = classLoader,
                directClass = clazz,
                directName = "n",
                key = NavigationTargetKeys.tagText,
            ),
            id = "ui.bottomTab.param.tagText",
            hookId = "qdhelper.p2.ui.bottom_tab_param_tag_text",
            phase = phase,
            action = "return-empty",
        ) { "" }
        installedHooks += installTabParamGetter(
            module = module,
            method = resolveMethod(
                classLoader = classLoader,
                directClass = clazz,
                directName = "g",
                key = NavigationTargetKeys.operationRedDot,
            ),
            id = "ui.bottomTab.param.operationRedDot",
            hookId = "qdhelper.p2.ui.bottom_tab_param_operation_red_dot",
            phase = phase,
            action = "return-null",
        ) { null }

        return installedHooks
    }

    private fun installAccountTabUnreadGates(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.ACCOUNT_TAB_RED_DOT_HANDLER, classLoader) ?: return 0
        var installedHooks = 0

        installedHooks += installAccountHandlerNoOp(
            module = module,
            method = FeatureHooks.findDeclaredMethodOrNull(clazz, "checkAccountTabUnreadMsg"),
            id = "ui.accountTab.checkUnread",
            hookId = "qdhelper.p2.ui.account_tab_check_unread",
            phase = phase,
            action = "clear-and-noop",
            clear = true,
        )
        installedHooks += installAccountHandlerNoOp(
            module = module,
            method = FeatureHooks.declaredMethodsNamed(clazz, "handleAccountTabUnreadMsgEvent")
                .singleOrNull { candidate -> candidate.parameterTypes.size == 1 },
            id = "ui.accountTab.unreadEvent",
            hookId = "qdhelper.p2.ui.account_tab_unread_event",
            phase = phase,
            action = "noop-event",
            clear = false,
        )
        installedHooks += installAccountHandlerNoOp(
            module = module,
            method = FeatureHooks.declaredMethodsNamed(clazz, "updateTabTagText")
                .singleOrNull { candidate ->
                    !Modifier.isStatic(candidate.modifiers) && candidate.returnType == Void.TYPE &&
                        isAccountTagTextSignature(candidate.parameterTypes.map { it.name })
                },
            id = "ui.accountTab.updateTagText",
            hookId = "qdhelper.p2.ui.account_tab_update_tag_text",
            phase = phase,
            action = "clear-and-noop",
            clear = true,
        )
        installedHooks += installAccountBooleanGetter(
            module = module,
            method = FeatureHooks.findDeclaredMethodOrNull(clazz, "getHasAdvBubbleText"),
            id = "ui.accountTab.hasBubbleText",
            hookId = "qdhelper.p2.ui.account_tab_get_bubble",
            phase = phase,
        )
        installedHooks += installAccountBooleanGetter(
            module = module,
            method = FeatureHooks.findDeclaredMethodOrNull(clazz, "getHasDotPositionMark"),
            id = "ui.accountTab.hasDotMark",
            hookId = "qdhelper.p2.ui.account_tab_get_dot_mark",
            phase = phase,
        )
        installedHooks += installAccountBooleanSetter(
            module = module,
            method = FeatureHooks.findDeclaredMethodOrNull(
                clazz,
                "setHasAdvBubbleText",
                Boolean::class.javaPrimitiveType ?: Boolean::class.java,
            ),
            id = "ui.accountTab.setBubbleText",
            hookId = "qdhelper.p2.ui.account_tab_set_bubble",
            phase = phase,
        )
        installedHooks += installAccountBooleanSetter(
            module = module,
            method = FeatureHooks.findDeclaredMethodOrNull(
                clazz,
                "setHasDotPositionMark",
                Boolean::class.javaPrimitiveType ?: Boolean::class.java,
            ),
            id = "ui.accountTab.setDotMark",
            hookId = "qdhelper.p2.ui.account_tab_set_dot_mark",
            phase = phase,
        )

        return installedHooks
    }

    private fun installPagerTabRebuildHook(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(QDReaderHost.Classes.PAGER_SLIDING_TAB_STRIP, classLoader)
        val method = resolveMethod(classLoader, clazz, "s", NavigationTargetKeys.pagerRebuild) ?: return 0
        return UiFeatureSupport.installAfter(
            module = module,
            id = "ui.bottomTab.applyCustom.afterRebuild",
            method = method,
            hookId = "qdhelper.ui.bottom_tab_apply_after_rebuild",
            phase = phase,
        ) { tabStrip ->
            val root = tabStrip as? View
            if (root != null) nativeRebuilt[root] = true
            val badgesChanged = clearRenderedBadgesAfterRebuild(root)
            applyBottomTabCustom(root) || badgesChanged
        }
    }

    private fun installTabParamGetter(
        module: HookModule,
        method: Method?,
        id: String,
        hookId: String,
        phase: String,
        action: String,
        value: () -> Any?,
    ): Int {
        method ?: return 0
        return UiFeatureSupport.installReturnValue(
            module = module,
            id = id,
            method = method,
            hookId = hookId,
            phase = phase,
            shouldBlock = { chain -> shouldHideTabParam(chain.thisObject) },
            action = action,
        ) {
            value()
        }
    }

    private fun installAccountHandlerNoOp(
        module: HookModule,
        method: Method?,
        id: String,
        hookId: String,
        phase: String,
        action: String,
        clear: Boolean,
    ): Int {
        method ?: return 0
        return UiFeatureSupport.installReturnValue(
            module = module,
            id = id,
            method = method,
            hookId = hookId,
            phase = phase,
            shouldBlock = { _ -> hideAccountBadges() },
            action = action,
        ) { chain ->
            if (clear) {
                clearAccountHandlerState(chain.thisObject)
            }
            null
        }
    }

    private fun installAccountBooleanGetter(
        module: HookModule,
        method: Method?,
        id: String,
        hookId: String,
        phase: String,
    ): Int {
        method ?: return 0
        return UiFeatureSupport.installReturnValue(
            module = module,
            id = id,
            method = method,
            hookId = hookId,
            phase = phase,
            shouldBlock = { _ -> hideAccountBadges() },
            action = "return-false",
        ) {
            false
        }
    }

    private fun installAccountBooleanSetter(
        module: HookModule,
        method: Method?,
        id: String,
        hookId: String,
        phase: String,
    ): Int {
        method ?: return 0
        return UiFeatureSupport.installReturnValue(
            module = module,
            id = id,
            method = method,
            hookId = hookId,
            phase = phase,
            shouldBlock = { _ -> hideAccountBadges() },
            action = "force-false",
        ) { chain ->
            setBooleanField(chain.thisObject, fieldNameForSetter(method.name), false)
            null
        }
    }

    private fun hideAccountBadges(): Boolean {
        val context = HostSessionStore.contextOrNull()
        return SettingsRuntime.isBottomTabRedDotHidden(context) || SettingsRuntime.isAccountTabRedDotHidden(context)
    }

    /** Existing views may predate a settings toggle/hot reload. Rebind only when a badge is visible. */
    fun refreshVisibleBadges(root: View): Boolean {
        if (!hideAccountBadges()) return false
        val strip = resolveTabStrip(root) as? ViewGroup ?: return false
        val row = strip.getChildAt(0) as? ViewGroup ?: return false
        if (!row.isShown) return false
        val all = SettingsRuntime.isBottomTabRedDotHidden(HostSessionStore.contextOrNull())
        val candidates = if (all) listOf(row) else scanLiveTabs(row)?.filter { it.key == "mine" }?.map { it.view }.orEmpty()
        if (candidates.none(::hasVisibleBadge)) return false
        return runCatching {
            resolveRuntimeMethod(strip.javaClass, "s", NavigationTargetKeys.pagerRebuild).invoke(strip)
            true
        }.onFailure { FeatureHooks.w("Bottom tab badge refresh failed: ${it.javaClass.name}") }.getOrDefault(false)
    }

    internal fun hasVisibleBadge(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val name = if (view.id == View.NO_ID) null else runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        if (name in BADGE_VIEW_NAMES && view.visibility == View.VISIBLE) return true
        return view is ViewGroup && (0 until view.childCount).any { hasVisibleBadge(view.getChildAt(it)) }
    }

    /** Runs before module reordering: fresh native children match the model list by index. */
    private fun clearRenderedBadgesAfterRebuild(root: View?): Boolean {
        val context = HostSessionStore.contextOrNull()
        val all = SettingsRuntime.isBottomTabRedDotHidden(context)
        if (root == null || (!all && !SettingsRuntime.isAccountTabRedDotHidden(context))) return false
        val row = (root as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return false
        val params = if (all) null else resolveMainGroupActivity(root)?.let(::readTabParams)
        if (!all && params?.size != row.childCount) return false
        var changed = false
        for (index in 0 until row.childCount) {
            if (!all && !isMineTabParam(params?.get(index))) continue
            BADGE_VIEW_NAMES.forEach { name ->
                UiFeatureSupport.findChildViewByIdName(row.getChildAt(index), name)?.let { badge ->
                    if (badge.visibility != View.GONE) { badge.visibility = View.GONE; changed = true }
                }
            }
        }
        return changed
    }

    private val BADGE_VIEW_NAMES = setOf("dotImg", "tagView", "operationRedDot")

    private fun shouldHideBottomTabRedDot(position: Int): Boolean {
        val context = HostSessionStore.contextOrNull()
        return SettingsRuntime.isBottomTabRedDotHidden(context) ||
            (position == MINE_TAB_PAGE_INDEX && SettingsRuntime.isAccountTabRedDotHidden(context))
    }

    private fun shouldHideTabParam(tabParam: Any?): Boolean {
        val context = HostSessionStore.contextOrNull()
        return SettingsRuntime.isBottomTabRedDotHidden(context) ||
            (SettingsRuntime.isAccountTabRedDotHidden(context) && isMineTabParam(tabParam))
    }

    private fun isMineTabParam(tabParam: Any?): Boolean {
        tabParam ?: return false
        return runCatching {
            val method = resolveRuntimeMethod(
                targetClass = tabParam.javaClass,
                directName = "p",
                key = trackerParamKey,
            )
            method.invoke(tabParam) == MINE_TAB_TRACKER_POSITION
        }.getOrDefault(false)
    }

    private fun clearAccountHandlerState(handler: Any?) {
        handler ?: return
        setBooleanField(handler, "hasAdvBubbleText", false)
        setBooleanField(handler, "hasDotPositionMark", false)
        setBooleanField(handler, "isTotalCountRequesting", false)
        setIntField(handler, "lastUnReadCount", 0)
        val activity = FeatureHooks.findFieldOrNull(handler.javaClass, "mActivity")
            ?.let { field -> (FeatureHooks.read(field, handler) as? WeakReference<*>)?.get() }
        clearTabParams(activity, mineOnly = true)
    }

    private fun clearTabParams(activity: Any?, mineOnly: Boolean) {
        activity ?: return
        runCatching {
            val adapter = activity.javaClass.getMethod("getTabsPagerAdapter").invoke(activity) ?: return@runCatching
            val tabParams = resolveRuntimeMethod(
                adapter.javaClass,
                "j",
                NavigationTargetKeys.adapterItems,
            ).invoke(adapter) as? List<*>
                ?: return@runCatching
            tabParams.forEach { param ->
                if (param != null && (!mineOnly || isMineTabParam(param))) {
                    clearTabParam(param)
                }
            }
            activity.javaClass.getMethod("getTabs").invoke(activity)?.let { tabs ->
                resolveRuntimeMethod(tabs.javaClass, "s", NavigationTargetKeys.pagerRebuild)
                    .invoke(tabs)
            }
        }.onFailure { throwable ->
            FeatureHooks.w("P2 clear tab params failed: ${throwable.javaClass.name}")
        }
    }

    private fun clearTabParam(tabParam: Any) {
        runCatching {
            resolveRuntimeMethod(
                tabParam.javaClass,
                "z",
                NavigationTargetKeys.setRedPoint,
                Int::class.javaPrimitiveType ?: Int::class.java,
            )
                .invoke(tabParam, 0)
            resolveRuntimeMethod(
                tabParam.javaClass,
                "E",
                NavigationTargetKeys.setTagText,
                String::class.java,
            )
                .invoke(tabParam, "")
            (FeatureHooks.allDeclaredMethods(tabParam.javaClass).firstOrNull { method ->
                method.name == "y" && method.parameterTypes.size == 1
            }
                ?: HostTargetResolver.resolveMethod(
                    context = HostSessionStore.contextOrNull(),
                    classLoader = tabParam.javaClass.classLoader ?: return@runCatching,
                    processName = HostSessionStore.processName,
                    key = NavigationTargetKeys.setOperationRedDot,
                ))?.invoke(tabParam, null)
        }.onFailure { throwable ->
            FeatureHooks.w("P2 clear one tab param failed: ${throwable.javaClass.name}")
        }
    }

    /** Native indices remain unchanged; only layout positions and visibility are customized. */
    fun applyBottomTabCustom(root: View?): Boolean {
        if (root == null || applying) return false
        val context = HostSessionStore.contextOrNull()
        val custom = SettingsRuntime.isBottomTabCustomEnabled(context)
        val hideActivity = SettingsRuntime.isBottomTabHideActivityEnabled(context)
        val container = UiFeatureSupport.findChildViewByIdName(root, "tabs")?.let(::firstLinearChild)
            ?: UiFeatureSupport.findChildViewByIdName(root, "view_tab_title_title")?.let(::tabContainerFromTitle)
            ?: return false
        // A hot replacement may inherit physically reordered children and a modified page map
        // from the old engine. Recreate native closures/arrays once before taking ownership.
        val strip = resolveTabStrip(root) ?: return false
        if ((custom || hideActivity) && nativeRebuilt[strip] != true) {
            nativeRebuilt[strip] = true
            return runCatching {
                resolveRuntimeMethod(strip.javaClass, "s", NavigationTargetKeys.pagerRebuild).invoke(strip)
                true // The after-rebuild hook applies the layout to the fresh native children.
            }.onFailure {
                nativeRebuilt.remove(strip)
                FeatureHooks.w("Bottom tab native rebind failed: ${it.javaClass.name}")
            }.getOrDefault(false)
        }
        if (!custom && !hideActivity) {
            val previous = layouts.remove(container)?.get() ?: return false
            previous.close()
            return true
        }
        applying = true
        return try {
            val tabs = scanLiveTabs(container) ?: return false
            val previous = layouts[container]?.get()
            val layout = previous?.takeIf { it.matches() } ?: run {
                previous?.close()
                NativeTabLayout(container).also { layouts[container] = WeakReference(it) }
            }
            layout.apply(tabs, custom, SettingsRuntime.bottomTabOrder(context), SettingsRuntime.bottomTabHidden(context), hideActivity)
        } finally { applying = false }
    }

    /**
     * Resolve the live tab set from the adapter tab-param list (authoritative, title-independent).
     * Returns null when the host MainGroupActivity / param list is unavailable or not 1:1 with the
     * tab child views, so the caller leaves the bar untouched.
     */
    private fun scanLiveTabs(container: ViewGroup): List<LiveTab>? {
        val activity = resolveMainGroupActivity(container) ?: return null
        val params = readTabParams(activity) ?: return null
        if (params.size != container.childCount) {
            return null
        }
        return params.mapIndexed { index, param ->
            val view = container.getChildAt(index)
            val tracker = param?.let { paramInt(it, "p", trackerParamKey) }
            val marker = param?.let { paramInt(it, "l", operationMarkerParamKey) }
            LiveTab(view, keyForTracker(tracker), isActivity = marker == 2)
        }
    }

    private fun resolveMainGroupActivity(view: View): Any? {
        var context: Context? = view.context
        while (context != null) {
            if (context.javaClass.name == QDReaderHost.Classes.MAIN_GROUP_ACTIVITY) {
                return context
            }
            context = (context as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun readTabParams(activity: Any): List<*>? {
        return runCatching {
            val adapter = activity.javaClass.getMethod("getTabsPagerAdapter").invoke(activity) ?: return null
            resolveRuntimeMethod(adapter.javaClass, "j", NavigationTargetKeys.adapterItems).invoke(adapter) as? List<*>
        }.getOrNull()
    }

    private fun paramInt(param: Any, directName: String, key: TargetKey.MethodTarget): Int? {
        return runCatching {
            resolveRuntimeMethod(param.javaClass, directName, key).invoke(param) as? Int
        }.getOrNull()
    }

    private fun keyForTracker(tracker: Int?): String? {
        return when (tracker) {
            1 -> "shelf"
            2 -> "selection"
            3 -> "discover"
            4 -> "mine"
            else -> null
        }
    }

    private fun resolveTabStrip(root: View): View? {
        return if (root.javaClass.name == QDReaderHost.Classes.PAGER_SLIDING_TAB_STRIP) {
            root
        } else {
            UiFeatureSupport.findChildViewByIdName(root, "tabs")
        }
    }

    private fun firstLinearChild(view: View): ViewGroup? {
        if (view !is ViewGroup) {
            return null
        }
        if (view is android.widget.LinearLayout) {
            return view
        }
        for (index in 0 until view.childCount) {
            firstLinearChild(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun tabContainerFromTitle(titleView: View): ViewGroup? {
        var current: View = titleView
        while (current.parent is View) {
            val parent = current.parent as View
            if (parent is ViewGroup && parent.childCount > 1) {
                var tabLikeChildren = 0
                for (index in 0 until parent.childCount) {
                    if (UiFeatureSupport.findChildViewByIdName(parent.getChildAt(index), "view_tab_title_title") != null) {
                        tabLikeChildren += 1
                    }
                }
                if (tabLikeChildren > 1) {
                    return parent
                }
            }
            current = parent
        }
        return null
    }

    private fun fieldNameForSetter(methodName: String): String {
        return when (methodName) {
            "setHasAdvBubbleText" -> "hasAdvBubbleText"
            "setHasDotPositionMark" -> "hasDotPositionMark"
            else -> methodName.removePrefix("set").replaceFirstChar { it.lowercaseChar() }
        }
    }

    private fun setBooleanField(target: Any?, fieldName: String, value: Boolean) {
        target ?: return
        FeatureHooks.findFieldOrNull(target.javaClass, fieldName)?.let { field ->
            FeatureHooks.write(field, target, value)
        }
    }

    private fun setIntField(target: Any?, fieldName: String, value: Int) {
        target ?: return
        FeatureHooks.findFieldOrNull(target.javaClass, fieldName)?.let { field ->
            FeatureHooks.write(field, target, value)
        }
    }

    private fun resolveMethod(
        classLoader: ClassLoader,
        directClass: Class<*>?,
        directName: String,
        key: TargetKey.MethodTarget,
    ): Method? {
        return HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = classLoader,
            processName = HostSessionStore.processName,
            key = key,
        )
    }

    private fun resolveRuntimeMethod(
        targetClass: Class<*>,
        directName: String,
        key: TargetKey.MethodTarget,
        vararg parameterTypes: Class<*>,
    ): Method {
        return HostTargetResolver.resolveMethod(
            context = HostSessionStore.contextOrNull(),
            classLoader = targetClass.classLoader ?: error("target-classloader-not-found"),
            processName = HostSessionStore.processName,
            key = key,
        )
            ?: error("method-not-found:$directName")
    }

}
