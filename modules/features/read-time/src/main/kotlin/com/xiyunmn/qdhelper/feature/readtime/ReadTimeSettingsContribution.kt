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
                    summary = "调整上报的阅读时长。",
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
                    summary = "",
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
                        String.format(Locale.US, "实际生效 %.3f×", effective)
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
                    summary = "用于今日和本周统计。",
                    key = SettingIds.KEY_READ_TIME_LOCAL_DISPLAY_REAL,
                    defaultValue = false,
                    keywords = setOf("本地显示", "真实时长", "数据库"),
                ),
                SettingsRow.Action(
                    id = "read_time.report_audit",
                    tool = ReadTimeAuditTool,
                    title = "上报记录",
                    summary = "",
                    actionText = "查看",
                    keywords = setOf("上报记录", "统计", "额外时长"),
                ),
                SettingsRow.Action(
                    id = "read_time.cloud_probe",
                    tool = ReadTimeCloudTool,
                    title = "云端时长",
                    summary = "",
                    actionText = "查询",
                    keywords = setOf("云端", "核对"),
                ),
                SettingsRow.Hint(
                    id = "read_time.hint",
                    text = "倍率可设为0.1～2.5倍，修改即时生效；关闭后，新记录恢复真实时长。\n本地显示统计当前账号的原始时长，云端时长以起点返回为准，查询需联网。\n如果起点认可的时长较少，实际倍率会自动降低；手动调整可重新设定，后续仍可能自动下调。\n上报记录保留今日最近50条，方便核对实际增加的时长。",
                ),
            ),
        ),
    )
}
