package com.xiyunmn.qdhelper.feature.readtime

import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.settings.api.SettingsBooleanChangedAction
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsDoubleChangedAction
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection
import java.util.Locale

/**
 * Reading-time settings, rendered on the account page below auto sign-in.
 *
 * Lives in the read-time feature module so the cloud probe and multiplier logic never cross a
 * feature boundary — the section is surfaced on the account page purely through the shared page id.
 */
object ReadTimeSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("read_time.settings")
    override val owner = OwnerId("read-time")
    override val sections = listOf(
        SettingsSection(
            id = "account.read_time",
            page = SettingsPageIds.ACCOUNT,
            title = "阅读时长",
            order = 200,
            rows = listOf(
                SettingsRow.Toggle(
                    id = "read_time.multiplier.enabled",
                    title = "阅读时长倍率",
                    summary = "按倍率调整上报给服务端的阅读时长，修改后即时生效。高风险操作。",
                    key = SettingIds.KEY_READ_TIME_MULTIPLIER_ENABLED,
                    defaultValue = false,
                    onChanged = SettingsBooleanChangedAction { scope, _ ->
                        scope.refresh(SettingsPageIds.ACCOUNT)
                    },
                    keywords = setOf("阅读时长", "倍率", "上报"),
                ),
                SettingsRow.Slider(
                    id = "read_time.multiplier.value",
                    title = "倍率",
                    summary = "0.1x – 2.5x，步进 0.1x。手动调整后覆盖本账号的自动限制；后续检测到折损时仍会自动回退。",
                    key = SettingIds.KEY_READ_TIME_MULTIPLIER_VALUE,
                    minValue = ReadTimeMultiplierPolicy.MIN_MULTIPLIER,
                    maxValue = ReadTimeMultiplierPolicy.MAX_MULTIPLIER,
                    step = 0.1,
                    defaultValue = ReadTimeMultiplierPolicy.DEFAULT_MULTIPLIER,
                    valueFormatter = { value ->
                        val automaticLimit = HostSessionStore.contextOrNull()?.let { context ->
                            ReadTimeCloudAuditStore.automaticLimit(context, ReadTimeRuntime.userId(context))
                        }
                        val effective = ReadTimeMultiplierPolicy.effectiveMultiplier(value, automaticLimit)
                        String.format(Locale.US, "设定 %.1fx · 生效 %.3fx", value, effective)
                    },
                    onCommitted = SettingsDoubleChangedAction { scope, _ ->
                        val activity = scope.activity
                        val applied = ReadTimeRuntime.session(activity.applicationContext, activity.classLoader)
                            ?.overrideAutomaticLimit() == true
                        if (!applied) android.widget.Toast.makeText(activity, "自动倍率限制未清除，请确认当前账号后重试", android.widget.Toast.LENGTH_SHORT).show()
                        scope.refresh(SettingsPageIds.ACCOUNT)
                    },
                    keywords = setOf("倍率"),
                ),
                SettingsRow.Toggle(
                    id = "read_time.local_display.real",
                    title = "本地显示真实时长",
                    summary = "按当前账号汇总本地记录的原始时长，显示今日和本周统计。",
                    key = SettingIds.KEY_READ_TIME_LOCAL_DISPLAY_REAL,
                    defaultValue = false,
                    keywords = setOf("本地显示", "真实时长", "数据库"),
                ),
                SettingsRow.Action(
                    id = "read_time.report_audit",
                    tool = ReadTimeAuditTool,
                    title = "查看阅读时长上报记录",
                    summary = "保留今日最近 50 条入库与宿主网络成功回调，并显示云端增量、实际认可倍率、折损时长及实际倍率限制。",
                    actionText = "查看",
                    keywords = setOf("上报记录", "统计", "额外时长"),
                ),
                SettingsRow.Action(
                    id = "read_time.cloud_probe",
                    tool = ReadTimeCloudTool,
                    title = "查看云端阅读时长",
                    summary = "拉取服务端记录并与上次基线核验；确认折损时自动限制实际倍率。仅在联网时可用。",
                    actionText = "查询",
                    keywords = setOf("云端", "核对"),
                ),
                SettingsRow.Hint(
                    id = "read_time.hint",
                    text = "云端查询始终显示服务端原始统计；自动限制按 0.001x 向下取整且不低于 1.000x。关闭倍率后新记录恢复真实值。",
                ),
            ),
        ),
    )
}
