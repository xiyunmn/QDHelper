package com.xiyunmn.qdhelper.feature.networkidentity

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object NetworkIdentitySettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("network-identity.settings")
    override val owner = OwnerId("network-identity")
    override val sections = listOf(
        SettingsSection(
            id = "network-identity.dns",
            page = SettingsPageIds.NETWORK_IDENTITY,
            title = "DNS 设置",
            order = 200,
            rows = listOf(
                SettingsRow.Toggle("network-identity.dns-enabled", "自定义 DNS", "指定腾讯 DNS 网络栈，并可追加 HTTP DNS 域名映射。", SettingIds.KEY_CUSTOM_DNS_ENABLED, keywords = setOf("dns")),
                SettingsRow.TextInput(
                    "network-identity.dns-stack",
                    "网络栈模式",
                    "",
                    SettingIds.KEY_CUSTOM_DNS_NET_STACK,
                    "3",
                    validator = { value -> value.trim() in setOf("1", "2", "3") },
                ),
                SettingsRow.TextInput("network-identity.dns-rules", "DNS 规则", "示例：qidian.com=1.2.3.4,5.6.7.8；空行和 # 注释会被忽略。", SettingIds.KEY_CUSTOM_DNS_RULES, "qidian.com=1.2.3.4", singleLine = false, keywords = setOf("dns规则", "HTTP DNS")),
            ),
        ),
    )
}
