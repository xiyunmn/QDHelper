package com.xiyunmn.qdhelper.settings.ui.compose.row

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/** Preserve the Framework renderer's quantization and stored decimal scale. */
internal fun normalizedSliderStorage(row: SettingsRow.Slider, raw: String): String {
    val steps = ((row.maxValue - row.minValue) / row.step).roundToInt().coerceAtLeast(1)
    val value = (raw.toDoubleOrNull()?.takeIf(Double::isFinite) ?: row.defaultValue).coerceIn(row.minValue, row.maxValue)
    val index = ((value - row.minValue) / row.step).roundToInt().coerceIn(0, steps)
    val scale = BigDecimal.valueOf(row.step).stripTrailingZeros().scale().coerceAtLeast(0)
    return BigDecimal.valueOf(row.minValue + index * row.step).setScale(scale, RoundingMode.HALF_UP).toPlainString()
}

/** Local preview only. A completed interaction is the sole user-edit commit boundary. */
internal class SliderEditState(
    private val row: SettingsRow.Slider,
    private var source: String,
    private val resetRevision: Int,
) {
    var value by mutableStateOf(normalizedSliderStorage(row, source))
        private set
    private var changed = false

    fun preview(next: Float) {
        value = normalizedSliderStorage(row, next.toString())
        changed = true
    }

    fun finish(
        current: String,
        currentResetRevision: Int,
        enabled: Boolean,
        save: (String) -> Boolean,
        onSaved: (Double) -> Unit,
        onCommitted: (Double) -> Unit = {},
    ) {
        val next = value
        val pending = changed
        changed = false
        value = normalizedSliderStorage(row, current)
        // An old gesture cannot undo a reset, external update or dependency change.
        if (!pending || !enabled || current != source || currentResetRevision != resetRevision) return
        if (next == value) {
            onCommitted(next.toDouble())
            return
        }
        if (save(next)) {
            source = next
            value = next
            onSaved(next.toDouble())
            onCommitted(next.toDouble())
        }
    }
}
