package com.xiyunmn.qdhelper.feature.welfare

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection

object WelfareSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("welfare.settings")
    override val owner = OwnerId("welfare")
    override val sections = listOf(
        SettingsSection(
            id = "welfare.rewards",
            page = SettingsPageIds.WELFARE,
            title = "福利中心",
            order = 100,
            rows = listOf(
                SettingsRow.Toggle("welfare.reward-ad", "普通激励广告免广告领奖励", "覆盖福利中心普通激励广告与相关奖励桥。", SettingIds.KEY_WELFARE_REWARD_AD_SKIP_ENABLED, keywords = setOf("激励视频", "广告奖励", "免广告")),
                SettingsRow.Toggle("welfare.combo-reward", "组合任务免广告领奖励", "覆盖福利中心组合任务广告链路。", SettingIds.KEY_WELFARE_COMBO_REWARD_SKIP_ENABLED, keywords = setOf("组合任务", "分组任务", "H5激励", "白泽", "宇航员", "装扮", "白泽宇航员")),
                SettingsRow.Toggle("welfare.reward-popup", "移除任务奖励获得弹窗", "福利中心激励广告奖励返回后，自动关闭“恭喜获得”类奖励覆盖层。", SettingIds.KEY_WELFARE_REWARD_POPUP_SUPPRESS_ENABLED, keywords = setOf("奖励弹窗", "任务奖励弹窗", "恭喜获得", "白泽", "宇航员", "装扮", "白泽宇航员")),
            ),
        ),
        SettingsSection(
            id = "welfare.diagnostics",
            page = SettingsPageIds.DIAGNOSTICS,
            title = "调试",
            order = 400,
            groupId = "diagnostics.debug",
            resetKeys = setOf(
                SettingIds.KEY_DEBUG_WELFARE_REWARD_SKIP_ONCE_ENABLED,
                SettingIds.KEY_DEBUG_WELFARE_REWARD_FLUTTER_SKIP_ONCE_ENABLED,
                SettingIds.KEY_DEBUG_WELFARE_REWARD_COMBO_SKIP_ONCE_ENABLED,
            ),
            rows = listOf(
                SettingsRow.Toggle(
                    id = "welfare.reward-probe",
                    title = "福利奖励广告探针",
                    summary = "重启后记录福利中心激励视频链路，只写脱敏摘要。需同时开启详细日志总开关；脱敏日志保存在本机，超限自动轮转。",
                    key = SettingIds.KEY_DEBUG_WELFARE_REWARD_PROBE_ENABLED,
                ),
            ),
        ),
    )
}
