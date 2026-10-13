package com.xiyunmn.qdhelper.settings.ui.compose.state

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.xiyunmn.qdhelper.config.ConfigRuntime
import com.xiyunmn.qdhelper.config.SettingCollections
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.config.schema.SettingSchemaCatalog
import com.xiyunmn.qdhelper.settings.api.SettingsActionScope
import com.xiyunmn.qdhelper.settings.api.SettingsPageId
import com.xiyunmn.qdhelper.settings.api.SettingsRow
import com.xiyunmn.qdhelper.settings.ui.SettingsUiRuntime
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.SettingsNavigator
import com.xiyunmn.qdhelper.settings.ui.compose.navigation.SettingsSearchIndex
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingsApplyPolicy
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.SettingsPresentationCatalog
import com.xiyunmn.qdhelper.settings.ui.compose.presentation.inlineDestinations
import java.lang.ref.WeakReference

internal class SettingsController(activity: Activity, val catalog: SettingsPresentationCatalog, val navigator: SettingsNavigator, closePanel: () -> Unit) : AutoCloseable {
    private val host = WeakReference(activity)
    private var closeAction: (() -> Unit)? = closePanel
    fun closePanel() { closeAction?.invoke() }
    val activity get() = checkNotNull(host.get()?.takeUnless { it.isDestroyed || it.isFinishing }) { "Settings host closed" }
    private val app = activity.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val values = mutableMapOf<String, MutableState<Any>>()
    val revision = mutableIntStateOf(0)
    val resetRevision = mutableIntStateOf(0)
    private var toast: Toast? = null
    val search = SettingsSearchIndex(catalog)
    var closed = false
        private set
    private val observer: AutoCloseable
    init {
        SettingsRuntime.initialize(app)
        SettingsApplyPolicy.capture(app)
        observer = ConfigRuntime.observeUserChanges(app, SettingSchemaCatalog.settingKeys.map { it.rawKey }.toSet()) {
            handler.post { refreshValues() }
        }
    }
    val scope = object : SettingsActionScope {
        override val activity: Activity get() = this@SettingsController.activity
        override fun refresh(page: SettingsPageId, focusTitle: String?) {
            if (closed) return
            refreshValues()
            // A callback refreshes its data without stealing navigation from a newer screen.
            catalog.rows.values.firstOrNull { it.title == focusTitle }?.let { row ->
                val source = catalog.metadata.getValue(row.id).destination
                val current = navigator.current
                if ((inlineDestinations[source] ?: source) == current.destination ||
                    current.destination == "page-customization" && source == current.selectedTab) current.focusRow = row.id
            }
        }
        override fun closeExclusiveSwitch(rawKey: String) { refreshValues() }
        override fun applyUiSettings() { if (!closed) SettingsUiRuntime.requireConfiguration().applyUiSettings(activity) }
        override fun restartHost() { restart() }
    }
    fun raw(key: String): Any = when (SettingSchemaCatalog.requireKey(key).valueType) {
        Boolean::class -> ConfigRuntime.readBoolean(key)
        Long::class -> ConfigRuntime.readLong(key)
        else -> ConfigRuntime.readString(key)
    }
    fun value(key: String): Any = values.getOrPut(key) { mutableStateOf(raw(key)) }.value
    fun boolean(key: String) = value(key) as Boolean
    fun string(key: String) = value(key) as String
    fun refreshValues() {
        if (closed) return
        values.forEach { (key, state) -> state.value = raw(key) }
        revision.intValue++
    }
    fun toggle(row: SettingsRow.Toggle, enabled: Boolean) {
        val success = if (row.key in SettingCollections.SPLASH_SOURCE_TOGGLE_KEYS) SettingsRuntime.putExclusiveSplashToggle(app, row.key, enabled)
        else SettingsRuntime.putBoolean(app, row.key, enabled)
        if (success) row.onChanged?.run(scope, enabled) else notify("保存失败")
        if (success) SettingsApplyPolicy.mode(row).takeIf { it == com.xiyunmn.qdhelper.settings.ui.compose.presentation.ApplyMode.PageReload || it == com.xiyunmn.qdhelper.settings.ui.compose.presentation.ApplyMode.NextColdStart }?.let { notify("已保存，${it.label}") }
        refreshValues()
    }
    fun putBoolean(key: String, enabled: Boolean): Boolean = SettingsRuntime.putBoolean(app, key, enabled).also { if (!it) notify("保存失败"); refreshValues() }
    fun putString(key: String, text: String): Boolean = SettingsRuntime.putString(app, key, text).also { if (!it) notify("保存失败"); refreshValues() }
    fun dependency(row: SettingsRow): String? {
        if (row.id == "page-customization.selection-guess") {
            val parent = catalog.rows.getValue("page-customization.selection") as SettingsRow.PageCustomization
            if (!boolean(parent.controller.enabledKey)) return "需要先开启「自定义精选页」"
        }
        return catalog.metadata.getValue(row.id).dependencies.firstOrNull { dependency ->
            !boolean((catalog.rows.getValue(dependency.rowId) as SettingsRow.Toggle).key)
        }?.reason
    }
    fun openRow(row: SettingsRow, focus: String? = null) {
        when (row) {
            is SettingsRow.OrderedChoice, is SettingsRow.ParagraphOrder -> {
                openDestination(catalog.metadata.getValue(row.id).destination, row.id)
                navigator.current.expanded[row.id] = true
                navigator.current.focusChoice = focus
            }
            is SettingsRow.PageCustomization -> openDestination(catalog.metadata.getValue(row.id).destination, focus ?: row.id)
            is SettingsRow.PageLink -> openDestination(catalog.destinationForPage(row.targetPage), focus)
            is SettingsRow.ExternalLink -> runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.url)).addCategory(Intent.CATEGORY_BROWSABLE)) }.onFailure { notify("未找到可打开链接的应用") }
            is SettingsRow.Action -> if (row.tool != null) navigator.open(catalog.metadata.getValue(row.id).destination, row.id, focus)
                else runCatching { checkNotNull(row.action).run(scope) }.onFailure { notify("操作未完成：${it.javaClass.simpleName}") }
            else -> navigator.open(catalog.metadata.getValue(row.id).destination, row.id, focus)
        }
    }
    fun openDestination(destination: String, focus: String? = null) {
        if (destination == "fonts" && focus in com.xiyunmn.qdhelper.settings.ui.compose.presentation.sharedFontLibraryLinks) {
            navigator.open("font-library")
            return
        }
        if (destination in setOf("mine", "selection", "detail-layout")) {
            navigator.open("page-customization", focus = focus ?: catalog.rowsFor(destination).first().id)
            navigator.current.selectedTab = destination
            return
        }
        inlineDestinations[destination]?.let { parent ->
            navigator.open(parent, focus = focus ?: catalog.rowsFor(destination).firstOrNull()?.id)
            return
        }
        val only = catalog.rowsFor(destination).singleOrNull()
        val editor = only?.takeIf { it is SettingsRow.DocumentEditor || it is SettingsRow.Action && it.tool != null }?.id
        navigator.open(destination, editor, focus)
    }
    fun reset(destination: String, all: Boolean = false) {
        val owners = if (all) catalog.sections else catalog.resetActionSections(destination)
        val rowKeys = if (all) emptySet() else catalog.resetRows(destination).flatMap { it.settingKeys }.toSet()
        val extraKeys = owners.flatMap { it.resetKeys }.toSet()
        val actions = owners.flatMap { it.resetActions.values }
        val actionResults = actions.map { runCatching { it.reset(app) }.getOrDefault(false) }
        val keysSaved = if (all) SettingsRuntime.resetDefaults(app, SettingSchemaCatalog.settingKeys.filter { it.scope != com.xiyunmn.qdhelper.config.SettingScope.RUNTIME }.map { it.rawKey }.toSet() + extraKeys)
            else SettingsRuntime.resetDefaults(app, rowKeys + extraKeys)
        val success = actionResults.all { it } && keysSaved
        scope.applyUiSettings()
        notify(if (success) "已恢复默认配置" else "恢复未完成，请检查配置或文件状态")
        resetRevision.intValue++
        refreshValues()
    }
    fun notify(text: String) {
        if (closed || text.isBlank()) return
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { notify(text) }; return }
        toast?.cancel()
        toast = Toast.makeText(app, text, if (text.length > 32) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).also { it.show() }
    }
    fun restart() { if (!closed) SettingsUiRuntime.requireConfiguration().restartHost(activity, ::closePanel) }
    override fun close() {
        if (closed) return
        closed = true
        toast?.cancel()
        toast = null
        observer.close()
        handler.removeCallbacksAndMessages(null)
        values.clear()
        closeAction = null
        host.clear()
    }
}
