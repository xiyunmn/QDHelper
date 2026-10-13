package com.xiyunmn.qdhelper.feature.maintenance

import com.xiyunmn.qdhelper.feature.maintenance.ui.MaintenanceConfigFilePicker
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.settings.api.SettingsAction
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object MaintenanceSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("maintenance.settings")
    override val owner = OwnerId("maintenance")
    override val sections = listOf(
        SettingsSection(
            id = "maintenance.account-assistance",
            page = SettingsPageIds.ACCOUNT,
            title = "账号",
            order = 120,
            groupId = "account.assistance",
            rows = listOf(
                SettingsRow.Action(
                    id = "maintenance.user-lookup",
                    tool = com.xiyunmn.qdhelper.feature.maintenance.ui.UserLookupTool,
                    title = "查找用户主页",
                    summary = "通过用户 ID 反查用户名，并打开原生个人主页。",
                    actionText = "查找",
                ),
            ),
        ),
        SettingsSection(
            id = "maintenance.actions",
            page = SettingsPageIds.MAINTENANCE,
            title = "维护",
            order = 100,
            rows = listOf(
                SettingsRow.Action(
                    id = "maintenance.clear-host-data",
                    tool = com.xiyunmn.qdhelper.feature.maintenance.ui.ClearHostDataTool,
                    title = "保留登录态清数据",
                    summary = "保留 shared_prefs、databases、app_webview 和 files，清理缓存与可重建目录。",
                    actionText = "清理",
                ),
                SettingsRow.Action(
                    id = "maintenance.export-config",
                    title = "导出配置",
                    summary = "将普通设置与书籍屏蔽 YAML 打包为 ZIP 文件。",
                    actionText = "导出",
                    action = SettingsAction { MaintenanceConfigFilePicker.exportConfig(it.activity) },
                ),
                SettingsRow.Action(
                    id = "maintenance.import-config",
                    title = "导入配置",
                    summary = "选择 QDHelper ZIP 配置包；旧版单 JSON 不再支持。",
                    actionText = "导入",
                    action = SettingsAction { scope ->
                        MaintenanceConfigFilePicker.importConfig(scope.activity) {
                            scope.refresh(SettingsPageIds.HOME)
                        }
                    },
                ),
                SettingsRow.Action(
                    id = "maintenance.export-book-shield-rules",
                    title = "导出内容屏蔽规则",
                    summary = "仅导出当前最后一次有效的 rules.yaml，不包含其他设置。",
                    actionText = "导出",
                    action = SettingsAction { MaintenanceConfigFilePicker.exportBookShieldRules(it.activity) },
                ),
                SettingsRow.Action(
                    id = "maintenance.import-book-shield-rules",
                    title = "导入内容屏蔽规则",
                    summary = "选择 YAML 并整体替换当前规则，校验通过后立即生效。",
                    actionText = "导入",
                    action = SettingsAction { scope ->
                        MaintenanceConfigFilePicker.importBookShieldRules(scope.activity) {
                            scope.refresh(SettingsPageIds.MAINTENANCE)
                        }
                    },
                ),
            ),
        ),
    )
}
