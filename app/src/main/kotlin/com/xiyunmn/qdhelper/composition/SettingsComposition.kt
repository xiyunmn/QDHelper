package com.xiyunmn.qdhelper.composition

import android.content.Context
import com.xiyunmn.qdhelper.feature.account.AccountSettingsContribution
import com.xiyunmn.qdhelper.feature.bookshield.BookShieldSettingsContribution
import com.xiyunmn.qdhelper.feature.bookshelf.BookshelfSettingsContribution
import com.xiyunmn.qdhelper.feature.bookshelf.BookshelfCustomizationFeature
import com.xiyunmn.qdhelper.feature.contenttools.ContentToolsSettingsContribution
import com.xiyunmn.qdhelper.feature.diagnostics.diagnosticsSettingsContribution
import com.xiyunmn.qdhelper.feature.dialogs.DialogsSettingsContribution
import com.xiyunmn.qdhelper.feature.maintenance.MaintenanceSettingsContribution
import com.xiyunmn.qdhelper.feature.navigation.NavigationSettingsContribution
import com.xiyunmn.qdhelper.feature.glassbars.GlassBarsSettingsContribution
import com.xiyunmn.qdhelper.feature.glassbars.GlassBarsFeature
import com.xiyunmn.qdhelper.feature.navigation.NavigationCustomizationFeature
import com.xiyunmn.qdhelper.feature.networkidentity.NetworkIdentitySettingsContribution
import com.xiyunmn.qdhelper.feature.pagecustomization.PageCustomizationSettingsContribution
import com.xiyunmn.qdhelper.feature.pagecustomization.PageCustomizationFeature
import com.xiyunmn.qdhelper.feature.reader.ReaderSettingsContribution
import com.xiyunmn.qdhelper.feature.readerfont.ReaderFontSettingsContribution
import com.xiyunmn.qdhelper.feature.readtime.ReadTimeSettingsContribution
import com.xiyunmn.qdhelper.feature.startup.StartupSettingsContribution
import com.xiyunmn.qdhelper.feature.welfare.WelfareSettingsContribution
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.resolver.diagnostics.resolverDiagnosticsGateway
import com.xiyunmn.qdhelper.settings.api.SettingsContribution
import com.xiyunmn.qdhelper.settings.api.SettingsContributionId
import com.xiyunmn.qdhelper.settings.api.SettingsPageIds
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.api.SettingsSection
import com.xiyunmn.qdhelper.settings.ui.SettingsUiRuntime
import com.xiyunmn.qdhelper.entry.restart.HostRestartTrampolineActivity
import com.xiyunmn.qdhelper.settings.ui.host.HostRestarter

/** Application composition root for the declarative settings surface. */
internal object SettingsComposition {
    fun configure(processName: String, contextProvider: () -> Context?) {
        SettingsUiRuntime.configure(
            contributions = createContributions(processName, contextProvider),
            resolver = resolverDiagnosticsGateway(contextProvider, processName),
            restartHost = { activity, beforeKill ->
                HostRestarter.restartHost(
                    activity = activity,
                    beforeKill = beforeKill,
                    trampolineIntent = HostRestartTrampolineActivity::createIntent,
                )
            },
            applyUiSettings = { activity ->
                BookshelfCustomizationFeature.applyFromSettings(activity)
                NavigationCustomizationFeature.applyFromSettings(activity)
                PageCustomizationFeature.applyFromSettings(activity)
                GlassBarsFeature.applyFromSettings(activity)
            },
        )
    }

    internal fun createContributions(
        processName: String,
        contextProvider: () -> Context?,
    ): List<SettingsContribution> {
        val resolver = resolverDiagnosticsGateway(contextProvider, processName)
        return listOf(
            CoreSettingsContribution,
            StartupSettingsContribution,
            ReaderSettingsContribution,
            ReaderFontSettingsContribution,
            BookshelfSettingsContribution,
            NavigationSettingsContribution,
            GlassBarsSettingsContribution,
            PageCustomizationSettingsContribution,
            ContentToolsSettingsContribution,
            AccountSettingsContribution,
            ReadTimeSettingsContribution,
            WelfareSettingsContribution,
            NetworkIdentitySettingsContribution,
            BookShieldSettingsContribution,
            DialogsSettingsContribution,
            MaintenanceSettingsContribution,
            diagnosticsSettingsContribution(resolver),
            AboutSettingsContribution,
        )
    }
}

private object CoreSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("core.settings")
    override val owner = OwnerId("app")
    override val sections = listOf(
        SettingsSection(
            id = "core.main",
            page = SettingsPageIds.CORE,
            title = "核心",
            order = 100,
            rows = listOf(
                SettingsRow.Toggle(
                    id = "core.module-enabled",
                    title = "启用模块功能",
                    summary = "关闭后仅保留设置入口，所有业务 hook 放行原逻辑。",
                    key = "module.enabled",
                ),
            ),
        ),
    )
}

private object AboutSettingsContribution : SettingsContribution {
    override val id = SettingsContributionId("about.settings")
    override val owner = OwnerId("app")
    override val sections = listOf(
        SettingsSection(
            id = "about.main",
            page = SettingsPageIds.HOME,
            title = "关于",
            order = 10_000,
            rows = listOf(
                SettingsRow.ExternalLink(
                    id = "about.repository",
                    title = "仓库",
                    summary = PROJECT_REPOSITORY_URL,
                    url = PROJECT_REPOSITORY_URL,
                    keywords = setOf("GitHub", "源码", "开源", "仓库地址"),
                ),
                SettingsRow.ExternalLink(
                    id = "about.author",
                    title = "作者",
                    summary = PROJECT_AUTHOR,
                    url = PROJECT_AUTHOR_URL,
                    keywords = setOf("GitHub", "开发者"),
                ),
            ),
        ),
    )
}

private const val PROJECT_REPOSITORY_URL = "https://github.com/xiyunmn/QDHelper"
private const val PROJECT_AUTHOR = "xiyunmn"
private const val PROJECT_AUTHOR_URL = "https://github.com/xiyunmn"
