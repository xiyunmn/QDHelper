package com.xiyunmn.qdhelper.feature.pagecustomization

import com.xiyunmn.qdhelper.feature.api.runtime.UiFeatureSupport

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.feature.api.runtime.HookModule
import com.xiyunmn.qdhelper.host.api.symbol.HostSymbols
import java.lang.reflect.Method

object BookDetailPageCustomization {
    const val ITEM_CIRCLE = "book_detail_circle"
    const val ITEM_AUTHOR = "book_detail_author"
    const val ITEM_LOOK_FOR = "book_detail_look_for"
    const val ITEM_ACTIVITY_BANNER = "book_detail_activity_banner"
    const val ITEM_ACTIVITY_FLOAT = "book_detail_activity_float"
    const val ITEM_SAME_RECOMMEND = "book_detail_same_recommend"
    const val ITEM_OTHER_READ = "book_detail_other_read"
    const val ITEM_FANS_RANK = "book_detail_fans_rank"
    const val ITEM_MONTH_TICKET_SPONSOR = "book_detail_month_ticket_sponsor"
    const val ITEM_COPYRIGHT = "book_detail_copyright"

    private const val BOOK_DETAIL_ACTIVITY = HostSymbols.Classes.BOOK_DETAIL_ACTIVITY
    private const val BOOK_CIRCLE_MODULE_VIEW = HostSymbols.Classes.BOOK_CIRCLE_MODULE_VIEW
    private const val BOOK_AUTHOR_MODULE_VIEW = HostSymbols.Classes.BOOK_AUTHOR_MODULE_VIEW
    private const val BOOK_LOOK_FOR_MODULE_VIEW = HostSymbols.Classes.BOOK_LOOK_FOR_MODULE_VIEW
    private const val BOOK_RECOMMEND_MODULE_VIEW = HostSymbols.Classes.BOOK_RECOMMEND_MODULE_VIEW
    private const val BOOK_FANS_MODULE_VIEW = HostSymbols.Classes.BOOK_FANS_MODULE_VIEW
    private const val BOOK_DETAIL = HostSymbols.Classes.BOOK_DETAIL
    private const val BASE_BOOK_INFO = HostSymbols.Classes.BASE_BOOK_INFO
    private const val AD_VIEW = HostSymbols.Classes.AD_VIEW
    private const val DETAIL_AD_POS = "newshowbook"

    val knownItems = listOf(
        PageCustomItem(ITEM_CIRCLE, "书友圈"),
        PageCustomItem(ITEM_AUTHOR, "作家相关"),
        PageCustomItem(ITEM_FANS_RANK, "书友榜"),
        PageCustomItem(ITEM_MONTH_TICKET_SPONSOR, "月票金主"),
        PageCustomItem(ITEM_LOOK_FOR, "本书看点"),
        PageCustomItem(ITEM_ACTIVITY_BANNER, "活动广告"),
        PageCustomItem(ITEM_ACTIVITY_FLOAT, "活动浮标"),
        PageCustomItem(ITEM_SAME_RECOMMEND, "同类作品推荐"),
        PageCustomItem(ITEM_OTHER_READ, "看过此书的人还推荐"),
        PageCustomItem(ITEM_COPYRIGHT, "版权改编"),
    )

    fun install(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        var installedHooks = 0
        installedHooks += installViewMethodGate(
            module = module,
            classLoader = classLoader,
            phase = phase,
            className = BOOK_CIRCLE_MODULE_VIEW,
            methodName = "bind",
            parameterCount = 8,
            itemKey = ITEM_CIRCLE,
            id = "ui.pageCustom.bookDetail.circle",
        )
        installedHooks += installViewMethodGate(
            module = module,
            classLoader = classLoader,
            phase = phase,
            className = BOOK_AUTHOR_MODULE_VIEW,
            methodName = "b",
            parameterCount = 2,
            itemKey = ITEM_AUTHOR,
            id = "ui.pageCustom.bookDetail.author",
        )
        installedHooks += installViewMethodGate(
            module = module,
            classLoader = classLoader,
            phase = phase,
            className = BOOK_LOOK_FOR_MODULE_VIEW,
            methodName = "bindView",
            parameterCount = 3,
            itemKey = ITEM_LOOK_FOR,
            id = "ui.pageCustom.bookDetail.lookFor",
        )
        installedHooks += installActivityBannerGate(module, classLoader, phase)
        installedHooks += installActivityFloatGate(module, classLoader, phase)
        installedHooks += installDetailAdGate(module, classLoader, phase)
        installedHooks += installRecommendGate(module, classLoader, phase)
        installedHooks += installFansGate(module, classLoader, phase)
        installedHooks += installCopyrightGate(module, classLoader, phase)
        return installedHooks
    }

    fun hiddenKeys(context: Context?): Set<String> {
        val prefsContext = context ?: HostSessionStore.contextOrNull() ?: return emptySet()
        val validKeys = knownItems.map { it.key }.toSet()
        return SettingsRuntime.prefs(prefsContext)
            .getString(SettingIds.KEY_BOOK_DETAIL_PAGE_CUSTOM_HIDDEN, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it in validKeys }
            ?.toSet()
            .orEmpty()
    }

    private fun installViewMethodGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
        className: String,
        methodName: String,
        parameterCount: Int,
        itemKey: String,
        id: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(className, classLoader) ?: return 0
        val method = FeatureHooks.declaredMethodsNamed(clazz, methodName)
            .singleOrNull { candidate -> candidate.parameterTypes.size == parameterCount }
            ?: return 0
        return installHideViewGate(module, method, phase, id, itemKey) { owner ->
            shouldHideViewItem(owner, itemKey)
        }
    }

    private fun installActivityBannerGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(BOOK_DETAIL, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, "getActivityBanner") ?: return 0
        return UiFeatureSupport.installReturnValue(
            module = module,
            id = "ui.pageCustom.bookDetail.activityBanner.data",
            method = method,
            hookId = "qdhelper.ui.page_custom_book_detail_activity_banner_data",
            phase = phase,
            shouldBlock = { _ -> shouldHideItem(HostSessionStore.contextOrNull(), ITEM_ACTIVITY_BANNER) },
            action = "return-null",
        ) {
            null
        }
    }

    private fun installActivityFloatGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(BOOK_DETAIL_ACTIVITY, classLoader) ?: return 0
        val method = FeatureHooks.findDeclaredMethodOrNull(clazz, "getFloatingAd") ?: return 0
        return UiFeatureSupport.installReturnValue(
            module = module,
            id = "ui.pageCustom.bookDetail.activityFloat",
            method = method,
            hookId = "qdhelper.ui.page_custom_book_detail_activity_float",
            phase = phase,
            shouldBlock = { chain ->
                shouldHideItem(chain.thisObject as? Context ?: HostSessionStore.contextOrNull(), ITEM_ACTIVITY_FLOAT)
            },
            action = "skip-floating-ad-request",
        ) {
            null
        }
    }

    private fun installDetailAdGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(AD_VIEW, classLoader) ?: return 0
        val callbackClass = FeatureHooks.findClassOrNull("$AD_VIEW\$d", classLoader) ?: return 0
        var installedHooks = 0
        FeatureHooks.declaredMethodsNamed(clazz, "dataBind")
            .filter { method ->
                method.parameterTypes.isNotEmpty() &&
                    callbackClass.isAssignableFrom(method.parameterTypes.last())
            }
            .forEach { method ->
                val hookSuffix = method.parameterTypes.joinToString("_") { type ->
                    type.simpleName.replace('$', '_')
                }
                installedHooks += UiFeatureSupport.installReturnValue(
                    module = module,
                    id = "ui.pageCustom.bookDetail.activityAd.$hookSuffix",
                    method = method,
                    hookId = "qdhelper.ui.page_custom_book_detail_activity_ad_$hookSuffix",
                    phase = phase,
                    shouldBlock = { chain -> shouldBlockDetailAd(chain.thisObject) },
                    action = "callback-false",
                ) { chain ->
                    notifyAdLoadBlocked(chain.args.lastOrNull())
                    if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            }
        return installedHooks
    }

    private fun installRecommendGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(BOOK_RECOMMEND_MODULE_VIEW, classLoader) ?: return 0
        val method = FeatureHooks.declaredMethodsNamed(clazz, "e")
            .singleOrNull { candidate ->
                candidate.parameterTypes.size == 3 &&
                    candidate.parameterTypes[0] == String::class.java &&
                    candidate.parameterTypes[1] == String::class.java
            }
            ?: return 0
        return installHideViewGate(
            module = module,
            method = method,
            phase = phase,
            id = "ui.pageCustom.bookDetail.recommend",
            itemKey = null,
        ) { owner ->
            val view = owner as? View ?: return@installHideViewGate false
            val itemKey = when (view.resourceEntryName()) {
                "bookRecommendLayout" -> ITEM_SAME_RECOMMEND
                "otherReadLayout" -> ITEM_OTHER_READ
                else -> return@installHideViewGate false
            }
            shouldHideViewItem(view, itemKey)
        }
    }

    private fun installFansGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(BOOK_FANS_MODULE_VIEW, classLoader) ?: return 0
        val method = FeatureHooks.declaredMethodsNamed(clazz, "a")
            .singleOrNull { candidate -> candidate.parameterTypes.size == 4 }
            ?: return 0
        return UiFeatureSupport.installAfter(
            module = module,
            id = "ui.pageCustom.bookDetail.fans",
            method = method,
            hookId = "qdhelper.ui.page_custom_book_detail_fans",
            phase = phase,
        ) { owner ->
            applyFansCustomization(owner)
        }
    }

    private fun installCopyrightGate(
        module: HookModule,
        classLoader: ClassLoader,
        phase: String,
    ): Int {
        val clazz = FeatureHooks.findClassOrNull(BASE_BOOK_INFO, classLoader) ?: return 0
        var installedHooks = 0
        FeatureHooks.findDeclaredMethodOrNull(clazz, "getCopyRightDetail")?.let { method ->
            installedHooks += UiFeatureSupport.installReturnValue(
                module = module,
                id = "ui.pageCustom.bookDetail.copyright.detail",
                method = method,
                hookId = "qdhelper.ui.page_custom_book_detail_copyright_detail",
                phase = phase,
                shouldBlock = { _ -> shouldHideItem(HostSessionStore.contextOrNull(), ITEM_COPYRIGHT) },
                action = "return-null",
            ) {
                null
            }
        }
        listOf("getDescCopyRight", "getJoinTimeCopyRight", "getAuthCopyRight").forEach { methodName ->
            FeatureHooks.findDeclaredMethodOrNull(clazz, methodName)?.let { method ->
                installedHooks += installCopyrightStringGate(module, method, phase, methodName)
            }
        }
        return installedHooks
    }

    private fun installCopyrightStringGate(
        module: HookModule,
        method: Method,
        phase: String,
        methodName: String,
    ): Int {
        return UiFeatureSupport.installReturnValue(
            module = module,
            id = "ui.pageCustom.bookDetail.copyright.$methodName",
            method = method,
            hookId = "qdhelper.ui.page_custom_book_detail_copyright_$methodName",
            phase = phase,
            shouldBlock = { _ -> shouldHideItem(HostSessionStore.contextOrNull(), ITEM_COPYRIGHT) },
            action = "return-empty",
        ) {
            ""
        }
    }

    private fun installHideViewGate(
        module: HookModule,
        method: Method,
        phase: String,
        id: String,
        itemKey: String?,
        shouldHide: (Any?) -> Boolean,
    ): Int {
        val hookKey = itemKey ?: id.substringAfterLast('.')
        return UiFeatureSupport.installReturnValue(
            module = module,
            id = id,
            method = method,
            hookId = "qdhelper.ui.page_custom_book_detail_${hookKey.replace('.', '_')}",
            phase = phase,
            shouldBlock = { chain -> shouldHide(chain.thisObject) },
            action = "hide-view",
        ) { chain ->
            UiFeatureSupport.hideView(chain.thisObject as? View)
            null
        }
    }

    private fun shouldHideViewItem(owner: Any?, itemKey: String): Boolean {
        val context = (owner as? View)?.context ?: HostSessionStore.contextOrNull()
        return isBookDetailActivityContext(context) && shouldHideItem(context, itemKey)
    }

    private fun shouldBlockDetailAd(owner: Any?): Boolean {
        val view = owner as? View ?: return false
        val context = view.context ?: HostSessionStore.contextOrNull()
        if (!isBookDetailActivityContext(context) || !shouldHideItem(context, ITEM_ACTIVITY_BANNER)) {
            return false
        }
        return owner.stringField("posMark") == DETAIL_AD_POS
    }

    private fun notifyAdLoadBlocked(callback: Any?) {
        callback ?: return
        val method = callback.javaClass.methods
            .firstOrNull { candidate -> candidate.name == "search" && candidate.parameterTypes.size == 2 }
            ?: return
        runCatching {
            method.invoke(callback, false, null)
        }
    }

    private fun applyFansCustomization(owner: Any?): Boolean {
        val view = owner as? View ?: return false
        val context = view.context ?: HostSessionStore.contextOrNull()
        if (!isBookDetailActivityContext(context) || !SettingsRuntime.isBookDetailPageCustomEnabled(context)) {
            return false
        }
        val hiddenKeys = hiddenKeys(context)
        val hideFansRank = ITEM_FANS_RANK in hiddenKeys
        val hideMonthTicketSponsor = ITEM_MONTH_TICKET_SPONSOR in hiddenKeys
        if (!hideFansRank && !hideMonthTicketSponsor) {
            return false
        }
        if (hideFansRank && hideMonthTicketSponsor) {
            return hideIfVisible(view)
        }

        var changed = false
        val doubleRow = UiFeatureSupport.findChildViewByIdName(view, "fansDoubleRowLayout")
        val planB = UiFeatureSupport.findChildViewByIdName(view, "fansLayoutPlanB")
        val banner = UiFeatureSupport.findChildViewByIdName(view, "scrollBanner") ?: return false

        if (hideFansRank) {
            changed = hideIfVisible(UiFeatureSupport.findChildViewByIdName(view, "fansLayout")) || changed
            changed = hideIfVisible(planB) || changed
            changed = hideIfVisible(siblingDividerBefore(banner)) || changed
            if (doubleRow?.visibility != View.VISIBLE) {
                changed = hideIfVisible(view) || changed
            }
        }
        if (hideMonthTicketSponsor) {
            changed = hideIfVisible(banner) || changed
            changed = hideIfVisible(siblingDividerBefore(banner)) || changed
        }
        return changed
    }

    private fun siblingDividerBefore(view: View): View? {
        val parent = view.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(view)
        if (index <= 0) {
            return null
        }
        return parent.getChildAt(index - 1).takeIf { it.id == View.NO_ID }
    }

    private fun hideIfVisible(view: View?): Boolean {
        view ?: return false
        if (view.visibility == View.GONE) {
            return false
        }
        view.visibility = View.GONE
        return true
    }

    private fun shouldHideItem(context: Context?, itemKey: String): Boolean {
        return SettingsRuntime.isBookDetailPageCustomEnabled(context) && itemKey in hiddenKeys(context)
    }

    private fun isBookDetailActivityContext(context: Context?): Boolean {
        var current = context
        while (current != null) {
            if (current.javaClass.name == BOOK_DETAIL_ACTIVITY) {
                return true
            }
            current = (current as? ContextWrapper)?.baseContext
        }
        return false
    }

    private fun View.resourceEntryName(): String? {
        if (id == View.NO_ID) return null
        return runCatching { resources.getResourceEntryName(id) }.getOrNull()
    }

    private fun Any.stringField(name: String): String? {
        var clazz: Class<*>? = javaClass
        while (clazz != null) {
            val field = FeatureHooks.findFieldOrNull(clazz, name)
            if (field != null) return FeatureHooks.read(field, this) as? String
            clazz = clazz.superclass
        }
        return null
    }
}
