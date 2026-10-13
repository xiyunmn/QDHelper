package com.xiyunmn.qdhelper.feature.diagnostics

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.gateway.ResolverDiagnosticsGateway
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection
import com.xiyunmn.qdhelper.settings.api.SettingsSummaryProvider

fun diagnosticsSettingsContribution(
    resolver: ResolverDiagnosticsGateway,
): SettingsContribution = DiagnosticsSettingsContribution(resolver)

private class DiagnosticsSettingsContribution(
    resolver: ResolverDiagnosticsGateway,
) : SettingsContribution {
    override val id = SettingsContributionId("diagnostics.settings")
    override val owner = OwnerId("diagnostics")
    override val sections = listOf(
        SettingsSection(
            id = "diagnostics.logs",
            page = SettingsPageIds.DIAGNOSTICS,
            title = "调试",
            order = 100,
            groupId = "diagnostics.debug",
            rows = listOf(
                SettingsRow.Toggle("diagnostics.detailed-log", "详细日志", "总开关，默认关闭；开启并重启后按下方子项记录，关闭后停止记录。日志仅保存于本机。", SettingIds.KEY_DETAILED_LOG_ENABLED),
                SettingsRow.Action("diagnostics.export-logs", "导出日志", "导出当前及轮转日志为 ZIP；分享前请检查内容。", "导出", action = com.xiyunmn.qdhelper.settings.api.SettingsAction { com.xiyunmn.qdhelper.feature.diagnostics.ui.DiagnosticsLogActions.export(it.activity) }),
                SettingsRow.Action("diagnostics.clear-logs", "清理日志", "删除本机模块日志；详细日志开启时仍会继续记录新日志。", "清理", action = com.xiyunmn.qdhelper.settings.api.SettingsAction { com.xiyunmn.qdhelper.feature.diagnostics.ui.DiagnosticsLogActions.clear(it.activity) }),
                SettingsRow.Toggle("diagnostics.discovery", "静态发现日志", "重启后记录目标类和方法签名。", SettingIds.KEY_DEBUG_DISCOVERY_ENABLED),
                SettingsRow.Toggle("diagnostics.call-probe", "调用探针日志", "重启后记录目标方法命中和参数类型。", SettingIds.KEY_DEBUG_CALL_PROBE_ENABLED),
            ),
        ),
        SettingsSection(
            id = "diagnostics.probes",
            page = SettingsPageIds.DIAGNOSTICS,
            title = "调试",
            order = 300,
            groupId = "diagnostics.debug",
            rows = listOf(
                SettingsRow.Toggle("diagnostics.device-identity", "设备身份探针", "重启后记录 AndroidId/QIMEI/OAID/Freshman/限免/Fock/网络指纹等新设备相关信号。", SettingIds.KEY_DEBUG_DEVICE_IDENTITY_PROBE_ENABLED, keywords = setOf("新设备", "ssaid", "oaid", "qimei", "限免")),
                SettingsRow.Toggle("diagnostics.host-collect-json", "宿主采集 JSON 抓取", "开启后在「查看 Cookie / 身份」的完整模式里展示宿主 native 采集的原始 JSON（含多开 obs_isMultiBoxBy*、root、frida 等检测字段的键名与原始值）；仅本地显示、不上传。", SettingIds.KEY_DEBUG_HOST_COLLECT_JSON_ENABLED, keywords = setOf("多开", "多看", "obs_isMultiBox", "multi_box", "风控字段", "raw json", "采集字段")),
            ),
        ),
        SettingsSection(
            id = "diagnostics.dexkit",
            page = SettingsPageIds.DIAGNOSTICS,
            title = "调试",
            order = 500,
            groupId = "diagnostics.debug",
            resetKeys = setOf(
                SettingIds.KEY_DEBUG_DEXKIT_ENABLED,
                SettingIds.KEY_DEBUG_DEXKIT_FORCE_RESCAN,
                SettingIds.KEY_DEBUG_DEXKIT_LOG_CANDIDATES,
            ),
            rows = listOf(
                SettingsRow.Action(
                    id = "diagnostics.resolver-status",
                    tool = com.xiyunmn.qdhelper.feature.diagnostics.ui.ResolverSettingsTool(resolver),
                    title = "DexKit 解析状态",
                    summary = "查看每条宿主目标规则的解析状态并可手动重新解析。",
                    actionText = "查看",
                    summaryProvider = SettingsSummaryProvider {
                        val snapshot = resolver.snapshot()
                        "当前成功 ${snapshot.success} / 总 ${snapshot.total}；点击查看每条规则解析情况并可手动重新解析。"
                    },
                    keywords = setOf("dexkit", "解析状态", "成功数"),
                ),
            ),
        ),
        SettingsSection(
            id = "diagnostics.cookie",
            page = SettingsPageIds.DIAGNOSTICS,
            title = "调试",
            order = 600,
            groupId = "diagnostics.debug",
            rows = listOf(
                SettingsRow.Action(
                    id = "diagnostics.cookie-identity",
                    tool = com.xiyunmn.qdhelper.feature.diagnostics.ui.CookieSettingsTool,
                    title = "Cookie 与指纹",
                    summary = "本地读取账号身份，并显示宿主 native 的 bootloader、root 与风险诊断结果；不写日志、不上传。",
                    actionText = "查看",
                    keywords = setOf("cookie", "qimei", "oaid", "androidid", "身份", "指纹", "bootloader", "root", "native", "风控"),
                ),
            ),
        ),
    )
}
