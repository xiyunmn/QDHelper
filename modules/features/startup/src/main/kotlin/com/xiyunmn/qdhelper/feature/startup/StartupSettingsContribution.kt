package com.xiyunmn.qdhelper.feature.startup

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsAction
import com.xiyunmn.qdhelper.settings.api.SettingsBooleanChangedAction
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection
import com.xiyunmn.qdhelper.settings.api.SettingsTextChangedAction

object StartupSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("startup.settings")
    override val owner = OwnerId("startup")

    private val scheduleBoolean = SettingsBooleanChangedAction { scope, _ -> StartupImageAutoSync.schedule(scope.activity) }
    private val scheduleText = SettingsTextChangedAction { scope, _ -> StartupImageAutoSync.schedule(scope.activity) }

    override val sections = listOf(
        SettingsSection(
            id = "startup.ad",
            page = SettingsPageIds.STARTUP,
            title = "启动与开屏",
            order = 100,
            rows = listOf(
                SettingsRow.Toggle("startup.block-ad", "移除开屏广告", "通过 SplashManager 返回无广告结果。", SettingIds.KEY_BLOCK_SPLASH_AD),
                SettingsRow.Toggle("startup.skip-image", "跳过启动图", "跳过起点默认与自定义启动图。", SettingIds.KEY_SKIP_SPLASH_IMAGE),
            ),
        ),
        SettingsSection(
            id = "startup.image",
            page = SettingsPageIds.STARTUP,
            title = "启动图",
            order = 200,
            rows = listOf(
                SettingsRow.Toggle("startup.dark-logo", "启动图使用深色 Logo", "控制启动页 Logo 明暗样式，与起点 Paper.isDarkLogo 保持一致。", SettingIds.KEY_CUSTOM_SPLASH_IMAGE_DARK_LOGO),
                SettingsRow.Action("startup.sync", "同步启动图文件", "下载当前生效源的图片到起点启动图缓存，使下一次冷启动可直接显示。", "同步", SettingsAction { StartupImageAutoSync.syncNow(it.activity) }),
                SettingsRow.Toggle("startup.local-enabled", "相册启动图", "使用从相册选取并裁剪的本地图片作为启动图。", SettingIds.KEY_LOCAL_SPLASH_IMAGE_ENABLED, onChanged = scheduleBoolean, keywords = setOf("从相册选择启动图", "本地启动图")),
                SettingsRow.Action(
                    id = "startup.local-pick",
                    title = "从相册选择启动图",
                    summary = "SAF 选图后可旋转、按 9:20 竖版裁剪。建议竖版 1080×2400。",
                    actionText = "选图",
                    action = SettingsAction { StartupImagePicker.open(it.activity) },
                    keywords = setOf("选图", "裁剪"),
                    settingKeys = setOf(SettingIds.KEY_LOCAL_SPLASH_IMAGE_PATH, SettingIds.KEY_LOCAL_SPLASH_IMAGE_ENABLED),
                ),
                SettingsRow.Action(
                    id = "startup.local-clear",
                    title = "清除相册启动图",
                    summary = "删除保存的相册选图并关闭相册启动图开关。",
                    actionText = "清除",
                    action = SettingsAction {
                        StartupImagePicker.clear(it.activity)
                        it.closeExclusiveSwitch(SettingIds.KEY_LOCAL_SPLASH_IMAGE_ENABLED)
                    },
                    settingKeys = setOf(SettingIds.KEY_LOCAL_SPLASH_IMAGE_PATH, SettingIds.KEY_LOCAL_SPLASH_IMAGE_ENABLED),
                ),
                SettingsRow.Toggle("startup.custom-enabled", "替换开屏启动图", "用自定义图片直链替换 QDAppConfigHelper 的 BootWallPapers 配置。", SettingIds.KEY_CUSTOM_SPLASH_IMAGE_ENABLED, onChanged = scheduleBoolean),
                SettingsRow.TextInput("startup.custom-url", "启动图直链", "填写带图片扩展名的 http、https、file 直链；变更后自动同步。", SettingIds.KEY_CUSTOM_SPLASH_IMAGE_URL, "https://example.com/splash.jpg", onChanged = scheduleText),
                SettingsRow.Toggle("startup.official-local-unlock", "本地解锁官方启动图", "在起点“我 → 装扮中心 → 启动屏幕”直接启用或停用官方图片。", SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_LOCAL_UNLOCK_ENABLED, onChanged = scheduleBoolean),
                SettingsRow.Toggle("startup.official-enabled", "手动配置官方启动图", "用官方 Paper ID 与官方图片直链替换启动图配置。", SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_ENABLED, onChanged = scheduleBoolean),
                SettingsRow.TextInput("startup.official-url", "官方启动图直链", "填写起点官方启动图图片直链；变更后自动同步。", SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_URL, "https://example.com/splash.jpg", onChanged = scheduleText),
                SettingsRow.TextInput("startup.official-paper", "官方 Paper ID", "可选；留空时按图片直链生成本地 Paper ID。", SettingIds.KEY_OFFICIAL_SPLASH_IMAGE_PAPER_ID, "904099020562", onChanged = scheduleText),
            ),
        ),
        SettingsSection(
            id = "startup.interception",
            page = SettingsPageIds.STARTUP,
            title = "启动拦截",
            order = 300,
            rows = listOf(
                SettingsRow.Toggle("startup.report", "屏蔽启动上报", "拦截 ROM 与时区上报。", SettingIds.KEY_DISABLE_STARTUP_REPORT),
                SettingsRow.Toggle("startup.device-track", "屏蔽设备追踪", "拦截主界面设备追踪入口。", SettingIds.KEY_DISABLE_TRACK_DEVICE_INFO),
                SettingsRow.Toggle("startup.fock-report", "屏蔽 Fock 上报", "拦截 Fock native 设备信息上报。", SettingIds.KEY_DISABLE_FOCK_REPORT),
                SettingsRow.Toggle("startup.download-game", "禁止自动下载游戏", "拦截 MainGroupActivity.downloadGame。", SettingIds.KEY_DISABLE_DOWNLOAD_GAME),
            ),
        ),
    )
}
