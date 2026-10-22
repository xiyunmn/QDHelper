package com.xiyunmn.qdhelper.settings.ui.compose.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.xiyunmn.qdhelper.settings.api.SettingsToolField
import com.xiyunmn.qdhelper.settings.ui.compose.component.ConsoleNumberRange
import com.xiyunmn.qdhelper.settings.ui.compose.component.ConsoleTextField

/** Pair only explicitly linked, adjacent numeric fields; unrelated inputs keep their own rows. */
internal fun toolFieldRows(fields: List<SettingsToolField>): List<List<SettingsToolField>> = buildList {
    var index = 0
    while (index < fields.size) {
        val first = fields[index]
        val last = fields.getOrNull(index + 1)
        if (first.numeric && last?.numeric == true && first.rangeEnd == last.id) {
            add(listOf(first, last)); index += 2
        } else { add(listOf(first)); index++ }
    }
}

@Composable
internal fun SettingsToolFieldRow(row: List<SettingsToolField>, values: MutableMap<String, String>, enabled: Boolean) {
    val first = row.first()
    val last = row.getOrNull(1)
    if (last != null) ConsoleNumberRange(values[first.id].orEmpty(), values[last.id].orEmpty(), first.label, last.label,
        { values[first.id] = it }, { values[last.id] = it }, enabled = enabled)
    else ConsoleTextField(values[first.id].orEmpty(), { values[first.id] = it }, Modifier.fillMaxWidth(),
        label = { Text(first.label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (first.numeric) KeyboardType.Number else KeyboardType.Text))
}
