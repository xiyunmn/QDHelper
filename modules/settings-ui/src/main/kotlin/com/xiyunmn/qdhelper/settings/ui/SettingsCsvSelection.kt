package com.xiyunmn.qdhelper.settings.ui

internal data class SettingsCsvSelection(
    val keys: LinkedHashSet<String>,
    val serialized: String,
)

internal fun readSettingsCsvSelection(
    raw: String?,
    validOrder: List<String>,
    defaultKeys: Set<String> = emptySet(),
): LinkedHashSet<String> {
    val source = if (raw == null) {
        defaultKeys
    } else {
        raw.split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
    }
    return validOrder.filterTo(linkedSetOf()) { it in source }
}

internal fun updateSettingsCsvSelection(
    raw: String?,
    validOrder: List<String>,
    defaultKeys: Set<String> = emptySet(),
    key: String,
    selected: Boolean,
): SettingsCsvSelection {
    val next = readSettingsCsvSelection(raw, validOrder, defaultKeys)
    if (selected) {
        if (key in validOrder) next += key
    } else {
        next -= key
    }
    val ordered = validOrder.filterTo(linkedSetOf()) { it in next }
    return SettingsCsvSelection(ordered, ordered.joinToString(","))
}
