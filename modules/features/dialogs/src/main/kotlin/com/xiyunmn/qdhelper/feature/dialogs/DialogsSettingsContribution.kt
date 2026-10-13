package com.xiyunmn.qdhelper.feature.dialogs

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object DialogsSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("dialogs.settings")
    override val owner = OwnerId("dialogs")
    override val sections = listOf(
        SettingsSection(
            id = "dialogs.prompts",
            page = SettingsPageIds.DIALOGS,
            title = "弹窗与引导",
            order = 100,
            rows = listOf(
                SettingsRow.Toggle("dialogs.privacy-update", "屏蔽隐私更新弹窗", "短路主界面隐私版本检查。", SettingIds.KEY_SUPPRESS_PRIVACY_VERSION_DIALOG),
                SettingsRow.Toggle("dialogs.update-check", "屏蔽版本更新检查", "拦截启动和后台自动检查；关于页手动检查仍可使用。", SettingIds.KEY_SUPPRESS_UPDATE_CHECK),
                SettingsRow.Toggle("dialogs.notification-permission", "屏蔽通知权限提示", "拦截应用内通知授权引导和开启推送浮窗。", SettingIds.KEY_SUPPRESS_NOTIFICATION_PERMISSION),
                SettingsRow.Toggle("dialogs.teenager-entry", "屏蔽青少年入口弹窗", "拦截青少年模式的推广弹窗。", SettingIds.KEY_SUPPRESS_TEENAGER_ENTER_DIALOG),
            ),
        ),
    )
}
