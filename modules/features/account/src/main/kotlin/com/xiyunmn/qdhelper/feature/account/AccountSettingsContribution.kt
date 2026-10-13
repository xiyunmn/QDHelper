package com.xiyunmn.qdhelper.feature.account

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object AccountSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("account.settings")
    override val owner = OwnerId("account")
    override val sections = listOf(
        SettingsSection(
            id = "account.assistance",
            page = SettingsPageIds.ACCOUNT,
            title = "账号",
            order = 100,
            rows = listOf(
                SettingsRow.Toggle(
                    id = "account.auto-sign-in",
                    title = "自动签到",
                    summary = "前台进入起点后自动签到，每天执行一次。",
                    key = SettingIds.KEY_AUTO_SIGN_IN_ENABLED,
                ),
                SettingsRow.Toggle(
                    id = "account.auto-invest",
                    title = "自动投资",
                    summary = "本书当天已上报阅读满 10 分钟后，仅查询一次投资资格，服务端确认未投过且有剩余名额时发起投资。",
                    key = SettingIds.KEY_AUTO_INVEST_ENABLED,
                    keywords = setOf("新书投资", "阅读时长", "投资次数"),
                ),
            ),
        ),
    )
}
