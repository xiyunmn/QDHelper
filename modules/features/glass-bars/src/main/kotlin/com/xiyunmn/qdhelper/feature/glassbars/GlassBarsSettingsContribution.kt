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
                    defaultValue = true,
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
                SettingsRow.Slider(
                    "glass-bars.blur", "背景模糊", "调整底栏后方内容的模糊程度，0 为关闭。", SettingIds.KEY_GLASS_BLUR_RADIUS,
                    0.0, 14.0, 1.0, 7.0, valueFormatter = { if (it == 0.0) "关闭" else "${it.toInt()} dp" },
                ),
                percentage("glass-bars.refraction", "玻璃折射", "调整底栏和胶囊的透镜形变，0 为关闭。", SettingIds.KEY_GLASS_REFRACTION_PERCENT, 200.0, 100.0),
                percentage("glass-bars.surface-opacity", "玻璃染色", "调整玻璃底色的浓淡；越低越通透，0 为不染色。", SettingIds.KEY_GLASS_SURFACE_OPACITY, 100.0, 50.0, step = 1.0),
                percentage("glass-bars.highlight", "边缘高光", "调整玻璃轮廓的明亮程度，0 为关闭。", SettingIds.KEY_GLASS_HIGHLIGHT_PERCENT, 200.0, 100.0),
                percentage("glass-bars.shadow", "悬浮阴影", "调整底栏阴影的深浅，0 为关闭。", SettingIds.KEY_GLASS_SHADOW_PERCENT, 200.0, 100.0),
                SettingsRow.Toggle("glass-bars.tilt", "重力感应光照", "高光随设备倾斜变化；高光为 0 时不生效。", SettingIds.KEY_GLASS_TILT, defaultValue = true),
                SettingsRow.Toggle("glass-bars.animation", "按压反馈", "启用按压光照与主界面胶囊动效；关闭后仍可拖动切换。", SettingIds.KEY_GLASS_ANIMATION, defaultValue = true),
                SettingsRow.Hint("glass-bars.base", "修改即时生效，材质跟随宿主日夜主题。三处底栏的尺寸互不影响。"),
            ),
        ),
    )

    private fun location(kind: GlassBarKind, title: String, summary: String, order: Int): SettingsSection {
        val prefix = "glass-bars.${kind.name.lowercase()}"
        return SettingsSection(prefix, SettingsPageIds.GLASS_BARS, title, order, buildList {
            add(SettingsRow.Toggle("$prefix.enabled", "启用$title", summary, kind.settingKey, defaultValue = true))
            add(SettingsRow.Slider(
                "$prefix.height", "${title}高度", "调整主操作行高度；控件较大时保留所需空间。", kind.heightKey,
                GlassBarDimensions.MIN_HEIGHT.toDouble(), GlassBarDimensions.MAX_HEIGHT.toDouble(), 1.0, GlassBarDimensions.DEFAULT_HEIGHT.toDouble(),
                valueFormatter = { "${it.toInt()} dp" },
            ))
            add(SettingsRow.Slider(
                "$prefix.width", "${title}宽度", "自动适配内容，或选择可用宽度的比例；保留按钮所需空间。", kind.widthKey,
                GlassBarDimensions.AUTO_WIDTH_TICK.toDouble(), GlassBarDimensions.MAX_WIDTH.toDouble(), 1.0,
                (kind.defaultWidthPercent.takeIf { it > 0 } ?: GlassBarDimensions.AUTO_WIDTH_TICK).toDouble(),
                valueFormatter = { if (it.toInt() == GlassBarDimensions.AUTO_WIDTH_TICK) "自动" else "${it.toInt()}%" },
            ))
            add(SettingsRow.Slider(
                "$prefix.bottom-gap", "${title}底部间距", "底栏与系统导航区域的距离；自动随导航方式调整。", kind.gapKey,
                -1.0, GlassBarDimensions.MAX_GAP.toDouble(), 1.0, GlassBarDimensions.DEFAULT_GAP.toDouble(),
                valueFormatter = { if (it < 0) "自动" else "${it.toInt()} dp" },
            ))
            if (kind == GlassBarKind.MAIN) {
                add(SettingsRow.Slider(
                    "$prefix.click-speed", "点击切换速度", "调整点击 Tab 后胶囊的移动速度；数值越小越慢，拖动手感独立。",
                    SettingIds.KEY_GLASS_CLICK_SPEED, 25.0, 175.0, 5.0, 100.0,
                    valueFormatter = { "${it.toInt()}%" },
                ))
                add(SettingsRow.Slider(
                    "$prefix.capsule-size", "胶囊大小", "调整选中胶囊的大小，不改变 Tab 和按钮位置。",
                    SettingIds.KEY_GLASS_CAPSULE_SIZE, 75.0, 125.0, 5.0, 100.0,
                    valueFormatter = { "${it.toInt()}%" },
                ))
                add(percentage(
                    "$prefix.drag-strength", "拖动强度", "调整拖动时的放大、形变和落位回弹；0 为关闭形变，仍可拖动切换。",
                    SettingIds.KEY_GLASS_DRAG_STRENGTH, 200.0, 100.0,
                ))
                add(SettingsRow.Toggle(
                    "$prefix.haptics", "拖动触觉反馈", "跨过 Tab 时轻触，松手落位时再次反馈，遵循系统触感设置。",
                    SettingIds.KEY_GLASS_HAPTICS, defaultValue = true,
                ))
            }
        })
    }

    private fun percentage(id: String, title: String, summary: String, key: String, maximum: Double, default: Double, step: Double = 5.0) =
        SettingsRow.Slider(id, title, summary, key, 0.0, maximum, step, default,
            valueFormatter = { if (it == 0.0) "关闭" else "${it.toInt()}%" },
        )
}
