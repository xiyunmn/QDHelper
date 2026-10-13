package com.xiyunmn.qdhelper.config

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicReference

data class BookShieldDocumentStatus(
    val error: BookShieldYamlError? = null,
    val ruleCount: Int = 0,
)

sealed interface BookShieldRuleOperationResult {
    data class Success(
        val config: BookShieldRuleConfig,
        val text: String,
    ) : BookShieldRuleOperationResult

    data class Failure(
        val error: BookShieldYamlError,
        val candidateText: String? = null,
    ) : BookShieldRuleOperationResult
}

internal const val BOOK_SHIELD_RULE_FILE_PATH = "qdhelper/book-shield/rules.yaml"

internal fun managedBookShieldRuleFile(filesDir: File): File = File(filesDir, BOOK_SHIELD_RULE_FILE_PATH)

object BookShieldRuleRepository {
    private const val PREF_LINKED_URI = "local.shield.book.rules_uri"
    private const val PREF_MIGRATED = "local.shield.book.rules_migrated_v1"

    private val lock = Any()
    private val state = AtomicReference(initialState())

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        val application = context.applicationContext ?: context
        synchronized(lock) {
            if (appContext === application) return
            appContext = application
            val migrationError = migrateLegacyRules(application)
            val internal = readOrCreateInternal(application)
            val linkedMigration = migrateLegacyLinkedDocument(application, internal.result)
            publish(
                linkedMigration.result.config,
                linkedMigration.result.text,
                error = migrationError ?: linkedMigration.error ?: internal.error.takeIf {
                    linkedMigration.result.config == internal.result.config &&
                        linkedMigration.result.text == internal.result.text
                },
            )
        }
    }

    fun clearRuntime() {
        synchronized(lock) {
            appContext = null
            state.set(initialState())
        }
    }

    fun runtimeSnapshot(context: Context? = null): BookShieldRuntimeSnapshot {
        ensureInitialized(context)
        val current = state.get()
        return BookShieldRuntimeSnapshot(current.revision, current.config)
    }

    fun snapshot(context: Context? = null): BookShieldRuleConfig = runtimeSnapshot(context).config

    fun text(context: Context? = null): String {
        ensureInitialized(context)
        return state.get().text
    }

    fun status(context: Context? = null): BookShieldDocumentStatus {
        ensureInitialized(context)
        return state.get().status
    }

    /** Stable user-editable rule file, alongside other managed assets under files/qdhelper/. */
    fun managedFile(context: Context): File {
        val application = context.applicationContext ?: context
        return internalFile(application)
    }

    fun saveText(context: Context, text: String): BookShieldRuleOperationResult = synchronized(lock) {
        val application = context.applicationContext ?: context
        ensureInitialized(application)
        when (val parsed = BookShieldYamlCodec.decode(text)) {
            is BookShieldYamlResult.Failure -> BookShieldRuleOperationResult.Failure(parsed.error, text)
            is BookShieldYamlResult.Success -> saveValidated(application, parsed.config, parsed.text)
        }
    }

    internal fun saveTextWithTransaction(
        context: Context,
        text: String,
        transaction: () -> Result<Unit>,
    ): BookShieldRuleOperationResult = synchronized(lock) {
        val application = context.applicationContext ?: context
        ensureInitialized(application)
        val parsed = when (val decoded = BookShieldYamlCodec.decode(text)) {
            is BookShieldYamlResult.Failure -> return@synchronized BookShieldRuleOperationResult.Failure(decoded.error, text)
            is BookShieldYamlResult.Success -> decoded
        }
        val persisted = persistWithoutPublishing(application, parsed.text)
        if (persisted is PersistenceResult.Failure) {
            return@synchronized BookShieldRuleOperationResult.Failure(persisted.error)
        }
        persisted as PersistenceResult.Success
        val committed = runCatching(transaction).getOrElse { Result.failure(it) }
        if (committed.isFailure) {
            val rolledBack = restorePersistence(application, persisted.backup)
            val detail = committed.exceptionOrNull()?.message ?: "普通设置保存失败"
            val suffix = if (rolledBack) "，已恢复原配置" else "，且文件回滚失败"
            return@synchronized BookShieldRuleOperationResult.Failure(BookShieldYamlError(detail + suffix))
        }
        publish(parsed.config, parsed.text)
        BookShieldRuleOperationResult.Success(parsed.config, parsed.text)
    }

    fun update(
        context: Context,
        transform: (BookShieldRuleConfig) -> BookShieldRuleConfig,
    ): BookShieldRuleOperationResult = synchronized(lock) {
        val application = context.applicationContext ?: context
        ensureInitialized(application)
        val latest = readInternal(application)
        if (latest is BookShieldRuleOperationResult.Failure) return@synchronized latest
        latest as BookShieldRuleOperationResult.Success
        val next = try {
            transform(latest.config).normalized()
        } catch (throwable: IllegalArgumentException) {
            return@synchronized BookShieldRuleOperationResult.Failure(
                BookShieldYamlError(throwable.message ?: "规则无效"),
            )
        }
        when (val rewritten = BookShieldYamlCodec.rewrite(latest.text, next)) {
            is BookShieldYamlResult.Failure -> BookShieldRuleOperationResult.Failure(rewritten.error)
            is BookShieldYamlResult.Success -> saveValidated(application, rewritten.config, rewritten.text)
        }
    }

    fun reset(context: Context): BookShieldRuleOperationResult = synchronized(lock) {
        val application = context.applicationContext ?: context
        ensureInitialized(application)
        saveValidated(application, BookShieldRuleConfig.EMPTY, BookShieldYamlCodec.template())
    }

    private fun saveValidated(
        context: Context,
        config: BookShieldRuleConfig,
        text: String,
    ): BookShieldRuleOperationResult {
        return when (val persisted = persistWithoutPublishing(context, text)) {
            is PersistenceResult.Failure -> BookShieldRuleOperationResult.Failure(persisted.error)
            is PersistenceResult.Success -> {
                publish(config, text)
                BookShieldRuleOperationResult.Success(config, text)
            }
        }
    }

    private fun persistWithoutPublishing(
        context: Context,
        text: String,
    ): PersistenceResult {
        val internalBefore = when (val read = readInternalBytes(context)) {
            is BytesRead.Success -> read.bytes
            BytesRead.Missing -> null
            is BytesRead.Failure -> return PersistenceResult.Failure(read.error)
        }
        val backup = PersistenceBackup(internalBefore)
        if (!writeInternal(context, text)) {
            val restored = restorePersistence(context, backup)
            val suffix = if (restored) "" else "；恢复原文件失败"
            return PersistenceResult.Failure(BookShieldYamlError("保存规则文件失败$suffix"))
        }
        return PersistenceResult.Success(backup)
    }

    private fun restorePersistence(context: Context, backup: PersistenceBackup): Boolean =
        restoreInternal(context, backup.internalBytes)

    private fun readOrCreateInternal(context: Context): InternalLoad {
        val existing = readInternal(context)
        if (existing is BookShieldRuleOperationResult.Success) return InternalLoad(existing)
        existing as BookShieldRuleOperationResult.Failure
        val text = BookShieldYamlCodec.template()
        val written = writeInternal(context, text)
        val error = if (written) {
            BookShieldYamlError("内部规则文件无效，已恢复为空模板：${existing.error.displayMessage()}")
        } else {
            BookShieldYamlError("内部规则文件无效且无法恢复：${existing.error.displayMessage()}")
        }
        return InternalLoad(BookShieldRuleOperationResult.Success(BookShieldRuleConfig.EMPTY, text), error)
    }

    private fun readInternal(context: Context): BookShieldRuleOperationResult = when (val read = readInternalBytes(context)) {
        is BytesRead.Success -> decodeBytes(read.bytes, "读取内部规则文件失败")
        BytesRead.Missing -> BookShieldRuleOperationResult.Failure(BookShieldYamlError("规则文件不存在"))
        is BytesRead.Failure -> BookShieldRuleOperationResult.Failure(read.error)
    }

    private fun readUri(context: Context, uri: Uri): BookShieldRuleOperationResult = when (val read = readUriBytes(context, uri)) {
        is BytesRead.Success -> decodeBytes(read.bytes, "读取关联文件失败")
        BytesRead.Missing -> BookShieldRuleOperationResult.Failure(BookShieldYamlError("关联文件不存在"))
        is BytesRead.Failure -> BookShieldRuleOperationResult.Failure(read.error)
    }

    private fun decodeBytes(bytes: ByteArray, label: String): BookShieldRuleOperationResult {
        val text = runCatching { decodeUtf8(bytes) }.getOrElse {
            return BookShieldRuleOperationResult.Failure(BookShieldYamlError("$label：文件不是有效 UTF-8"))
        }
        return decodeOperation(text)
    }

    private fun readInternalBytes(context: Context): BytesRead {
        val file = internalFile(context)
        if (!file.isFile) return BytesRead.Missing
        return runCatching { file.inputStream().use(::readLimited) }
            .fold(
                onSuccess = { BytesRead.Success(it) },
                onFailure = { BytesRead.Failure(BookShieldYamlError("读取内部规则文件失败：${it.message}")) },
            )
    }

    private fun readUriBytes(context: Context, uri: Uri): BytesRead = runCatching {
        val stream = context.contentResolver.openInputStream(uri) ?: return@runCatching BytesRead.Missing
        BytesRead.Success(stream.use(::readLimited))
    }.getOrElse { BytesRead.Failure(BookShieldYamlError("读取关联文件失败：${it.message}")) }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > BookShieldYamlCodec.MAX_BYTES) error("文件不能超过 1 MiB")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decodeOperation(text: String): BookShieldRuleOperationResult = when (val decoded = BookShieldYamlCodec.decode(text)) {
        is BookShieldYamlResult.Success -> BookShieldRuleOperationResult.Success(decoded.config, decoded.text)
        is BookShieldYamlResult.Failure -> BookShieldRuleOperationResult.Failure(decoded.error, text)
    }

    private fun writeInternal(context: Context, text: String): Boolean =
        writeInternalBytes(context, text.toByteArray(Charsets.UTF_8))

    private fun writeInternalBytes(context: Context, bytes: ByteArray): Boolean {
        if (bytes.size > BookShieldYamlCodec.MAX_BYTES) return false
        val file = internalFile(context)
        if (file.parentFile?.let { it.isDirectory || it.mkdirs() } == false) return false
        val atomic = AtomicFile(file)
        val stream = runCatching { atomic.startWrite() }.getOrNull() ?: return false
        return try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
            true
        } catch (_: Throwable) {
            atomic.failWrite(stream)
            false
        }
    }

    private fun restoreInternal(context: Context, bytes: ByteArray?): Boolean {
        if (bytes != null) return writeInternalBytes(context, bytes)
        val file = internalFile(context)
        return !file.exists() || file.delete()
    }

    private fun migrateLegacyLinkedDocument(
        context: Context,
        fallback: BookShieldRuleOperationResult.Success,
    ): InternalLoad {
        val prefs = SettingsRuntime.prefs(context)
        val rawUri = prefs.getString(PREF_LINKED_URI, null)?.takeIf(String::isNotBlank)
            ?: return InternalLoad(fallback)
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        val linked = uri?.let { readUri(context, it) }
        if (linked is BookShieldRuleOperationResult.Success && !writeInternal(context, linked.text)) {
            return InternalLoad(fallback, BookShieldYamlError("旧关联规则有效，但收回内部规则失败"))
        }
        if (!prefs.edit().remove(PREF_LINKED_URI).commit()) {
            return InternalLoad(
                if (linked is BookShieldRuleOperationResult.Success) linked else fallback,
                BookShieldYamlError("旧关联规则已收回，但关联标记清理失败"),
            )
        }
        if (uri != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        return when (linked) {
            null -> InternalLoad(fallback, BookShieldYamlError("旧关联规则地址无效，已继续使用内部规则"))
            is BookShieldRuleOperationResult.Failure -> InternalLoad(fallback, linked.error)
            is BookShieldRuleOperationResult.Success -> InternalLoad(linked)
        }
    }

    private fun migrateLegacyRules(context: Context): BookShieldYamlError? {
        val prefs = SettingsRuntime.prefs(context)
        if (prefs.getBoolean(PREF_MIGRATED, false)) return null
        val legacy = legacyRuleConfig(prefs.all)
        val existing = readInternal(context) as? BookShieldRuleOperationResult.Success
        val merged = mergeMigratedRules(existing?.config ?: BookShieldRuleConfig.EMPTY, legacy)
        val text = if (existing == null) {
            BookShieldYamlCodec.template(merged)
        } else {
            when (val rewritten = BookShieldYamlCodec.rewrite(existing.text, merged)) {
                is BookShieldYamlResult.Success -> rewritten.text
                is BookShieldYamlResult.Failure -> BookShieldYamlCodec.template(merged)
            }
        }
        if (!writeInternal(context, text)) return BookShieldYamlError("旧书籍屏蔽规则迁移失败：无法写入 YAML")
        val verified = readInternal(context) as? BookShieldRuleOperationResult.Success
            ?: return BookShieldYamlError("旧书籍屏蔽规则迁移失败：YAML 回读校验失败")
        if (verified.config != merged) return BookShieldYamlError("旧书籍屏蔽规则迁移失败：YAML 回读内容不一致")
        val editor = prefs.edit()
        LEGACY_RULE_KEYS.forEach(editor::remove)
        if (!editor.putBoolean(PREF_MIGRATED, true).commit()) {
            return BookShieldYamlError("旧规则已写入 YAML，但迁移标记保存失败；下次启动会安全重试")
        }
        return null
    }

    private fun publish(
        config: BookShieldRuleConfig,
        text: String,
        error: BookShieldYamlError? = null,
    ) {
        val previous = state.get()
        val revision = if (previous.config != config || previous.text != text) {
            previous.revision + 1L
        } else {
            previous.revision
        }
        state.set(
            RepositoryState(
                revision = revision,
                config = config,
                text = text,
                status = BookShieldDocumentStatus(
                    error = error,
                    ruleCount = config.ruleCount,
                ),
            ),
        )
    }

    private fun internalFile(context: Context): File = managedBookShieldRuleFile(context.filesDir)

    private fun ensureInitialized(context: Context?) {
        if (appContext == null && context != null) initialize(context)
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private data class RepositoryState(
        val revision: Long,
        val config: BookShieldRuleConfig,
        val text: String,
        val status: BookShieldDocumentStatus,
    )

    private data class InternalLoad(
        val result: BookShieldRuleOperationResult.Success,
        val error: BookShieldYamlError? = null,
    )

    private data class PersistenceBackup(
        val internalBytes: ByteArray?,
    )

    private sealed interface PersistenceResult {
        data class Success(val backup: PersistenceBackup) : PersistenceResult
        data class Failure(val error: BookShieldYamlError) : PersistenceResult
    }

    private sealed interface BytesRead {
        data class Success(val bytes: ByteArray) : BytesRead
        data class Failure(val error: BookShieldYamlError) : BytesRead
        data object Missing : BytesRead
    }

    private fun initialState(): RepositoryState {
        val text = BookShieldYamlCodec.template()
        return RepositoryState(
            revision = 0L,
            config = BookShieldRuleConfig.EMPTY,
            text = text,
            status = BookShieldDocumentStatus(),
        )
    }

    private val LEGACY_RULE_KEYS = listOf(
        LEGACY_BOOK_SHIELD_AUTHORS,
        LEGACY_BOOK_SHIELD_NAME_KEYWORDS,
        LEGACY_BOOK_SHIELD_NAME_FULL,
        LEGACY_BOOK_SHIELD_TYPES,
        LEGACY_BOOK_SHIELD_WORDS_AT_MOST,
        LEGACY_BOOK_SHIELD_WORDS_AT_LEAST,
        LEGACY_BOOK_SHIELD_BOOK_IDS,
    )
}

internal fun legacyRuleConfig(values: Map<String, *>): BookShieldRuleConfig {
    fun string(key: String): String = values[key] as? String ?: ""
    val atMost = string(LEGACY_BOOK_SHIELD_WORDS_AT_MOST).trim().toLongOrNull()?.takeIf { it > 0L }
    val atLeast = string(LEGACY_BOOK_SHIELD_WORDS_AT_LEAST).trim().toLongOrNull()?.takeIf { it > 0L }
    val validBounds = atMost == null || atLeast == null || atMost < atLeast
    return BookShieldRuleConfig(
        authors = BookShieldRuleConfig.splitQuickInput(string(LEGACY_BOOK_SHIELD_AUTHORS)),
        titleKeywords = BookShieldRuleConfig.splitQuickInput(string(LEGACY_BOOK_SHIELD_NAME_KEYWORDS)),
        fullTitles = BookShieldRuleConfig.splitQuickInput(string(LEGACY_BOOK_SHIELD_NAME_FULL)),
        types = BookShieldRuleConfig.splitQuickInput(string(LEGACY_BOOK_SHIELD_TYPES)),
        bookIds = BookShieldRuleConfig.splitQuickInput(string(LEGACY_BOOK_SHIELD_BOOK_IDS))
            .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0L } },
        wordsAtMost = atMost.takeIf { validBounds },
        wordsAtLeast = atLeast.takeIf { validBounds },
    ).normalized()
}

internal const val LEGACY_BOOK_SHIELD_AUTHORS = "feature.shield.book.authors"
internal const val LEGACY_BOOK_SHIELD_NAME_KEYWORDS = "feature.shield.book.name_keywords"
internal const val LEGACY_BOOK_SHIELD_NAME_FULL = "feature.shield.book.name_full"
internal const val LEGACY_BOOK_SHIELD_TYPES = "feature.shield.book.types"
internal const val LEGACY_BOOK_SHIELD_WORDS_AT_MOST = "feature.shield.book.words_at_most"
internal const val LEGACY_BOOK_SHIELD_WORDS_AT_LEAST = "feature.shield.book.words_at_least"
internal const val LEGACY_BOOK_SHIELD_BOOK_IDS = "feature.shield.book.book_ids"

internal fun mergeMigratedRules(
    existing: BookShieldRuleConfig,
    legacy: BookShieldRuleConfig,
): BookShieldRuleConfig {
    val candidateAtMost = existing.wordsAtMost ?: legacy.wordsAtMost
    val candidateAtLeast = existing.wordsAtLeast ?: legacy.wordsAtLeast
    val boundsAreValid = candidateAtMost == null || candidateAtLeast == null || candidateAtMost < candidateAtLeast
    val candidate = existing.copy(
        authors = existing.authors + legacy.authors,
        titleKeywords = existing.titleKeywords + legacy.titleKeywords,
        fullTitles = existing.fullTitles + legacy.fullTitles,
        types = existing.types + legacy.types,
        bookIds = existing.bookIds + legacy.bookIds,
        wordsAtMost = if (boundsAreValid) candidateAtMost else existing.wordsAtMost,
        wordsAtLeast = if (boundsAreValid) candidateAtLeast else existing.wordsAtLeast,
    )
    return candidate.normalized()
}
