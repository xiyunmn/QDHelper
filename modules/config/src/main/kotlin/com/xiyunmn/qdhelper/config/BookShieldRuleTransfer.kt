package com.xiyunmn.qdhelper.config

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class BookShieldRuleFileExport(
    val bytes: ByteArray,
    val message: String,
)

data class BookShieldRuleFileImport(
    val message: String,
)

object BookShieldRuleTransfer {
    const val MAX_FILE_BYTES = BookShieldYamlCodec.MAX_BYTES

    fun export(context: Context): Result<BookShieldRuleFileExport> = runCatching {
        val application = context.applicationContext ?: context
        SettingsRuntime.initialize(application)
        val content = decodeBookShieldRuleFile(
            BookShieldRuleRepository.text(application).toByteArray(Charsets.UTF_8),
        )
        BookShieldRuleFileExport(
            bytes = content.text.toByteArray(Charsets.UTF_8),
            message = "内容屏蔽规则已导出",
        )
    }

    fun import(context: Context, bytes: ByteArray): Result<BookShieldRuleFileImport> = runCatching {
        val application = context.applicationContext ?: context
        SettingsRuntime.initialize(application)
        val content = decodeBookShieldRuleFile(bytes)
        when (val saved = BookShieldRuleRepository.saveText(application, content.text)) {
            is BookShieldRuleOperationResult.Success -> BookShieldRuleFileImport(
                message = "已导入 ${saved.config.ruleCount} 条内容屏蔽规则",
            )
            is BookShieldRuleOperationResult.Failure ->
                error("规则写入失败：${saved.error.displayMessage()}")
        }
    }
}

internal data class BookShieldRuleFileContent(
    val config: BookShieldRuleConfig,
    val text: String,
)

internal fun decodeBookShieldRuleFile(bytes: ByteArray): BookShieldRuleFileContent {
    require(bytes.size <= BookShieldRuleTransfer.MAX_FILE_BYTES) { "规则文件不能超过 1 MiB" }
    val text = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    }.getOrElse { throwable ->
        throw IllegalArgumentException("规则文件不是有效 UTF-8", throwable)
    }
    return when (val decoded = BookShieldYamlCodec.decode(text)) {
        is BookShieldYamlResult.Success -> BookShieldRuleFileContent(decoded.config, decoded.text)
        is BookShieldYamlResult.Failure ->
            throw IllegalArgumentException("内容屏蔽规则 YAML 无效：${decoded.error.displayMessage()}")
    }
}
