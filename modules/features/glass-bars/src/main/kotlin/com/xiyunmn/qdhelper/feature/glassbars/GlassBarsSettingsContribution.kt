package com.xiyunmn.qdhelper.feature.glassbars

import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.GlassBarDimensions
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object GlassBarsSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("glass-bars.settings")
    override val owner = OwnerId("glass-bars")
    override val sections = listOf(
        SettingsSection(
            "glass-bars.entry", SettingsPageIds.NAVIGATION, "悬浮底栏", 0,
            listOf(
                SettingsRow.Toggle(
                    "glass-bars.enabled",
                    "启用悬浮底栏",
                    "需要 Android 13 及以上；较低版本保留原生底栏。关闭后保留各处独立配置。",
                    SettingIds.KEY_GLASS_BARS_ENABLED,
                ),
                SettingsRow.PageLink(
                    "glass-bars.page", "液态玻璃底栏", "需要 Android 13 及以上；三处独立尺寸。",
                    SettingsPageIds.GLASS_BARS, keywords = setOf("模糊", "折射", "悬浮", "Liquid Glass"),
                ),
            ),
        ),
        location(GlassBarKind.MAIN, "主界面底栏", "保留原有 Tab、排序和图标，支持拖拽预览与松手切换。", 100),
        location(GlassBarKind.DETAIL, "书籍详情页底栏", "保留加书架、听书、阅读等原生操作。", 200),
        location(GlassBarKind.DIRECTORY, "目录页底栏", "保留下载和订阅，折扣提示按需叠加。", 300),
        SettingsSection(
            "glass-bars.effects", SettingsPageIds.GLASS_BARS, "共用效果", 400,
            listOf(
                SettingsRow.Toggle("glass-bars.blur", "背景模糊", "实时模糊底栏后方内容。", SettingIds.KEY_GLASS_BLUR, defaultValue = true),
                SettingsRow.Toggle("glass-bars.refraction", "边缘折射", "圆角边缘呈现玻璃透镜形变。", SettingIds.KEY_GLASS_REFRACTION, defaultValue = true),
                SettingsRow.Toggle("glass-bars.highlight", "边缘高光", "增加玻璃轮廓的明暗层次。", SettingIds.KEY_GLASS_HIGHLIGHT, defaultValue = true),
                SettingsRow.Toggle("glass-bars.shadow", "悬浮阴影", "增加底栏下方的柔和阴影。", SettingIds.KEY_GLASS_SHADOW, defaultValue = true),
                SettingsRow.Toggle("glass-bars.tilt", "重力感应光照", "高光随设备倾斜变化，需同时开启边缘高光。", SettingIds.KEY_GLASS_TILT, defaultValue = true),
                SettingsRow.Toggle("glass-bars.animation", "按压效果", "按下时放大，拖拽时形变并回弹；关闭后仍可拖拽切换。", SettingIds.KEY_GLASS_ANIMATION, defaultValue = true),
                SettingsRow.Hint("glass-bars.base", "修改即时生效，材质跟随起点日夜主题。三处底栏的尺寸互不影响。"),
            ),
        ),
    )

    private fun location(kind: GlassBarKind, title: String, summary: String, order: Int): SettingsSection {
        val prefix = "glass-bars.${kind.name.lowercase()}"
        return SettingsSection(prefix, SettingsPageIds.GLASS_BARS, title, order, listOf(
            SettingsRow.Toggle("$prefix.enabled", "启用$title", summary, kind.settingKey),
            SettingsRow.Slider(
                "$prefix.height", "${title}高度", "调整主操作行高度；控件较大时保留所需空间。", kind.heightKey,
                GlassBarDimensions.MIN_HEIGHT.toDouble(), GlassBarDimensions.MAX_HEIGHT.toDouble(), 1.0, 64.0,
                valueFormatter = { "${it.toInt()} dp" },
            ),
            SettingsRow.Slider(
                "$prefix.width", "${title}宽度", "自动适配内容，或选择可用宽度的比例；保留按钮所需空间。", kind.widthKey,
                GlassBarDimensions.AUTO_WIDTH_TICK.toDouble(), GlassBarDimensions.MAX_WIDTH.toDouble(), 1.0,
                (kind.defaultWidthPercent.takeIf { it > 0 } ?: GlassBarDimensions.AUTO_WIDTH_TICK).toDouble(),
                valueFormatter = { if (it.toInt() == GlassBarDimensions.AUTO_WIDTH_TICK) "自动" else "${it.toInt()}%" },
            ),
            SettingsRow.Slider(
                "$prefix.bottom-gap", "${title}底部间距", "底栏与系统导航区域的距离；自动随导航方式调整。", kind.gapKey,
                -1.0, GlassBarDimensions.MAX_GAP.toDouble(), 1.0, -1.0,
                valueFormatter = { if (it < 0) "自动" else "${it.toInt()} dp" },
            ),
        ))
    }
}
