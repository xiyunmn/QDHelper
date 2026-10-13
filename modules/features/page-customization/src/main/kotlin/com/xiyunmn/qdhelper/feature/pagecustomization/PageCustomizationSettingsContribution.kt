package com.xiyunmn.qdhelper.feature.pagecustomization

import android.app.Activity
import android.content.Context
import android.view.View
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.PageCustomizationItem
import com.xiyunmn.qdhelper.settings.api.PageCustomizationScanResult
import com.xiyunmn.qdhelper.settings.api.PageCustomizationSettingsController
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSearchAlias
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object PageCustomizationSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("page-customization.settings")
    override val owner = OwnerId("page-customization")
    override val sections = listOf(
        SettingsSection(
            id = "page-customization.mine",
            page = SettingsPageIds.PAGE_CUSTOMIZATION,
            title = "我的页",
            order = 100,
            resetKeys = setOf(SettingIds.KEY_PAGE_CUSTOM_PENDING_SCAN),
            rows = listOf(
                customRow(
                    kind = PageCustomKind.Mine,
                    title = "自定义我的",
                    summary = "选择需要隐藏的卡片或入口。",
                    aliases = listOf("扫描我的页控件", "清空我的配置"),
                ),
                SettingsRow.Toggle("page-customization.mine-auto-refresh", "移除自动下拉刷新", "切回我的页时不再自动刷新；保留首次加载、手动下拉及登录状态变更刷新。", SettingIds.KEY_DISABLE_MINE_PAGE_AUTO_REFRESH, keywords = setOf("我的页刷新", "自动刷新")),
            ),
        ),
        SettingsSection(
            id = "page-customization.selection",
            page = SettingsPageIds.PAGE_CUSTOMIZATION,
            title = "精选页",
            order = 200,
            rows = listOf(
                customRow(
                    kind = PageCustomKind.Selection,
                    title = "自定义精选页",
                    summary = "选择需要隐藏的轮播、栏目或推荐分组。",
                    aliases = listOf("扫描精选页控件", "清空精选页配置"),
                ),
                SettingsRow.Toggle("page-customization.selection-guess", "屏蔽猜你喜欢瀑布流", "隐藏精选页底部的「猜你喜欢」无限推荐流并停止无限加载；需先开启上方「自定义精选页」。", SettingIds.KEY_SELECTION_PAGE_CUSTOM_HIDE_GUESS),
            ),
        ),
        SettingsSection(
            id = "page-customization.book-detail",
            page = SettingsPageIds.PAGE_CUSTOMIZATION,
            title = "书籍详情页",
            order = 400,
            groupId = "page-customization.book-detail-card",
            rows = listOf(
                customRow(
                    kind = PageCustomKind.BookDetail,
                    title = "自定义书籍详情页",
                    summary = "固定列出书籍详情页业务模块，隐藏时从模块绑定或数据入口短路。",
                    aliases = listOf(
                        "刷新书籍详情页项目",
                        "清空书籍详情页配置",
                        "书友圈",
                        "作家相关",
                        "书友榜",
                        "月票金主",
                        "本书看点",
                        "活动广告",
                        "活动浮标",
                        "同类作品推荐",
                        "看过此书的人还推荐",
                        "版权改编",
                    ),
                ),
            ),
        ),
    )

    private fun customRow(
        kind: PageCustomKind,
        title: String,
        summary: String,
        aliases: List<String>,
    ) = SettingsRow.PageCustomization(
        id = "page-customization.${kind.id}",
        title = title,
        summary = summary,
        controller = PageCustomizationControllerAdapter(kind),
        searchAliases = aliases.map { SettingsSearchAlias(it) },
    )
}

private class PageCustomizationControllerAdapter(
    private val kind: PageCustomKind,
) : PageCustomizationSettingsController {
    override val id: String = kind.id
    override val label: String = kind.label
    override val enabledKey: String = kind.enabledKey
    override val entriesKey: String = kind.entriesKey
    override val hiddenKey: String = kind.hiddenKey
    override val fixedItems: Boolean = kind == PageCustomKind.BookDetail

    override fun storedItems(context: Context): List<PageCustomizationItem> =
        PageCustomization.storedItems(context, kind).map { PageCustomizationItem(it.key, it.label) }

    override fun refreshKnownItems(context: Context): PageCustomizationScanResult =
        PageCustomization.refreshKnownItems(context, kind).toApi()

    override fun addScanResultListener(listener: (PageCustomizationScanResult) -> Unit): AutoCloseable =
        PageCustomization.addScanResultListener { resultKind, result ->
            if (resultKind == kind) listener(result.toApi())
        }

    override fun requestPendingScan(context: Context): Boolean = PageCustomization.requestPendingScan(context, kind)

    override fun openHostPageForPendingScan(activity: Activity): AutoCloseable? =
        PageCustomization.openHostPageForPendingScan(activity, kind)

    override fun cancelPendingScan(context: Context) = PageCustomization.cancelPendingScan(context, kind)

    override fun hasPendingScan(context: Context): Boolean = PageCustomization.pendingScanKind(context) == kind

    override fun consumePendingScanIfMatches(root: View): PageCustomizationScanResult? =
        PageCustomization.consumePendingScanIfMatches(root)?.toApi()

    override fun consumePendingScanIfMatchesAsync(
        root: View,
        onResult: (PageCustomizationScanResult?) -> Unit,
    ): Boolean = PageCustomization.consumePendingScanIfMatchesAsync(root) { onResult(it?.toApi()) }

    override fun clear(context: Context): Boolean = PageCustomization.clearPage(context, kind)
}

private fun PageCustomScanResult.toApi() = PageCustomizationScanResult(matchedPage, count)
