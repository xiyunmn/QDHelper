package com.xiyunmn.qdhelper.settings.ui.compose.presentation

import android.content.Context
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingScope
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.schema.SettingSchemaCatalog
import com.xiyunmn.qdhelper.settings.api.SettingsRow

internal enum class ApplyMode(val label: String) {
    Immediate(""), PageReload("重新进入页面生效"), HostRestart("需重启"), NextColdStart("下次启动生效"),
}

/** Immutable process-start baseline. Closing a dialog cannot acknowledge unapplied changes. */
internal object SettingsApplyPolicy {
    private val baseline = mutableMapOf<String, Any>()
    private var captured = false
    private val welfareActions = listOf(SettingIds.KEY_WELFARE_REWARD_AD_SKIP_ENABLED, SettingIds.KEY_WELFARE_COMBO_REWARD_SKIP_ENABLED, SettingIds.KEY_WELFARE_REWARD_POPUP_SUPPRESS_ENABLED)
    private val conditionalInstall = welfareActions + listOf(
        SettingIds.KEY_AUTO_SIGN_IN_ENABLED, SettingIds.KEY_AUTO_INVEST_ENABLED,
        SettingIds.KEY_CUSTOM_DNS_ENABLED,
        SettingIds.KEY_DEBUG_DISCOVERY_ENABLED,
        SettingIds.KEY_DEBUG_CALL_PROBE_ENABLED, SettingIds.KEY_DEBUG_DEVICE_IDENTITY_PROBE_ENABLED,
        SettingIds.KEY_DEBUG_WELFARE_REWARD_PROBE_ENABLED, SettingIds.KEY_DETAILED_LOG_ENABLED,
    )
    @Synchronized fun capture(context: Context) {
        if (captured) return
        ConfigRuntime.initialize(context)
        SettingSchemaCatalog.settingKeys.filter { it.scope != SettingScope.RUNTIME }.forEach { key ->
            baseline[key.rawKey] = when (key.valueType) {
                Boolean::class -> ConfigRuntime.readBoolean(key.rawKey)
                Long::class -> ConfigRuntime.readLong(key.rawKey)
                else -> ConfigRuntime.readString(key.rawKey)
            }
        }
        captured = true
    }
    fun mode(row: SettingsRow): ApplyMode = when {
        row is SettingsRow.Action || row is SettingsRow.Hint || row is SettingsRow.ExternalLink || row is SettingsRow.PageLink -> ApplyMode.Immediate
        row.id == "core.module-enabled" -> if (baseline["module.enabled"] == false && conditionalInstall.any(ConfigRuntime::readBoolean)) ApplyMode.HostRestart else ApplyMode.Immediate
        row.id.startsWith("glass-bars.") || row.id.startsWith("read_time.") -> ApplyMode.Immediate
        row.id.startsWith("reader-font.global") -> ApplyMode.HostRestart
        // Account lifecycle hooks are conditionally installed by FeatureCatalogComposition.
        row.id.startsWith("account.") && row is SettingsRow.Toggle -> if (installed(row.key)) ApplyMode.Immediate else ApplyMode.HostRestart
        row.id in setOf("welfare.reward-ad", "welfare.combo-reward", "welfare.reward-popup") ->
            if (!welfareActions.any(::installed)) ApplyMode.HostRestart else if (row.id == "welfare.reward-popup") ApplyMode.PageReload else ApplyMode.Immediate
        row.id == "welfare.reward-probe" -> ApplyMode.HostRestart
        row.id in setOf("diagnostics.detailed-log", "diagnostics.discovery", "diagnostics.call-probe", "diagnostics.device-identity", "book-shield.trace") -> ApplyMode.HostRestart
        row.id.startsWith("network-identity.") -> if (row.id == "network-identity.dns-rules") {
            val enabled = installed(com.xiyunmn.qdhelper.config.SettingIds.KEY_CUSTOM_DNS_ENABLED)
            if (enabled) ApplyMode.Immediate else ApplyMode.HostRestart
        } else ApplyMode.HostRestart
        row.id.startsWith("startup.") && row.id !in setOf("startup.report", "startup.device-track", "startup.fock-report", "startup.download-game") -> ApplyMode.NextColdStart
        row.id.startsWith("reader.") || row.id.startsWith("reader-font.") || row.id.startsWith("bookshelf.") || row.id.startsWith("navigation.") || row.id.startsWith("page-customization.") || row.id.startsWith("content-tools.") || row.id.startsWith("book-shield.") -> ApplyMode.PageReload
        else -> ApplyMode.Immediate
    }
    private fun installed(key: String) = baseline["module.enabled"] == true && baseline[key] == true
    fun pending(catalog: SettingsPresentationCatalog, read: (String) -> Any): List<SettingsRow> =
        catalog.rows.values.filter { row ->
            mode(row) == ApplyMode.HostRestart && row.settingKeys.any { key ->
                val isRoleEnable = row is SettingsRow.DynamicChoice && catalog.rows.values.any { it is SettingsRow.Toggle && it.key == key }
                !isRoleEnable && baseline[key]?.let { it != read(key) } == true
            }
        }
}
