package com.xiyunmn.qdhelper.settings.ui

import android.app.Activity
import android.content.Context
import com.xiyunmn.qdhelper.host.api.gateway.ResolverDiagnosticsGateway
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingsApplyPolicy
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingsPresentationCatalog
import com.xiyunmn.qdhelper.config.schema.SettingSchemaCatalog
import com.xiyunmn.qdhelper.settings.api.DefaultSettingRegistry
import com.xiyunmn.qdhelper.settings.api.SettingsContribution

object SettingsUiRuntime {
    data class Configuration(
        val contributions: List<SettingsContribution>,
        val restartHost: (Activity, onStarted: () -> Unit) -> Unit,
        val applyUiSettings: (Activity) -> Unit,
        val resolver: ResolverDiagnosticsGateway? = null,
        val moduleVersion: String = "",
    )

    @Volatile
    private var configuration: Configuration? = null

    fun configure(
        contributions: List<SettingsContribution>,
        restartHost: (Activity, onStarted: () -> Unit) -> Unit,
        applyUiSettings: (Activity) -> Unit,
        resolver: ResolverDiagnosticsGateway? = null,
        moduleVersion: String = "",
    ) {
        DefaultSettingRegistry(
            keys = SettingSchemaCatalog.settingKeys,
            contributions = contributions,
        )
        val next = Configuration(contributions.toList(), restartHost, applyUiSettings, resolver, moduleVersion)
        configuration = next
    }

    fun captureProcessSettings(context: Context) = SettingsApplyPolicy.capture(context)

    /** Used by the composition-root test and when opening Compose, before displaying any UI. */
    fun validatePresentation(contributions: List<SettingsContribution>) {
        val presentation = SettingsPresentationCatalog(contributions)
        presentation.destinations.keys.forEach { destination ->
            com.xiyunmn.qdhelper.settings.ui.compose.presentation.settingsPageBlocks(presentation, destination)
        }
        listOf("detail", "directory").forEach { place ->
            com.xiyunmn.qdhelper.settings.ui.compose.presentation.settingsPageBlocks(presentation, "glass", place)
        }
    }

    internal fun requireConfiguration(): Configuration =
        checkNotNull(configuration) { "SettingsUiRuntime is not configured" }
}
