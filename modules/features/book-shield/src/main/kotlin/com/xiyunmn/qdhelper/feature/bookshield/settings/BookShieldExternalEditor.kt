package com.xiyunmn.qdhelper.feature.bookshield.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xiyunmn.qdhelper.config.BookShieldYamlCodec
import com.xiyunmn.qdhelper.host.api.reflection.HostAccess
import com.xiyunmn.qdhelper.host.api.reflection.callHostResult
import com.xiyunmn.qdhelper.host.api.reflection.declaredMethodOrNull
import com.xiyunmn.qdhelper.host.api.reflection.findHostClass
import com.xiyunmn.qdhelper.shared.io.readBytesBounded
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal data class BookShieldExternalEditSession(
    val file: File,
    val uri: Uri,
    val originalText: String,
)

internal object BookShieldExternalEditor {
    private const val EDIT_DIRECTORY = "qdhelper/book-shield/external-edit"
    private const val FILE_PROVIDER_CLASS = "androidx.core.content.FileProvider"
    private const val FILE_NAME = "rules.yaml"
    private const val STALE_FILE_AGE_MS = 24L * 60L * 60L * 1000L
    private const val URI_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun createSession(context: Context, text: String): Result<BookShieldExternalEditSession> {
        var createdFile: File? = null
        return runCatching {
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= BookShieldYamlCodec.MAX_BYTES) { "规则文件不能超过 1 MiB" }
            val directory = File(context.filesDir, EDIT_DIRECTORY)
            require(directory.isDirectory || directory.mkdirs()) { "无法创建外部编辑临时目录" }
            val staleBefore = System.currentTimeMillis() - STALE_FILE_AGE_MS
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.lastModified() < staleBefore }
                .forEach { it.delete() }
            val file = File(directory, "rules-${UUID.randomUUID()}.yaml")
            createdFile = file
            file.outputStream().use { output ->
                output.write(bytes)
                output.flush()
            }
            BookShieldExternalEditSession(
                file = file,
                uri = fileProviderUri(context, file),
                originalText = text,
            )
        }.onFailure {
            createdFile?.let { file -> runCatching { file.delete() } }
        }
    }

    fun editIntent(context: Context, session: BookShieldExternalEditSession): Intent {
        val edit = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(session.uri, "text/plain")
            clipData = ClipData.newUri(context.contentResolver, FILE_NAME, session.uri)
            addFlags(URI_FLAGS)
        }
        return Intent.createChooser(edit, "使用文件编辑器编辑内容屏蔽规则").apply {
            clipData = edit.clipData
            addFlags(URI_FLAGS)
        }
    }

    fun readText(session: BookShieldExternalEditSession): Result<String> = runCatching {
        val bytes = session.file.inputStream().use { input ->
            input.readBytesBounded(BookShieldYamlCodec.MAX_BYTES.toLong())
        }
        decodeExternalEditText(bytes)
    }

    fun cleanup(context: Context, session: BookShieldExternalEditSession) {
        runCatching { context.revokeUriPermission(session.uri, URI_FLAGS) }
        discard(session)
    }

    fun discard(session: BookShieldExternalEditSession) {
        runCatching { session.file.delete() }
        runCatching { session.file.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete() }
    }

    private fun fileProviderUri(context: Context, file: File): Uri {
        val providerClass = context.classLoader.findHostClass(FILE_PROVIDER_CLASS)
            ?: error("宿主文件共享类不可用")
        val method = providerClass.declaredMethodOrNull(
            "getUriForFile",
            Context::class.java,
            String::class.java,
            File::class.java,
        ) ?: error("宿主文件共享入口不可用")
        val authorities = listOf(
            "${context.packageName}.provider",
            "${context.packageName}.fileprovider",
        )
        var lastFailure: Throwable? = null
        authorities.forEach { authority ->
            when (val result = method.callHostResult(null, context, authority, file)) {
                is HostAccess.Value -> (result.value as? Uri)?.let { return it }
                is HostAccess.Failed -> lastFailure = IllegalStateException(result.errorType)
                HostAccess.Missing -> lastFailure = IllegalStateException("FileProvider 调用入口缺失")
                is HostAccess.Ambiguous -> lastFailure = IllegalStateException("FileProvider 调用入口不唯一")
            }
        }
        throw IllegalStateException("宿主文件共享服务不可用", lastFailure)
    }
}

internal fun decodeExternalEditText(bytes: ByteArray): String {
    require(bytes.size <= BookShieldYamlCodec.MAX_BYTES) { "规则文件不能超过 1 MiB" }
    return runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("\uFEFF")
    }.getOrElse { throwable ->
        throw IllegalArgumentException("规则文件不是有效 UTF-8", throwable)
    }
}
