package com.xiyunmn.qdhelper.settings.api

import com.xiyunmn.qdhelper.config.SettingKey
import com.xiyunmn.qdhelper.config.SettingScope
import com.xiyunmn.qdhelper.foundation.identity.OwnerId
import org.junit.Test
import org.junit.Assert.assertThrows

class SettingsContributionRegistryTest {
    private val enabled = SettingKey(
        rawKey = "test.enabled",
        valueType = Boolean::class,
        defaultValue = false,
        scope = SettingScope.USER,
    )

    @Test
    fun acceptsTypedKeysAndContributions() {
        val contribution = contribution(
            SettingsRow.Toggle("test.toggle", "测试", "测试开关", enabled.rawKey),
            suffix = "valid",
        )
        val registry = DefaultSettingRegistry(listOf(enabled), listOf(contribution))

        check(registry.keys.toList() == listOf(enabled))
        check(registry.contributions.toList() == listOf(contribution))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownSettingKey() {
        DefaultSettingRegistry(
            keys = listOf(enabled),
            contributions = listOf(
                contribution(
                    SettingsRow.Toggle("test.unknown", "未知", "未知", "missing.key"),
                    suffix = "unknown",
                ),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateRowIdsAcrossContributions() {
        val row = SettingsRow.Toggle("test.same-row", "测试", "测试开关", enabled.rawKey)
        DefaultSettingRegistry(
            keys = listOf(enabled),
            contributions = listOf(
                contribution(row, suffix = "one"),
                contribution(row.copy(id = row.id), suffix = "two"),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun externalLinksRejectNonHttpsUrls() {
        SettingsRow.ExternalLink(
            id = "test.external-link",
            title = "外链",
            summary = "不安全链接",
            url = "http://example.com",
        )
    }

    @Test
    fun actionsRequireExactlyOneExecutionPath() {
        val command = SettingsAction { }
        val tool = object : SettingsToolController {
            override fun read(context: android.content.Context) = SettingsToolState()
            override fun execute(
                activity: android.app.Activity,
                command: String,
                values: Map<String, String>,
                result: (SettingsToolResult) -> Unit,
            ): AutoCloseable? = null
        }
        val actionRow = SettingsRow.Action("test.command", "Command", "", "Run", action = command)
        val toolRow = SettingsRow.Action("test.tool", "Tool", "", "Open", tool = tool)
        check(actionRow.action === command && actionRow.tool == null)
        check(toolRow.tool === tool && toolRow.action == null)
        assertThrows(IllegalArgumentException::class.java) {
            actionRow.copy(tool = tool)
        }
        assertThrows(IllegalArgumentException::class.java) {
            toolRow.copy(tool = null)
        }
    }

    private fun contribution(row: SettingsRow, suffix: String): SettingsContribution = object : SettingsContribution {
        override val id = SettingsContributionId("test.$suffix")
        override val owner = OwnerId("test")
        override val sections = listOf(
            SettingsSection(
                id = "test.$suffix-section",
                page = SettingsPageIds.CORE,
                title = "测试",
                order = 100,
                rows = listOf(row),
            ),
        )
    }
}
