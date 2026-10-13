package com.xiyunmn.qdhelper.feature.readtime

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks

/** Reads the JSON-backed values without invoking getters that may be hooked for host display. */
internal object ReadTimeCloudResponseReader {
    data class Totals(val todaySeconds: Int, val weekSeconds: Int)

    fun read(
        readInfo: Any,
        fieldReader: (Any, String) -> Int? = ::readHostIntField,
    ): Totals? {
        val today = fieldReader(readInfo, "TodayReadTime") ?: return null
        val week = fieldReader(readInfo, "WeekReadTime") ?: return null
        return Totals(todaySeconds = today, weekSeconds = week)
    }

    private fun readHostIntField(instance: Any, name: String): Int? =
        FeatureHooks.findFieldOrNull(instance.javaClass, name)
            ?.takeIf { field -> field.type == Int::class.javaPrimitiveType }
            ?.let { field -> FeatureHooks.read(field, instance) as? Int }
}
