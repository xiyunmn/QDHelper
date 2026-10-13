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
            id = "network-identity.identifiers",
            page = SettingsPageIds.NETWORK_IDENTITY,
            title = "标识覆盖",
            order = 100,
            resetKeys = setOf(
                SettingIds.KEY_CUSTOM_QIMEI_LAST_SYNCED,
                SettingIds.KEY_CUSTOM_QIMEI36_LAST_SYNCED,
            ),
            rows = listOf(
                SettingsRow.Toggle("network-identity.multibox-shield", "屏蔽多开检测上报", "净化宿主 4 个多开检测字段（obs_isMultiBoxByPkg/FileStr/ParentDir/Uid），使其在采集 JSON 与 ibex 上报中均为 false。适用于应用分身/多开环境；主空间开启无副作用。", SettingIds.KEY_MULTIBOX_REPORT_SHIELD_ENABLED, keywords = setOf("多开", "多看", "分身", "multi_box", "双开", "obs_isMultiBox")),
                SettingsRow.Toggle("network-identity.qimei-enabled", "自定义 QIMEI", "覆盖宿主读取 QIMEI 的边界；输入为空或格式异常时自动放行。", SettingIds.KEY_CUSTOM_QIMEI_ENABLED, keywords = setOf("qimei")),
                SettingsRow.TextInput("network-identity.qimei", "QIMEI", "填写 QIMEI16 或你要返回的短标识。", SettingIds.KEY_CUSTOM_QIMEI_VALUE, "qimei16", keywords = setOf("qimei16")),
                SettingsRow.TextInput("network-identity.qimei36", "QIMEI36", "可选；留空时尝试复用 QIMEI。", SettingIds.KEY_CUSTOM_QIMEI36_VALUE, "qimei36", keywords = setOf("qimei36")),
                SettingsRow.Toggle("network-identity.guid-enabled", "自定义 GUID", "覆盖账号 ywGuid（DeviceInfo.Guid 与请求签名来源）；仅建议登出态使用，登录态可能破坏账号请求。默认关、高风险。", SettingIds.KEY_CUSTOM_GUID_ENABLED, keywords = setOf("guid", "ywguid", "设备id")),
                SettingsRow.TextInput("network-identity.guid", "GUID", "填写要返回的 ywGuid；空或格式异常时放行。", SettingIds.KEY_CUSTOM_GUID_VALUE, "guid", keywords = setOf("ywguid")),
            ),
        ),
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
