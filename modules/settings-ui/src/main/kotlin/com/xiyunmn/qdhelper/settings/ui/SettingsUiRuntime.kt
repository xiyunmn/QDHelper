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
        val frameworkName: String = "未知",
    )

    @Volatile
    private var configuration: Lazy<Configuration>? = null

    fun configure(
        contributions: () -> List<SettingsContribution>,
        restartHost: (Activity, onStarted: () -> Unit) -> Unit,
        applyUiSettings: (Activity) -> Unit,
        resolver: ResolverDiagnosticsGateway? = null,
        moduleVersion: String = "",
        frameworkName: String = "未知",
    ) {
        // Presentation metadata is needed only by the settings panel. Runtime settings
        // and their process snapshot remain initialized on the original lifecycle path.
        configuration = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val resolved = contributions().toList()
            DefaultSettingRegistry(keys = SettingSchemaCatalog.settingKeys, contributions = resolved)
            Configuration(resolved, restartHost, applyUiSettings, resolver, moduleVersion, frameworkName)
        }
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
        checkNotNull(configuration) { "SettingsUiRuntime is not configured" }.value
}
