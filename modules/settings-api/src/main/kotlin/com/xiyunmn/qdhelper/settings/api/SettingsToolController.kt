package com.xiyunmn.qdhelper.settings.api

import android.app.Activity
import android.content.Context

/** Feature-owned data and commands for dedicated settings tools, without a UI toolkit dependency. */
data class SettingsToolField(val id: String, val label: String, val value: String, val numeric: Boolean = false,
    val rangeEnd: String? = null)
enum class SettingsToolStatus { Neutral, Success, Warning, Error }
data class SettingsToolEntry(
    val id: String,
    val title: String,
    val detail: String,
    val status: SettingsToolStatus = SettingsToolStatus.Neutral,
    /** Exact feature-provided fragment to emphasize, without guessing from display wording. */
    val emphasizedText: String? = null,
)
data class SettingsToolCommand(val id: String, val label: String, val confirmation: String? = null)
data class SettingsToolState(
    val text: String = "",
    val fullText: String? = null,
    val fields: List<SettingsToolField> = emptyList(),
    val entries: List<SettingsToolEntry> = emptyList(),
    val commands: List<SettingsToolCommand> = emptyList(),
    val error: String? = null,
    val status: SettingsToolStatus = SettingsToolStatus.Neutral,
)
data class SettingsToolResult(val state: SettingsToolState? = null, val message: String = "", val close: Boolean = false)

interface SettingsToolController {
    val refreshIntervalMillis: Long get() = 0
    val initialCommand: String? get() = null
    /** Main-thread snapshot. Expensive work belongs in execute, using the Feature's existing worker. */
    fun read(context: Context): SettingsToolState
    fun execute(activity: Activity, command: String, values: Map<String, String>, result: (SettingsToolResult) -> Unit): AutoCloseable?
}
