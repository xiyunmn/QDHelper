package com.xiyunmn.qdhelper.composition

import android.content.Context
import com.xiyunmn.qdhelper.feature.account.AutoInvestFeature
import com.xiyunmn.qdhelper.feature.account.AutoSignInFeature
import com.xiyunmn.qdhelper.feature.api.runtime.InstallableFeature
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.target.AccountTargetKeys
import com.xiyunmn.qdhelper.host.api.target.BookshelfTargetKeys
import com.xiyunmn.qdhelper.host.api.target.CircleTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ContentToolsTargetKeys
import com.xiyunmn.qdhelper.host.api.target.DialogTargetKeys
import com.xiyunmn.qdhelper.host.api.target.NavigationTargetKeys
import com.xiyunmn.qdhelper.host.api.target.PageCustomizationTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ReaderFontTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ReaderTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ReadTimeTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ShieldTargetKeys
import com.xiyunmn.qdhelper.host.api.target.StartupTargetKeys
import com.xiyunmn.qdhelper.host.api.target.TargetId
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import com.xiyunmn.qdhelper.feature.bookshelf.BookshelfFeature
import com.xiyunmn.qdhelper.feature.bookshelf.BookshelfCustomizationFeature
import com.xiyunmn.qdhelper.feature.contenttools.BookDetailFreeCopyFeature
import com.xiyunmn.qdhelper.feature.contenttools.BookDetailOriginalImageFeature
import com.xiyunmn.qdhelper.feature.contenttools.BookFansRankFeature
import com.xiyunmn.qdhelper.feature.contenttools.CardPlazaUnlockFeature
import com.xiyunmn.qdhelper.feature.contenttools.CircleFreeCopyFeature
import com.xiyunmn.qdhelper.feature.contenttools.ProfileH5Feature
import com.xiyunmn.qdhelper.feature.contenttools.ProfilePrivacyRestoreFeature
import com.xiyunmn.qdhelper.feature.contenttools.UploadWatermarkFeature
import com.xiyunmn.qdhelper.feature.networkidentity.NetworkPrivacyFeature
import com.xiyunmn.qdhelper.feature.reader.ReaderPrivacyFeature
import com.xiyunmn.qdhelper.feature.startup.StartupPrivacyFeature
import com.xiyunmn.qdhelper.feature.dialogs.DialogSuppressFeature
import com.xiyunmn.qdhelper.feature.networkidentity.CustomDnsFeature
import com.xiyunmn.qdhelper.feature.navigation.NavigationCustomizationFeature
import com.xiyunmn.qdhelper.feature.glassbars.GlassBarsFeature
import com.xiyunmn.qdhelper.feature.pagecustomization.PageCustomizationFeature
import com.xiyunmn.qdhelper.feature.diagnostics.identity.DeviceIdentityProbeFeature
import com.xiyunmn.qdhelper.feature.diagnostics.probe.TargetCallProbeFeature
import com.xiyunmn.qdhelper.feature.diagnostics.probe.TargetDiscoveryFeature
import com.xiyunmn.qdhelper.feature.diagnostics.probe.TargetObjectShapeProbeFeature
import com.xiyunmn.qdhelper.feature.contenttools.ProfileOriginalImageFeature
import com.xiyunmn.qdhelper.feature.contenttools.ProfileUserIdFeature
import com.xiyunmn.qdhelper.feature.reader.ChapterCommentShareLimitFeature
import com.xiyunmn.qdhelper.feature.reader.FreeCopyFeature
import com.xiyunmn.qdhelper.feature.reader.ReadPageCleanFeature
import com.xiyunmn.qdhelper.feature.reader.ReaderBodyCopyFeature
import com.xiyunmn.qdhelper.feature.reader.ReaderBackgroundFeature
import com.xiyunmn.qdhelper.feature.readtime.ReadTimeMultiplierFeature
import com.xiyunmn.qdhelper.feature.readerfont.hook.CustomReaderFontFeature
import com.xiyunmn.qdhelper.feature.readerfont.hook.GlobalFontFeature
import com.xiyunmn.qdhelper.settings.ui.SettingsEntryFeature
import com.xiyunmn.qdhelper.feature.bookshield.hook.BookShieldFeature
import com.xiyunmn.qdhelper.feature.bookshield.hook.BookShieldLastPageFeature
import com.xiyunmn.qdhelper.feature.bookshield.hook.BookShieldQuickAddFeature
import com.xiyunmn.qdhelper.feature.startup.StartupFeature
import com.xiyunmn.qdhelper.feature.welfare.hook.WelfareRewardActionFeature
import com.xiyunmn.qdhelper.feature.welfare.hook.WelfareRewardProbeFeature
import com.xiyunmn.qdhelper.config.SettingsRuntime

internal data class ConfiguredFeatureProvider(
    val id: String,
    val feature: InstallableFeature,
)

private data class ConfiguredFeatureSpec(
    val id: String,
    val hotReloadable: Boolean,
    val requiredTargets: Set<TargetId>,
    val featureFactory: () -> InstallableFeature,
    val enabled: (Context?) -> Boolean,
)

internal object FeatureCatalogComposition {
    private val definitions = buildDefinitions()

    fun selected(context: Context?, hotReload: Boolean): List<ConfiguredFeatureProvider> {
        return selectedSpecs(context, hotReload).map { spec ->
            val feature = spec.featureFactory()
            ConfiguredFeatureProvider(
                id = spec.id,
                feature = feature,
            )
        }
    }

    fun selectedForResolvedTargets(context: Context?, targetIds: Set<TargetId>): List<ConfiguredFeatureProvider> {
        return selectedSpecsForResolvedTargets(context, targetIds)
            .map { spec ->
                val feature = spec.featureFactory()
                ConfiguredFeatureProvider(
                    id = spec.id,
                    feature = feature,
                )
            }
    }

    fun selectedCountBeforeHotReloadFilter(context: Context?): Int =
        definitions.count { it.enabled(context) }

    private fun selectedSpecs(context: Context?, hotReload: Boolean): List<ConfiguredFeatureSpec> =
        definitions
            .filter { it.enabled(context) }
            .filter { !hotReload || it.hotReloadable }

    private fun selectedSpecsForResolvedTargets(
        context: Context?,
        targetIds: Set<TargetId>,
    ): List<ConfiguredFeatureSpec> {
        if (targetIds.isEmpty()) return emptyList()
        return selectedSpecs(context, hotReload = false)
            .filter { spec -> spec.requiredTargets.any(targetIds::contains) }
    }

    private fun buildDefinitions(): List<ConfiguredFeatureSpec> {
        val rows = listOf(
            row("reader.privacy-gates") { ReaderPrivacyFeature },
            row("startup.privacy-gates") { StartupPrivacyFeature },
            row("network-identity.privacy-gates") { NetworkPrivacyFeature },
            row("content-tools.upload-watermark") { UploadWatermarkFeature },
            row("reader.chapter-comment-share-limit") { ChapterCommentShareLimitFeature },
            row(
                "startup.core",
                requiredTargets = StartupTargetKeys.all.mapTo(linkedSetOf()) { it.id },
            ) { StartupFeature },
            row("settings.entry") { SettingsEntryFeature },
            row(
                "dialogs.suppress",
                requiredTargets = DialogTargetKeys.all.mapTo(linkedSetOf()) { it.id },
            ) { DialogSuppressFeature },
            row(
                "bookshelf.core",
                requiredTargets = setOf(BookshelfTargetKeys.appConfigCurrent.id),
            ) { BookshelfFeature },
            row("book-shield.quick-add") { BookShieldQuickAddFeature },
            row("book-shield.last-page") { BookShieldLastPageFeature },
            row("content-tools.book-fans-rank") { BookFansRankFeature },
            row(
                "reader.clean",
                requiredTargets = setOf(ReaderTargetKeys.chapterEndAppend.id),
            ) { ReadPageCleanFeature },
            row(
                "reader.free-copy",
                requiredTargets = setOf(ReaderTargetKeys.chapterCommentCopyBindMethods.id),
            ) { FreeCopyFeature },
            row("reader.background") { ReaderBackgroundFeature },
            row("content-tools.book-detail-free-copy") { BookDetailFreeCopyFeature },
            row("content-tools.book-detail-original-image") { BookDetailOriginalImageFeature },
            row("content-tools.card-plaza-local-unlock") { CardPlazaUnlockFeature },
            row("content-tools.profile-h5") { ProfileH5Feature },
            row("content-tools.profile-original-image") { ProfileOriginalImageFeature },
            row("content-tools.profile-user-id") { ProfileUserIdFeature },
            row(
                "content-tools.profile-privacy-restore",
                requiredTargets = ContentToolsTargetKeys.profilePrivacyRestore.mapTo(linkedSetOf()) { it.id },
            ) { ProfilePrivacyRestoreFeature },
            row(
                "content-tools.circle-free-copy",
                requiredTargets = setOf(
                    CircleTargetKeys.listBind.id,
                    CircleTargetKeys.detailBindMethods.id,
                    CircleTargetKeys.detailData.id,
                ),
            ) { CircleFreeCopyFeature },
            row(
                "reader-font.custom",
                requiredTargets = ReaderFontTargetKeys.reader.mapTo(linkedSetOf()) { it.id },
            ) { CustomReaderFontFeature },
            row(
                "reader-font.global",
                requiredTargets = ReaderFontTargetKeys.global.mapTo(linkedSetOf()) { it.id },
            ) { GlobalFontFeature },
            row(
                "network-identity.dns",
                enabled = SettingsRuntime::isCustomDnsEnabled,
            ) { CustomDnsFeature },
            row(
                "bookshelf.customization",
                requiredTargets = (
                    (BookshelfTargetKeys.all - BookshelfTargetKeys.appConfigCurrent) +
                        ReaderFontTargetKeys.eventBusGetter
                    ).mapTo(linkedSetOf()) { it.id },
            ) { BookshelfCustomizationFeature },
            row(
                "navigation.customization",
                requiredTargets = NavigationTargetKeys.all.mapTo(linkedSetOf()) { it.id },
            ) { NavigationCustomizationFeature },
            row("glass-bars.core", requiredTargets = setOf(NavigationTargetKeys.renderIcon.id)) { GlassBarsFeature },
            row(
                "page-customization.core",
                requiredTargets = PageCustomizationTargetKeys.all.mapTo(linkedSetOf()) { it.id },
            ) { PageCustomizationFeature },
            row(
                "book-shield.core",
                requiredTargets = (ShieldTargetKeys.all + BookshelfTargetKeys.nativeNetBridgeRegister)
                    .mapTo(linkedSetOf()) { it.id },
            ) { BookShieldFeature },
            row(
                "account.auto-sign-in",
                requiredTargets = (AccountTargetKeys.autoSignIn + AccountTargetKeys.userSession)
                    .mapTo(linkedSetOf()) { it.id },
                enabled = SettingsRuntime::isAutoSignInEnabled,
            ) { AutoSignInFeature },
            row(
                "account.auto-invest",
                requiredTargets = (AccountTargetKeys.autoInvest + AccountTargetKeys.userSession +
                    ReadTimeTargetKeys.statisticsMarkReported).mapTo(linkedSetOf()) { it.id },
                enabled = SettingsRuntime::isAutoInvestEnabled,
            ) { AutoInvestFeature },
            row(
                "read-time.multiplier",
                requiredTargets = (ReadTimeTargetKeys.all + AccountTargetKeys.userSession).mapTo(linkedSetOf()) { it.id },
            ) { ReadTimeMultiplierFeature },
            row("reader.body-copy") { ReaderBodyCopyFeature },
            row(
                "diagnostics.target-discovery",
                hotReloadable = false,
                enabled = SettingsRuntime::isDiscoveryProbeEnabled,
            ) { TargetDiscoveryFeature },
            row(
                "diagnostics.target-call",
                hotReloadable = false,
                enabled = SettingsRuntime::isCallProbeEnabled,
            ) { TargetCallProbeFeature },
            row(
                "diagnostics.target-object-shape",
                hotReloadable = false,
                enabled = SettingsRuntime::isCallProbeEnabled,
            ) { TargetObjectShapeProbeFeature },
            row(
                "diagnostics.device-identity",
                hotReloadable = false,
                enabled = SettingsRuntime::isDeviceIdentityProbeEnabled,
            ) { DeviceIdentityProbeFeature },
            row(
                "welfare.action",
                hotReloadable = false,
                requiredTargets = WelfareTargetKeys.all.mapTo(linkedSetOf()) { it.id },
                enabled = SettingsRuntime::isAnyWelfareActionEnabled,
            ) { WelfareRewardActionFeature },
            row(
                "welfare.probe",
                hotReloadable = false,
                requiredTargets = WelfareTargetKeys.all.mapTo(linkedSetOf()) { it.id },
                enabled = { context ->
                    !SettingsRuntime.isAnyWelfareActionEnabled(context) &&
                        SettingsRuntime.isWelfareRewardProbeEnabled(context)
                },
            ) { WelfareRewardProbeFeature },
        )
        return rows
    }

    private fun row(
        id: String,
        hotReloadable: Boolean = true,
        requiredTargets: Set<TargetId> = emptySet(),
        enabled: (Context?) -> Boolean = { true },
        featureFactory: () -> InstallableFeature,
    ): ConfiguredFeatureSpec = ConfiguredFeatureSpec(
        id = id,
        hotReloadable = hotReloadable,
        requiredTargets = requiredTargets,
        featureFactory = featureFactory,
        enabled = enabled,
    )
}

internal fun activeTargetOwners(context: Context?, featureIds: Iterable<String>): Set<OwnerId> {
    val owners = featureIds.mapTo(linkedSetOf()) { featureId -> featureId.substringBefore('.') }
    if (!SettingsRuntime.isCustomDnsEnabled(context)) {
        owners -= "network-identity"
    }
    if (!SettingsRuntime.isAnyWelfareActionEnabled(context) && !SettingsRuntime.isWelfareRewardProbeEnabled(context)) {
        owners -= "welfare"
    }
    return (owners + "shared").map(::OwnerId).toSet()
}
