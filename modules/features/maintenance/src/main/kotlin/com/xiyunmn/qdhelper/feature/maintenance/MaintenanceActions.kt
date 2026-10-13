package com.xiyunmn.qdhelper.feature.maintenance

import android.content.Context
import com.xiyunmn.qdhelper.config.BookShieldRuleFileExport
import com.xiyunmn.qdhelper.config.BookShieldRuleTransfer
import com.xiyunmn.qdhelper.config.ConfigurationArchive
import java.io.File

object MaintenanceActions {
    fun exportConfigArchive(context: Context): Result<com.xiyunmn.qdhelper.config.ConfigurationArchiveExport> =
        ConfigurationArchive.export(context)

    fun importConfigArchive(context: Context, bytes: ByteArray): ActionResult =
        ConfigurationArchive.import(context, bytes).fold(
            onSuccess = { ActionResult(true, it.message) },
            onFailure = { ActionResult(false, "导入失败：${it.message ?: it.javaClass.simpleName}") },
        )

    fun exportBookShieldRules(context: Context): Result<BookShieldRuleFileExport> =
        BookShieldRuleTransfer.export(context)

    fun importBookShieldRules(context: Context, bytes: ByteArray): ActionResult =
        BookShieldRuleTransfer.import(context, bytes).fold(
            onSuccess = { ActionResult(true, it.message) },
            onFailure = { ActionResult(false, "导入失败：${it.message ?: it.javaClass.simpleName}") },
        )

    fun clearDataKeepingLogin(context: Context): ActionResult {
        val appContext = context.applicationContext ?: context
        val dataDir = appContext.dataDir ?: return ActionResult(false, "无法定位数据目录")
        val deleted = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        listOfNotNull(appContext.cacheDir, appContext.codeCacheDir, appContext.externalCacheDir).forEach { dir ->
            deleteChildren(dir, deleted, failed)
        }
        dataDir.listFiles().orEmpty().forEach { child ->
            if (MaintenanceFilePolicy.shouldKeepDataRoot(child.name)) return@forEach
            if (!MaintenanceFilePolicy.shouldDeleteDataRoot(child.name)) return@forEach
            if (MaintenanceFilePolicy.deletePathInside(dataDir, child)) {
                deleted += child.name
            } else {
                failed += child.name
            }
        }
        val message = buildString {
            append("已清理缓存、广告和崩溃等可重建数据")
            if (deleted.isNotEmpty()) append("：").append(deleted.take(8).joinToString(", "))
            if (failed.isNotEmpty()) append("；失败 ").append(failed.size).append(" 项")
        }
        return ActionResult(failed.isEmpty(), message)
    }

    private fun deleteChildren(dir: File, deleted: MutableSet<String>, failed: MutableSet<String>) {
        if (!dir.isDirectory) return
        dir.listFiles().orEmpty().forEach { child ->
            if (MaintenanceFilePolicy.deletePathInside(dir, child)) {
                deleted += dir.name + "/" + child.name
            } else {
                failed += dir.name + "/" + child.name
            }
        }
    }
}

internal object MaintenanceFilePolicy {
    fun shouldKeepDataRoot(name: String): Boolean {
        if (name in KEEP_DATA_ROOT_NAMES) return true
        if (name.startsWith("app_webview")) return true
        return SENSITIVE_ROOT_TOKENS.any { token -> name.contains(token, ignoreCase = true) }
    }

    fun shouldDeleteDataRoot(name: String): Boolean {
        if (name == "cache" || name == "code_cache") return false
        return DELETE_DATA_ROOT_PREFIXES.any { prefix -> name.startsWith(prefix) }
    }

    fun deletePathInside(root: File, target: File): Boolean {
        return runCatching {
            val rootPath = root.canonicalFile.toPath()
            val targetPath = target.canonicalFile.toPath()
            if (!targetPath.startsWith(rootPath)) return false
            target.deleteRecursively()
        }.getOrDefault(false)
    }

    private val KEEP_DATA_ROOT_NAMES = setOf(
        "shared_prefs",
        "databases",
        "files",
        "no_backup",
    )

    private val SENSITIVE_ROOT_TOKENS = listOf(
        "account",
        "login",
        "mmkv",
        "passport",
        "token",
        "user",
    )

    private val DELETE_DATA_ROOT_PREFIXES = listOf(
        "app_adnet",
        "app_bugly",
        "app_crash",
        "app_e_qq_com_",
        "app_gatherer",
        "app_tangram",
        "app_tbs",
        "app_textures",
        "app_turing",
    )
}

data class ActionResult(
    val success: Boolean,
    val message: String,
)
