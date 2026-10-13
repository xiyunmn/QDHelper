package com.xiyunmn.qdhelper.feature.maintenance.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.xiyunmn.qdhelper.feature.maintenance.*
import com.xiyunmn.qdhelper.hook.concurrency.ManagedExecutor
import com.xiyunmn.qdhelper.settings.api.*

internal object UserLookupTool : SettingsToolController {
    private val worker = ManagedExecutor("feature.maintenance.settings-lookup", "QDHelper-SettingsLookup")
    override fun read(context: Context) = SettingsToolState(
        text = "输入用户 ID 后立即进入宿主原生个人主页，并在后台反查用户名。",
        fields = listOf(SettingsToolField("userId", "用户 ID", "", true)),
        commands = listOf(SettingsToolCommand("open", "查找用户主页")),
    )
    override fun execute(activity: Activity, command: String, values: Map<String, String>, result: (SettingsToolResult) -> Unit): AutoCloseable? {
        val app = activity.applicationContext
        val id = UserLookupInput.userId(values["userId"].orEmpty())
        if (id == null) { result(SettingsToolResult(read(app).copy(error = "用户 ID 必须是大于 0 的整数。"))); return null }
        if (!UserProfileNavigator.open(activity, id)) { result(SettingsToolResult(read(app).copy(error = "无法打开宿主个人主页。"))); return null }
        val handler = Handler(Looper.getMainLooper())
        val accepted = worker.execute {
            val response = UserLookupHost.search(app, id.toString())
            val message = when (response) {
                is UserLookupHostResult.Failure -> "已打开个人主页，用户名反查失败：${response.message}"
                is UserLookupHostResult.Success -> UserLookupSelectionPolicy.exactUserId(response.candidates, id)?.userName?.takeIf(String::isNotBlank)?.let { "用户名：$it" } ?: "已打开个人主页，未匹配到用户名"
            }
            handler.post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
        }
        result(SettingsToolResult(message = if (accepted) "已打开个人主页" else "已打开个人主页，用户名反查未启动", close = true))
        return null
    }
}

internal object ClearHostDataTool : SettingsToolController {
    private val worker = ManagedExecutor("feature.maintenance.settings-clear", "QDHelper-SettingsClear")
    override fun read(context: Context) = SettingsToolState("清理宿主数据，保留登录态相关目录。", commands = listOf(SettingsToolCommand("clear", "开始清理", "将执行现有宿主清理规则。登录态相关目录保留；删除的数据不可恢复。")))
    override fun execute(activity: Activity, command: String, values: Map<String, String>, result: (SettingsToolResult) -> Unit): AutoCloseable? {
        val app = activity.applicationContext
        if (!worker.execute { val response = MaintenanceActions.clearDataKeepingLogin(app); result(SettingsToolResult(read(app), response.message)) }) result(SettingsToolResult(message = "清理未启动，请重试"))
        return null
    }
}
