package com.xiyunmn.qdhelper.config.schema

import com.xiyunmn.qdhelper.config.SettingCollections
import com.xiyunmn.qdhelper.config.GlassBarDimensions
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingKey
import com.xiyunmn.qdhelper.config.SettingScope

enum class SettingType { BOOLEAN, STRING, LONG }

data class SettingDefinition(
    val rawKey: String,
    val type: SettingType,
    val serializedDefault: String,
    val scope: SettingScope,
)

object SettingSchemaCatalog {
    val definitions: List<SettingDefinition> by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("config/setting-schema.tsv")) {
            "Missing config/setting-schema.tsv"
        }
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1).filter(String::isNotBlank).map { row ->
                val columns = row.split('\t', limit = 4)
                require(columns.size == 4) { "Invalid setting schema row" }
                SettingDefinition(
                    rawKey = columns[0],
                    type = SettingType.valueOf(columns[1]),
                    serializedDefault = columns[2],
                    scope = SettingScope.valueOf(columns[3]),
                )
            }.toList()
        }.also { definitions ->
            require(definitions.map { it.rawKey }.toSet().size == definitions.size) {
                "Setting schema contains duplicate raw keys"
            }
        }
    }

    val settingKeys: List<SettingKey<*>> by lazy { definitions.map(::toSettingKey) }

    private val byRawKey by lazy { settingKeys.associateBy(SettingKey<*>::rawKey) }

    fun requireKey(rawKey: String): SettingKey<*> =
        requireNotNull(byRawKey[rawKey]) { "Unknown setting key: $rawKey" }

    private fun toSettingKey(definition: SettingDefinition): SettingKey<*> = when (definition.type) {
        SettingType.BOOLEAN -> SettingKey(
            rawKey = definition.rawKey,
            valueType = Boolean::class,
            defaultValue = definition.serializedDefault.toBooleanStrict(),
            scope = definition.scope,
        )
        SettingType.LONG -> SettingKey(
            rawKey = definition.rawKey,
            valueType = Long::class,
            defaultValue = definition.serializedDefault.toLong(),
            scope = definition.scope,
        )
        SettingType.STRING -> SettingKey(
            rawKey = definition.rawKey,
            valueType = String::class,
            defaultValue = definition.serializedDefault,
            scope = definition.scope,
            validate = stringValidator(definition.rawKey),
        )
    }

    private fun stringValidator(rawKey: String): (String) -> Boolean = when (rawKey) {
        SettingIds.KEY_GLASS_MAIN_HEIGHT, SettingIds.KEY_GLASS_DETAIL_HEIGHT, SettingIds.KEY_GLASS_DIRECTORY_HEIGHT ->
            integerRange(GlassBarDimensions.MIN_HEIGHT..GlassBarDimensions.MAX_HEIGHT)
        SettingIds.KEY_GLASS_MAIN_WIDTH, SettingIds.KEY_GLASS_DETAIL_WIDTH, SettingIds.KEY_GLASS_DIRECTORY_WIDTH ->
            integerRange(GlassBarDimensions.AUTO_WIDTH_TICK..GlassBarDimensions.MAX_WIDTH)
        SettingIds.KEY_GLASS_MAIN_BOTTOM_GAP, SettingIds.KEY_GLASS_DETAIL_BOTTOM_GAP, SettingIds.KEY_GLASS_DIRECTORY_BOTTOM_GAP ->
            integerRange(-1..GlassBarDimensions.MAX_GAP)
        SettingIds.KEY_GLASS_STRENGTH -> { raw -> raw in setOf("gentle", "standard", "strong") }
        SettingIds.KEY_READ_TIME_MULTIPLIER_VALUE -> { raw ->
            raw.trim().toDoubleOrNull()?.let { it.isFinite() && it in 0.1..2.5 } == true
        }
        SettingIds.KEY_CUSTOM_DNS_NET_STACK -> { raw -> raw.trim() in setOf("1", "2", "3") }
        SettingIds.KEY_FREE_COPY_MODE -> { raw ->
            raw == SettingIds.FREE_COPY_MODE_LONG_PRESS || raw == SettingIds.FREE_COPY_MODE_MENU_BUTTON
        }
        SettingIds.KEY_PARAGRAPH_ACTION_ORDER -> exactOrderValidator(SettingCollections.PARAGRAPH_ACTION_ITEMS.map { it.key })
        SettingIds.KEY_BOTTOM_TAB_ORDER -> exactOrderValidator(SettingCollections.BOTTOM_TAB_ITEMS.map { it.key })
        SettingIds.KEY_HOME_TOP_ACTION_ORDER -> exactOrderValidator(SettingCollections.HOME_TOP_ACTION_ITEMS.map { it.key })
        SettingIds.KEY_BOTTOM_TAB_HIDDEN -> subsetValidator(
            SettingCollections.BOTTOM_TAB_ITEMS.map { it.key },
            allowAll = false,
        )
        SettingIds.KEY_HOME_TOP_ACTION_HIDDEN -> subsetValidator(
            SettingCollections.HOME_TOP_ACTION_ITEMS.map { it.key },
            allowAll = false,
        )
        SettingIds.KEY_BOOK_SHIELD_LOCATIONS -> subsetValidator(
            SettingCollections.BOOK_SHIELD_LOCATION_ITEMS.map { it.key },
            allowAll = true,
        )
        else -> { _ -> true }
    }

    private fun integerRange(range: IntRange): (String) -> Boolean = { raw ->
        raw.toIntOrNull()?.let { it in range } == true
    }

    private fun exactOrderValidator(validOrder: List<String>): (String) -> Boolean = { raw ->
        val values = csvValues(raw)
        values.size == validOrder.size && values.toSet() == validOrder.toSet()
    }

    private fun subsetValidator(validOrder: List<String>, allowAll: Boolean): (String) -> Boolean = { raw ->
        val values = csvValues(raw)
        values.size == values.toSet().size &&
            values.all(validOrder::contains) &&
            (allowAll || values.size < validOrder.size)
    }

    private fun csvValues(raw: String): List<String> = raw.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
}
