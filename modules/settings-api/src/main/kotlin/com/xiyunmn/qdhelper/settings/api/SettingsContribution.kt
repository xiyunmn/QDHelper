package com.xiyunmn.qdhelper.settings.api

import android.app.Activity
import android.content.Context
import android.view.View
import com.xiyunmn.qdhelper.config.SettingKey
import com.xiyunmn.qdhelper.foundation.identity.OwnerId

private val SETTINGS_ID_PATTERN = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")

@JvmInline
value class SettingsContributionId(val value: String) {
    init {
        require(SETTINGS_ID_PATTERN.matches(value)) { "Invalid settings contribution ID: $value" }
    }
}

@JvmInline
value class SettingsPageId(val value: String) {
    init {
        require(SETTINGS_ID_PATTERN.matches(value)) { "Invalid settings page ID: $value" }
    }
}

data class SettingsSearchAlias(
    val title: String,
    val keywords: Set<String> = emptySet(),
    val focusTitle: String = title,
)

data class SettingsChoice(
    val key: String,
    val title: String,
    val summary: String? = null,
)

fun interface SettingsAction {
    fun run(scope: SettingsActionScope)
}

fun interface SettingsBooleanChangedAction {
    fun run(scope: SettingsActionScope, value: Boolean)
}

fun interface SettingsTextChangedAction {
    fun run(scope: SettingsActionScope, value: String)
}

fun interface SettingsDoubleChangedAction {
    fun run(scope: SettingsActionScope, value: Double)
}

fun interface SettingsSummaryProvider {
    fun summary(context: Context): String
}

fun interface SettingsTextProvider {
    fun value(context: Context): String
}

fun interface SettingsResetAction {
    fun reset(context: Context): Boolean
}

data class SettingsDocumentState(
    val text: String,
    val sourceLabel: String,
    val errorMessage: String? = null,
    val ruleCount: Int = 0,
    val dirty: Boolean = false,
    val ruleBreakdown: Map<String, Int> = emptyMap(),
    /** Optional copyable path, separate from the localized display label. */
    val sourcePath: String? = null,
)

data class SettingsDocumentActionResult(
    val success: Boolean,
    val message: String,
)

interface SettingsDocumentController {
    fun state(context: Context): SettingsDocumentState
    fun updateDraft(text: String)
    fun save(activity: Activity, onResult: (SettingsDocumentActionResult) -> Unit)
    fun openExternalEditor(activity: Activity, onResult: (SettingsDocumentActionResult) -> Unit)
}

data class SettingsManagedListItem(
    val key: String,
    val title: String,
    val summary: String? = null,
    val active: Boolean = false,
    val badge: String? = null,
)

data class SettingsManagedListActionResult(
    val success: Boolean,
    val message: String = "",
)

data class SettingsDynamicChoiceItem(
    val key: String,
    val title: String,
    val summary: String? = null,
)

interface SettingsDynamicChoiceController {
    fun items(context: Context): List<SettingsDynamicChoiceItem>
    fun selectedKey(context: Context): String?
    fun summary(context: Context): String
    fun select(context: Context, key: String?): SettingsManagedListActionResult
}

interface SettingsManagedListController {
    fun items(context: Context): List<SettingsManagedListItem>
    fun reorder(context: Context, orderedKeys: List<String>): SettingsManagedListActionResult
    fun delete(context: Context, key: String): SettingsManagedListActionResult
    fun addChangedListener(listener: () -> Unit): AutoCloseable
}

interface SettingsActionScope {
    val activity: Activity
    fun refresh(page: SettingsPageId, focusTitle: String? = null)
    fun closeExclusiveSwitch(rawKey: String)
    fun applyUiSettings()
    fun restartHost()
}

data class PageCustomizationItem(
    val key: String,
    val label: String,
)

data class PageCustomizationScanResult(
    val matchedPage: Boolean,
    val count: Int,
)

interface PageCustomizationSettingsController {
    val id: String
    val label: String
    val enabledKey: String
    val entriesKey: String
    val hiddenKey: String
    val fixedItems: Boolean

    fun storedItems(context: Context): List<PageCustomizationItem>
    fun refreshKnownItems(context: Context): PageCustomizationScanResult
    fun addScanResultListener(listener: (PageCustomizationScanResult) -> Unit): AutoCloseable
    fun requestPendingScan(context: Context): Boolean
    fun openHostPageForPendingScan(activity: Activity): AutoCloseable?
    fun cancelPendingScan(context: Context)
    fun hasPendingScan(context: Context): Boolean
    fun consumePendingScanIfMatches(root: View): PageCustomizationScanResult?
    fun consumePendingScanIfMatchesAsync(
        root: View,
        onResult: (PageCustomizationScanResult?) -> Unit,
    ): Boolean
    fun clear(context: Context): Boolean
}

sealed interface SettingsRow {
    val id: String
    val title: String
    val summary: String?
    val keywords: Set<String>
    val searchAliases: List<SettingsSearchAlias>
    val settingKeys: Set<String>

    data class Toggle(
        override val id: String,
        override val title: String,
        override val summary: String,
        val key: String,
        val defaultValue: Boolean = false,
        val onChanged: SettingsBooleanChangedAction? = null,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        override val settingKeys: Set<String> = setOf(key)
    }

    data class TextInput(
        override val id: String,
        override val title: String,
        override val summary: String,
        val key: String,
        val hint: String = "",
        val singleLine: Boolean = true,
        val onChanged: SettingsTextChangedAction? = null,
        val validator: ((String) -> Boolean)? = null,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        override val settingKeys: Set<String> = setOf(key)
    }

    data class SessionTextInput(
        override val id: String,
        override val title: String,
        override val summary: String,
        val valueProvider: SettingsTextProvider,
        val hint: String = "",
        val singleLine: Boolean = false,
        val onChanged: SettingsTextChangedAction,
        val validator: ((String) -> Boolean)? = null,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        override val settingKeys: Set<String> = emptySet()
    }

    data class Slider(
        override val id: String,
        override val title: String,
        override val summary: String,
        val key: String,
        val minValue: Double,
        val maxValue: Double,
        val step: Double,
        val defaultValue: Double,
        val valueFormatter: (Double) -> String = Double::toString,
        val onChanged: SettingsDoubleChangedAction? = null,
        /** Explicit user commit, including a drag returning to the stored value; never normalization. */
        val onCommitted: SettingsDoubleChangedAction? = null,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        init {
            require(minValue < maxValue) { "Slider minValue must be lower than maxValue" }
            require(step > 0.0) { "Slider step must be positive" }
            require(defaultValue in minValue..maxValue) { "Slider defaultValue must be in range" }
        }

        override val settingKeys: Set<String> = setOf(key)
    }

    data class Action(
        override val id: String,
        override val title: String,
        override val summary: String,
        val actionText: String,
        /** Immediate command; mutually exclusive with the dedicated tool controller. */
        val action: SettingsAction? = null,
        val summaryProvider: SettingsSummaryProvider? = null,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
        override val settingKeys: Set<String> = emptySet(),
        val tool: SettingsToolController? = null,
    ) : SettingsRow {
        init {
            require((action != null) != (tool != null)) {
                "Action $id must declare exactly one of action or tool"
            }
        }
    }

    data class PageLink(
        override val id: String,
        override val title: String,
        override val summary: String,
        val targetPage: SettingsPageId,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        override val settingKeys: Set<String> = emptySet()
    }

    data class ExternalLink(
        override val id: String,
        override val title: String,
        override val summary: String,
        val url: String,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        init {
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            require(
                uri?.scheme.equals("https", ignoreCase = true) &&
                    !uri?.host.isNullOrBlank() &&
                    uri?.userInfo == null,
            ) { "External settings links must use a credential-free HTTPS URL: $url" }
        }

        override val settingKeys: Set<String> = emptySet()
    }

    data class DocumentEditor(
        override val id: String,
        override val title: String,
        override val summary: String,
        val controller: SettingsDocumentController,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        override val settingKeys: Set<String> = emptySet()
    }

    data class ManagedList(
        override val id: String,
        override val title: String,
        override val summary: String,
        val emptyText: String,
        val controller: SettingsManagedListController,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
        override val settingKeys: Set<String> = emptySet(),
    ) : SettingsRow

    data class DynamicChoice(
        override val id: String,
        override val title: String,
        override val summary: String,
        val defaultChoiceTitle: String,
        val emptyText: String,
        val controller: SettingsDynamicChoiceController,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
        override val settingKeys: Set<String> = emptySet(),
    ) : SettingsRow

    data class SingleChoice(
        override val id: String,
        override val title: String,
        override val summary: String,
        val key: String,
        val defaultKey: String,
        val choices: List<SettingsChoice>,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        override val settingKeys: Set<String> = setOf(key)
    }

    data class OrderedChoice(
        override val id: String,
        override val title: String,
        override val summary: String,
        val enabledKey: String,
        val orderKey: String,
        val hiddenKey: String,
        val choices: List<SettingsChoice>,
        val onChanged: SettingsAction? = null,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        override val settingKeys: Set<String> = setOf(enabledKey, orderKey, hiddenKey)
    }

    data class ParagraphOrder(
        override val id: String,
        override val title: String,
        override val summary: String,
        val orderKey: String,
        val copyEnabledKey: String,
        val copyChoiceKey: String,
        val choices: List<SettingsChoice>,
        val normalize: (String?) -> List<String>,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        override val settingKeys: Set<String> = setOf(orderKey, copyEnabledKey)
    }

    data class MultiChoice(
        override val id: String,
        override val title: String,
        override val summary: String,
        val key: String,
        val choices: List<SettingsChoice>,
        val defaultSelected: Set<String>,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        override val settingKeys: Set<String> = setOf(key)
    }

    data class PageCustomization(
        override val id: String,
        override val title: String,
        override val summary: String,
        val controller: PageCustomizationSettingsController,
        override val keywords: Set<String> = emptySet(),
        override val searchAliases: List<SettingsSearchAlias> = emptyList(),
    ) : SettingsRow {
        override val settingKeys: Set<String> = setOf(
            controller.enabledKey,
            controller.entriesKey,
            controller.hiddenKey,
        )
    }

    data class Hint(
        override val id: String,
        val text: String,
    ) : SettingsRow {
        override val title: String = ""
        override val summary: String? = text
        override val keywords: Set<String> = emptySet()
        override val searchAliases: List<SettingsSearchAlias> = emptyList()
        override val settingKeys: Set<String> = emptySet()
    }
}

data class SettingsSection(
    val id: String,
    val page: SettingsPageId,
    val title: String,
    val order: Int,
    val rows: List<SettingsRow>,
    val groupId: String = id,
    /** Additional keys reset with this section even when no row owns them directly. */
    val resetKeys: Set<String> = emptySet(),
    val resetActions: Map<String, SettingsResetAction> = emptyMap(),
)

interface SettingsContribution {
    val id: SettingsContributionId
    val owner: OwnerId
    val sections: List<SettingsSection>
}

interface SettingRegistry {
    val keys: Collection<SettingKey<*>>
    val contributions: Collection<SettingsContribution>
}

class DefaultSettingRegistry(
    override val keys: Collection<SettingKey<*>>,
    override val contributions: Collection<SettingsContribution>,
) : SettingRegistry {
    private val keysByRawKey = keys.associateBy { it.rawKey }

    init {
        require(keysByRawKey.size == keys.size) { "Duplicate SettingKey raw keys" }
        require(contributions.map { it.id }.toSet().size == contributions.size) {
            "Duplicate SettingsContribution IDs"
        }
        val sections = contributions.flatMap { it.sections }
        require(sections.all { SETTINGS_ID_PATTERN.matches(it.id) }) { "Invalid settings section ID" }
        require(sections.map { it.id }.toSet().size == sections.size) { "Duplicate settings section IDs" }
        val rows = sections.flatMap { it.rows }
        require(rows.all { SETTINGS_ID_PATTERN.matches(it.id) }) { "Invalid settings row ID" }
        require(rows.map { it.id }.toSet().size == rows.size) { "Duplicate settings row IDs" }
        rows.forEach { row ->
            val unknown = row.settingKeys - keysByRawKey.keys
            require(unknown.isEmpty()) { "Settings row ${row.id} references unknown keys: $unknown" }
        }
        sections.forEach { section ->
            val unknown = section.resetKeys - keysByRawKey.keys
            require(unknown.isEmpty()) { "Settings section ${section.id} references unknown reset keys: $unknown" }
        }
        val resetActionIds = sections.flatMap { it.resetActions.keys }
        require(resetActionIds.toSet().size == resetActionIds.size) { "Duplicate settings reset action IDs" }
    }
}
