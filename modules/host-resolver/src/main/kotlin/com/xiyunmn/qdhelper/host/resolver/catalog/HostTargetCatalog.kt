package com.xiyunmn.qdhelper.host.resolver.catalog

import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import com.xiyunmn.qdhelper.host.api.target.AccountTargetKeys
import com.xiyunmn.qdhelper.host.api.target.Cardinality
import com.xiyunmn.qdhelper.host.api.target.BookshelfTargetKeys
import com.xiyunmn.qdhelper.host.api.target.BookCoverTargetKeys
import com.xiyunmn.qdhelper.host.api.target.CircleTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ContentToolsTargetKeys
import com.xiyunmn.qdhelper.host.api.target.DiagnosticsTargetKeys
import com.xiyunmn.qdhelper.host.api.target.DialogTargetKeys
import com.xiyunmn.qdhelper.host.api.target.MaintenanceTargetKeys
import com.xiyunmn.qdhelper.host.api.target.NavigationTargetKeys
import com.xiyunmn.qdhelper.host.api.target.NetworkIdentityTargetKeys
import com.xiyunmn.qdhelper.host.api.target.PageCustomizationTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ReaderFontTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ReaderTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ReadTimeTargetKeys
import com.xiyunmn.qdhelper.host.api.target.ShieldTargetKeys
import com.xiyunmn.qdhelper.host.api.target.StartupTargetKeys
import com.xiyunmn.qdhelper.host.api.target.UiThemeTargetKeys
import com.xiyunmn.qdhelper.host.api.target.TargetId
import com.xiyunmn.qdhelper.host.api.target.TargetKey
import com.xiyunmn.qdhelper.host.api.target.WelfareTargetKeys
import com.xiyunmn.qdhelper.host.resolver.dexkit.query.DexKitMethodQuery
import java.security.MessageDigest

enum class TargetRisk { LOW, MEDIUM, HIGH }
enum class ResolutionTiming { EARLY_STARTUP, RUNTIME_INSTALLABLE, ON_ACTION, PROBE_ONLY }
enum class PrewarmPolicy { ALWAYS, WHEN_OWNER_ACTIVE, NEVER }

data class SemanticSpec(
    val queryId: String,
    val candidateVerifierId: String,
)

data class HostTargetSpec(
    val key: TargetKey.MethodTarget,
    val revision: Int,
    val owner: OwnerId,
    val purpose: String,
    val risk: TargetRisk,
    val process: String,
    val timing: ResolutionTiming,
    val prewarm: PrewarmPolicy,
    val cardinality: Cardinality,
    val semantic: SemanticSpec,
    internal val query: DexKitMethodQuery,
)

object HostTargetCatalog {
    // Schema 2 keeps host versions as cache identity only; no version is rejected at runtime.
    const val SCHEMA_VERSION = 2

    fun forProcess(processName: String): List<HostTargetSpec> {
        val specs = TargetQueryCatalog.forProcess(processName).map { registration ->
            val id = TargetId(registration.id)
            val revision = revisionOf(id.value)
            val key = requireNotNull(namedMethodKeyById[id.value]) {
                "Target query ${id.value} has no named TargetKey"
            }
            val cardinality = key.cardinality
            val timing = timingOf(id.value)
            val risk = riskOf(id.value)
            HostTargetSpec(
                key = key,
                revision = revision,
                owner = ownerOf(id.value),
                purpose = registration.purpose,
                risk = risk,
                process = if (processName.contains(":mini")) "mini" else "main",
                timing = timing,
                prewarm = prewarmOf(id.value, timing),
                cardinality = cardinality,
                semantic = SemanticSpec(
                    queryId = "dexkit.${id.value}.r$revision",
                    candidateVerifierId = "candidate.${id.value}.r$revision",
                ),
                query = registration,
            )
        }
        require(specs.map { it.key.id }.toSet().size == specs.size) {
            "Target catalog contains duplicate IDs for process $processName"
        }
        return specs
    }

    fun prewarmForProcess(processName: String, activeOwners: Set<OwnerId>): List<HostTargetSpec> =
        forProcess(processName).filter { spec ->
            spec.prewarm == PrewarmPolicy.ALWAYS ||
                spec.prewarm == PrewarmPolicy.WHEN_OWNER_ACTIVE && (
                    spec.owner in activeOwners ||
                        OwnerId("read-time") in activeOwners && spec.key in AccountTargetKeys.userSession
                    )
        }

    fun sha256(processName: String): String {
        val canonical = forProcess(processName).joinToString("\n") { spec ->
            listOf(
                spec.key.id.value,
                spec.revision,
                spec.owner.value,
                spec.purpose,
                spec.risk,
                spec.process,
                spec.timing,
                spec.prewarm,
                spec.cardinality,
                spec.semantic,
            ).joinToString("\u0000")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun ownerOf(id: String): OwnerId {
        val owner = when {
            id.startsWith("shield.") -> "book-shield"
            id.startsWith("welfare.") || id.startsWith("gdt.") || id.startsWith("tangram.") ||
                id.startsWith("h5.") || id.startsWith("qqmini.") -> "welfare"
            id.startsWith("network.identity.") || id.startsWith("identity.") -> "network-identity"
            id.startsWith("startup.") -> "startup"
            id.startsWith("bookshelf.") || id.startsWith("ui.bookshelf.") ||
                id.startsWith("ui.daily_reading.") -> "bookshelf"
            id.startsWith("ui.bottom_tab.") -> "navigation"
            id.startsWith("ui.page_custom.") || id.startsWith("ui.mine_page.") -> "page-customization"
            id.startsWith("reader.font.") || id.startsWith("reader_font.") -> "reader-font"
            id.startsWith("reader.") -> "reader"
            id.startsWith("circle.") || id.startsWith("content_tools.") -> "content-tools"
            id.startsWith("dialog.") -> "dialogs"
            id.startsWith("account.") -> "account"
            id.startsWith("read_time.") -> "read-time"
            id.startsWith("maintenance.") -> "maintenance"
            id.startsWith("debug.") -> "diagnostics"
            else -> "shared"
        }
        return OwnerId(owner)
    }

    private fun riskOf(id: String): TargetRisk = when {
        id.startsWith("account.") || id.startsWith("read_time.") ||
            id.contains("welfare") || id.contains("identity") ||
            id.contains("qimei") || id.contains("cookie") || id.contains("oaid") ||
            id.contains("membership") || id.contains("decrypt") ->
            TargetRisk.HIGH
        id.contains("splash") || id.contains("auto_sign") -> TargetRisk.MEDIUM
        else -> TargetRisk.LOW
    }

    private fun revisionOf(id: String): Int = when (id) {
        in REVISION_10_TARGET_IDS -> 10
        in REVISION_9_TARGET_IDS -> 9
        in REVISION_8_TARGET_IDS -> 8
        in REVISION_7_TARGET_IDS -> 7
        in REVISION_6_TARGET_IDS -> 6
        in REVISION_5_TARGET_IDS -> 5
        in REVISION_4_TARGET_IDS -> 4
        in REVISION_3_TARGET_IDS -> 3
        in REVISION_2_TARGET_IDS -> 2
        else -> 1
    }

    // Invalidate stale misses after infrastructure renaming and welfare stream changes.
    private val REVISION_10_TARGET_IDS = setOf(
        "welfare.h5.reward_bridge.f1_config_mid",
        "welfare.h5.reward_bridge.f1_config_last",
        "welfare.h5.reward_bridge.r1_game",
        "welfare.extra.finish_watch_business",
        "welfare.recovery.native_bridge",
        "welfare.recovery.grouped_bridge",
        "welfare.recovery.grouped_event",
        "welfare.recovery.grouped_error",
        "welfare.recovery.h5_callback",
        "debug.device_identity_probe.config_loader_request",
        "debug.device_identity_probe.config_loader_apply",
        "debug.device_identity_probe.app_config_cache_loaded",
        "debug.device_identity_probe.app_config_network_updated",
    )

    // Push the existing getter owner contract into native matching before reflection.
    private val REVISION_9_TARGET_IDS = setOf(
        "network.identity.common_device.qimei16",
        "network.identity.common_device.qimei36",
        "debug.host_oaid.getter",
        "network.identity.common_device_cached.beacon16",
        "network.identity.common_device_cached.beacon36",
        "network.identity.common_device_cached.ostar16",
        "network.identity.common_device_cached.ostar36",
        "network.identity.fake_identity.fake_local16",
        "network.identity.fake_identity.fake_local36",
        "network.identity.fake_identity.fake_ostar16",
        "network.identity.fake_identity.fake_ostar36",
        "network.identity.fake_identity.fake16",
        "network.identity.fake_identity.fake36",
    )

    // Rescan both misses and formerly ambiguous candidates after the 7.9.480 contract changes.
    private val REVISION_8_TARGET_IDS = setOf(
        "reader.clean.chapter_end.append",
        "dialog.notification.request",
        "shield.list_setter.ranking_right",
        "debug.device_identity_probe.qdinfo_candidates",
        "welfare.extra.finish_watch_business",
        "welfare.recovery.grouped_event",
        "welfare.recovery.grouped_error",
    )

    private val REVISION_5_TARGET_IDS = emptySet<String>()

    // Revision 7 adapts four contracts whose 7.9.476 owner, native body, or parameter shape
    // changed. Per-target revisions invalidate only the affected negative caches.
    private val REVISION_7_TARGET_IDS = setOf(
        "content_tools.profile_privacy.preload_callback_success",
        "welfare.extra.finish_watch_business",
    )

    // Revision 6 invalidates the negative/ambiguous cache written by the pre-7.9.464
    // query shapes. These targets now use stable field, invoke, string, or generated-lambda
    // suffix anchors and must be rescanned once after an upgrade.
    private val REVISION_6_TARGET_IDS = setOf(
        "account.auto_invest.reader_book_id",
        "account.auto_sign_in.manager.already_checked_in",
        "account.user_manager.is_logged_in",
        "account.user_manager.user_id",
        "ui.bookshelf.operation.notify.lambda_0",
        "ui.bookshelf.operation.notify.lambda_13_12",
        "ui.bottom_tab.pager.rebuild",
    )

    private val REVISION_4_TARGET_IDS = emptySet<String>()

    private val REVISION_2_TARGET_IDS = setOf(
        "dialog.notification.request",
        "dialog.notification.scene_guide",
        "ui.bookshelf.new_list_book.bind",
        "network.identity.common_device.qimei16",
        "network.identity.common_device.qimei36",
        "network.identity.common_device_cached.beacon16",
        "network.identity.common_device_cached.beacon36",
        "network.identity.common_device_cached.ostar16",
        "network.identity.common_device_cached.ostar36",
        "debug.host_oaid.getter",
        "welfare.flutter.method_call",
    )

    private val REVISION_3_TARGET_IDS = setOf(
        "network.identity.fake_identity.fake_local16",
        "network.identity.fake_identity.fake_local36",
        "network.identity.fake_identity.fake_ostar16",
        "network.identity.fake_identity.fake_ostar36",
        "network.identity.fake_identity.fake16",
        "network.identity.fake_identity.fake36",
    )

    private fun timingOf(id: String): ResolutionTiming = when {
        id.startsWith("content_tools.cover.") -> ResolutionTiming.ON_ACTION
        id.startsWith("startup.splash.") -> ResolutionTiming.EARLY_STARTUP
        id.startsWith("maintenance.") -> ResolutionTiming.ON_ACTION
        id == DiagnosticsTargetKeys.hostCookieGetter.id.value ||
            id == DiagnosticsTargetKeys.hostOaidGetter.id.value -> ResolutionTiming.ON_ACTION
        id.startsWith("welfare.") -> ResolutionTiming.RUNTIME_INSTALLABLE
        id.contains("probe") -> ResolutionTiming.PROBE_ONLY
        id.contains("welfare") || id.contains("reward") -> ResolutionTiming.ON_ACTION
        else -> ResolutionTiming.RUNTIME_INSTALLABLE
    }

    private fun prewarmOf(id: String, timing: ResolutionTiming): PrewarmPolicy = when {
        id.startsWith("network.identity.") || id.startsWith("identity.account.") ||
            id == DiagnosticsTargetKeys.hostCookieGetter.id.value ||
            id == DiagnosticsTargetKeys.hostOaidGetter.id.value -> PrewarmPolicy.NEVER
        timing == ResolutionTiming.ON_ACTION || timing == ResolutionTiming.PROBE_ONLY -> PrewarmPolicy.NEVER
        else -> PrewarmPolicy.WHEN_OWNER_ACTIVE
    }

    private val namedMethodKeys = listOf(
        ReaderTargetKeys.chapterEndAppend,
        ReaderTargetKeys.chapterCommentCopyBindMethods,
        ContentToolsTargetKeys.profileHomeResponseConvert,
        ContentToolsTargetKeys.profileChapterReviewsApi,
        ContentToolsTargetKeys.profileBookReviewsApi,
        ContentToolsTargetKeys.profilePreloadCallbackSuccess,
        ContentToolsTargetKeys.profileColumnsApi,
        ContentToolsTargetKeys.profileBookListApi,
        ContentToolsTargetKeys.profileUserFollowApi,
        ContentToolsTargetKeys.profileUserAudioListApi,
        ContentToolsTargetKeys.profileCapsuleApi,
        ContentToolsTargetKeys.profileCvApi,
        MaintenanceTargetKeys.userLookupSearchRequest,
    ) + CircleTargetKeys.all + BookshelfTargetKeys.all + NavigationTargetKeys.all +
        PageCustomizationTargetKeys.all + ShieldTargetKeys.all +
        DialogTargetKeys.all + BookCoverTargetKeys.all +
        ReaderFontTargetKeys.all + StartupTargetKeys.all +
        AccountTargetKeys.all + NetworkIdentityTargetKeys.all +
        WelfareTargetKeys.all + DiagnosticsTargetKeys.all +
        ReadTimeTargetKeys.all + UiThemeTargetKeys.all

    private val namedMethodKeyById = namedMethodKeys
        .also { keys ->
            require(keys.map { it.id }.toSet().size == keys.size) {
                "Named host target keys contain duplicate IDs"
            }
        }
        .associateBy { it.id.value }

    private val revisionSets = listOf(
        REVISION_2_TARGET_IDS,
        REVISION_3_TARGET_IDS,
        REVISION_4_TARGET_IDS,
        REVISION_5_TARGET_IDS,
        REVISION_6_TARGET_IDS,
        REVISION_7_TARGET_IDS,
    )

    init {
        val assigned = revisionSets.flatten()
        require(assigned.size == assigned.toSet().size) {
            "Host target revision sets must be mutually exclusive"
        }
    }
}
