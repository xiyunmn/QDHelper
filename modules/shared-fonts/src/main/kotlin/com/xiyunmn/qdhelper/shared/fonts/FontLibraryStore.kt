package com.xiyunmn.qdhelper.shared.fonts

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.xiyunmn.qdhelper.config.SettingIds
import com.xiyunmn.qdhelper.config.SettingsRuntime
import com.xiyunmn.qdhelper.foundation.logging.RuntimeLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.LinkedHashMap
import java.util.Locale

enum class FontRole(
    val displayName: String,
    internal val enabledKey: String,
    internal val pathKey: String,
    internal val nameKey: String,
) {
    READER(
        displayName = "阅读字体",
        enabledKey = SettingIds.KEY_CUSTOM_READER_FONT_ENABLED,
        pathKey = SettingIds.KEY_CUSTOM_READER_FONT_PATH,
        nameKey = SettingIds.KEY_CUSTOM_READER_FONT_NAME,
    ),
    GLOBAL_REGULAR(
        displayName = "界面常规字体",
        enabledKey = SettingIds.KEY_CUSTOM_GLOBAL_FONT_ENABLED,
        pathKey = SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_PATH,
        nameKey = SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_NAME,
    ),
    GLOBAL_EMPHASIS(
        displayName = "界面强调字体",
        enabledKey = SettingIds.KEY_CUSTOM_GLOBAL_FONT_ENABLED,
        pathKey = SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_PATH,
        nameKey = SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_NAME,
    ),
}

/** The file stays visible in management even when it cannot currently be used. */
enum class FontFileState {
    READY,
    MISSING,
    UNREADABLE,
    INVALID_FORMAT,
    TOO_LARGE,
    PENDING_DELETE,
}

val FontFileState.isUsable: Boolean
    get() = this == FontFileState.READY

fun FontFileState.displayLabel(): String = when (this) {
    FontFileState.READY -> "可用"
    FontFileState.MISSING -> "文件不存在"
    FontFileState.UNREADABLE -> "无法读取（检查宿主 UID/权限）"
    FontFileState.INVALID_FORMAT -> "格式无效或系统不支持"
    FontFileState.TOO_LARGE -> "文件超过 80 MiB"
    FontFileState.PENDING_DELETE -> "等待删除"
}

data class FontEntry(
    val path: String,
    val state: FontFileState,
    val managed: Boolean,
)

object FontSelectionStore {
    fun isEnabled(context: Context, role: FontRole): Boolean = when (role) {
        FontRole.READER -> SettingsRuntime.isCustomReaderFontEnabled(context)
        FontRole.GLOBAL_REGULAR,
        FontRole.GLOBAL_EMPHASIS -> SettingsRuntime.isCustomGlobalFontEnabled(context)
    }

    fun selectedPath(context: Context, role: FontRole): String = SettingsRuntime.prefs(context)
        .getString(role.pathKey, "")
        .orEmpty()
        .trim()

    fun selectedRolesForPath(context: Context, path: String): Set<FontRole> {
        val target = FontLibraryStore.normalizePath(path)
        if (target.isBlank()) return emptySet()
        return FontRole.entries.filterTo(linkedSetOf()) { role ->
            selectedPath(context, role)
                .takeIf(String::isNotBlank)
                ?.let(FontLibraryStore::normalizePath) == target
        }
    }

    fun select(context: Context, role: FontRole, path: String): Boolean {
        val normalizedPath = FontLibraryStore.normalizePath(path)
        if (normalizedPath.isBlank() || !FontLibraryStore.validateTypeface(File(normalizedPath))) {
            return false
        }
        // Preserve missing/broken entries and their order; selection only appends a new path.
        val orderedFonts = appendFontWithoutReordering(
            FontLibraryStore.readFontEntries(context).map(FontEntry::path),
            normalizedPath,
        )
        val saved = SettingsRuntime.prefs(context).edit()
            .putString(SettingIds.KEY_CUSTOM_READER_FONT_LIST, orderedFonts.joinToString("\n"))
            .putString(role.pathKey, normalizedPath)
            .putString(role.nameKey, FontLibraryStore.displayNameForPath(normalizedPath))
            .putBoolean(role.enabledKey, true)
            .commit()
        if (saved) FontLibraryChanges.notifyChanged()
        return saved
    }

    fun clear(context: Context, role: FontRole): Boolean {
        val preferences = SettingsRuntime.prefs(context)
        val editor = preferences.edit()
            .putString(role.pathKey, "")
            .putString(role.nameKey, "")
        when (role) {
            FontRole.READER -> editor.putBoolean(SettingIds.KEY_CUSTOM_READER_FONT_ENABLED, false)
            FontRole.GLOBAL_REGULAR,
            FontRole.GLOBAL_EMPHASIS -> {
                val other = if (role == FontRole.GLOBAL_REGULAR) {
                    FontRole.GLOBAL_EMPHASIS
                } else {
                    FontRole.GLOBAL_REGULAR
                }
                if (selectedPath(context, other).isBlank()) {
                    editor.putBoolean(SettingIds.KEY_CUSTOM_GLOBAL_FONT_ENABLED, false)
                }
            }
        }
        val saved = editor.commit()
        if (saved) FontLibraryChanges.notifyChanged()
        return saved
    }
}

object FontLibraryStore {
    private const val FONT_DIR = "qdhelper/fonts"
    private const val TRASH_DIR = ".trash"
    private const val COMMITTED_MARKER = ".committed"
    private const val PART_SUFFIX = ".part"
    private const val TEMP_SUFFIX = ".tmp"
    private const val MAX_FONT_BYTES = 80L * 1024L * 1024L
    private const val PROBE_CACHE_LIMIT = 128

    private val mutationLock = Any()
    private val probeCache = LinkedHashMap<ProbeKey, FontFileState>(32, 0.75f, true)

    /**
     * Returns all known entries, including missing or invalid files. The managed directory is
     * scanned on every refresh so files copied by host/root tooling are discovered without a
     * preference edit.
     */
    fun readFontEntries(context: Context): List<FontEntry> = synchronized(mutationLock) {
        val application = applicationContext(context)
        cleanupTrash(application)
        val entries = LinkedHashMap<String, FontEntry>()

        fun add(rawPath: String) {
            val path = normalizePath(rawPath)
            if (path.isBlank() || entries.containsKey(path)) return
            entries[path] = FontEntry(
                path = path,
                state = probeTypeface(File(path)),
                managed = isManagedPath(application, path),
            )
        }

        val referenced = FontRole.entries.mapNotNull { role ->
            FontSelectionStore.selectedPath(application, role).takeIf(String::isNotBlank)
        }
        mergeFontPaths(indexedPaths(application), referenced, discoverManagedFiles(application)).forEach(::add)
        entries.values.toList()
    }

    /** Only usable entries are exposed to host hooks and dynamic choice dialogs. */
    fun readFonts(context: Context): List<String> = readFontEntries(context)
        .asSequence()
        .filter { it.state.isUsable }
        .map(FontEntry::path)
        .toList()

    fun writeFonts(context: Context, paths: List<String>): Boolean = synchronized(mutationLock) {
        val normalized = normalizeDistinct(paths)
        val saved = saveIndex(applicationContext(context), normalized)
        if (saved) FontLibraryChanges.notifyChanged()
        saved
    }

    fun deleteFont(context: Context, path: String, deleteFile: Boolean = true): FontDeleteResult =
        synchronized(mutationLock) {
            val application = applicationContext(context)
            val target = normalizePath(path)
            if (target.isBlank()) return@synchronized FontDeleteResult(false, false, emptySet())

            val entries = readFontEntries(application)
            val clearedRoles = FontSelectionStore.selectedRolesForPath(application, target)
            val targetFile = File(target)
            val owned = isManagedPath(application, target)
            val requiresStage = deleteFile && owned && targetFile.isFile && !isSymbolicLink(targetFile)
            FontTypefaceCache.invalidatePath(target)

            val staged = if (requiresStage) stageForDeletion(application, targetFile) else null
            if (requiresStage && staged == null) {
                // Keep references intact so the user can retry after fixing permissions.
                return@synchronized FontDeleteResult(false, false, clearedRoles)
            }

            val remaining = entries.asSequence()
                .map(FontEntry::path)
                .filterNot { it == target }
                .toList()
            val editor = SettingsRuntime.prefs(application).edit()
                .putString(SettingIds.KEY_CUSTOM_READER_FONT_LIST, remaining.joinToString("\n"))
            clearedRoles.forEach { role ->
                editor.putString(role.pathKey, "").putString(role.nameKey, "")
            }
            if (FontRole.READER in clearedRoles) {
                editor.putBoolean(SettingIds.KEY_CUSTOM_READER_FONT_ENABLED, false)
            }
            if (clearedRoles.any { it != FontRole.READER }) {
                val regular = if (FontRole.GLOBAL_REGULAR in clearedRoles) "" else {
                    FontSelectionStore.selectedPath(application, FontRole.GLOBAL_REGULAR)
                }
                val emphasis = if (FontRole.GLOBAL_EMPHASIS in clearedRoles) "" else {
                    FontSelectionStore.selectedPath(application, FontRole.GLOBAL_EMPHASIS)
                }
                if (regular.isBlank() && emphasis.isBlank()) {
                    editor.putBoolean(SettingIds.KEY_CUSTOM_GLOBAL_FONT_ENABLED, false)
                }
            }

            if (!editor.commit()) {
                staged?.let {
                    restoreStagedFile(it)
                    discardStagedBatch(it.batch)
                }
                return@synchronized FontDeleteResult(false, false, clearedRoles)
            }

            staged?.let(::markStagedCommitted)
            val fileDeleted = staged?.let(::deleteStagedFile) ?: true
            FontLibraryChanges.notifyChanged()
            FontDeleteResult(true, fileDeleted, clearedRoles)
        }

    fun clearImportedFonts(context: Context): FontClearResult = synchronized(mutationLock) {
        val application = applicationContext(context)
        val candidates = listManagedFilesForClear(application)
            ?: return@synchronized FontClearResult(settingsCleared = false, filesCleared = false)
        val stagedBatch = stageFilesForClear(application, candidates)
        if (stagedBatch === StageBatchResult.Failed) {
            return@synchronized FontClearResult(settingsCleared = false, filesCleared = false)
        }

        FontTypefaceCache.clear()
        val settingsCleared = SettingsRuntime.prefs(application).edit()
            .putString(SettingIds.KEY_CUSTOM_READER_FONT_LIST, "")
            .putString(SettingIds.KEY_CUSTOM_READER_FONT_PATH, "")
            .putString(SettingIds.KEY_CUSTOM_READER_FONT_NAME, "")
            .putBoolean(SettingIds.KEY_CUSTOM_READER_FONT_ENABLED, false)
            .putString(SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_PATH, "")
            .putString(SettingIds.KEY_CUSTOM_GLOBAL_FONT_REGULAR_NAME, "")
            .putString(SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_PATH, "")
            .putString(SettingIds.KEY_CUSTOM_GLOBAL_FONT_EMPHASIS_NAME, "")
            .putBoolean(SettingIds.KEY_CUSTOM_GLOBAL_FONT_ENABLED, false)
            .commit()

        if (!settingsCleared) {
            stagedBatch.restore()
            return@synchronized FontClearResult(settingsCleared = false, filesCleared = false)
        }

        stagedBatch.markCommitted()
        val stagedDeleted = stagedBatch.delete()
        val trashClean = cleanupTrash(application)
        val remaining = listManagedFilesForClear(application)
        val filesCleared = stagedDeleted && trashClean && remaining != null && remaining.isEmpty()
        FontLibraryChanges.notifyChanged()
        FontClearResult(settingsCleared = true, filesCleared = filesCleared)
    }

    fun displayNameForPath(path: String): String {
        val name = File(path).name
        return name.substringBeforeLast('.', name).ifBlank { name }
    }

    fun importFromUri(context: Context, uri: Uri): FontImportResult = synchronized(mutationLock) {
        val application = applicationContext(context)
        val sourceName = queryDisplayName(application, uri) ?: uri.lastPathSegment.orEmpty()
        val safeName = ensureFontExtension(
            safeFontFileName(sourceName).ifBlank { "custom_font.ttf" },
            application.contentResolver.getType(uri),
        )
        val dir = fontsDir(application)
        if ((!dir.exists() && !dir.mkdirs()) || !dir.isDirectory || isSymbolicLink(dir)) {
            return@synchronized FontImportResult.Failure("无法创建字体目录")
        }

        val target = uniqueTarget(dir, safeName)
        val part = File(dir, ".${target.name}.${System.nanoTime()}$PART_SUFFIX")
        runCatching {
            copyUriToFile(application, uri, part)
            if (probeTypeface(part, logicalName = safeName) != FontFileState.READY) {
                throw FontImportException("字体文件无法加载")
            }
            if (!moveFile(part, target)) {
                throw FontImportException("无法保存字体文件")
            }
            if (probeTypeface(target) != FontFileState.READY) {
                target.delete()
                throw FontImportException("字体文件无法加载")
            }
            if (!appendToIndex(application, normalizePath(target.path))) {
                target.delete()
                throw FontImportException("保存字体配置失败")
            }
            FontImportResult.Success(target)
        }.getOrElse { throwable ->
            part.delete()
            if (throwable is FontImportException) {
                FontImportResult.Failure(throwable.message ?: "导入字体失败")
            } else {
                RuntimeLog.w("Font library import failed: ${throwable.javaClass.name}")
                FontImportResult.Failure("导入字体失败")
            }
        }
    }

    fun validateTypeface(file: File): Boolean = probeTypeface(file) == FontFileState.READY

    /** Normalizes aliases while preserving a symlink path instead of resolving it out of scope. */
    fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return ""
        val file = File(trimmed)
        return runCatching {
            if (isSymbolicLink(file)) file.absolutePath else file.canonicalPath
        }.getOrElse { file.absolutePath }
    }

    fun isManagedPath(context: Context, path: String): Boolean {
        val candidate = File(path)
        if (isSymbolicLink(candidate)) return false
        val root = runCatching { fontsDir(applicationContext(context)).canonicalFile }.getOrNull() ?: return false
        val normalized = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        return normalized.path == root.path || normalized.path.startsWith(root.path + File.separator)
    }

    fun isFontFile(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".ttf") ||
            lower.endsWith(".otf") ||
            lower.endsWith(".ttc") ||
            lower.endsWith(".otc") ||
            lower.endsWith(".woff") ||
            lower.endsWith(".woff2")
    }

    internal fun fileSignature(file: File): FontFileSignature? {
        if (!file.isFile || isSymbolicLink(file)) return null
        return FontFileSignature(
            path = normalizePath(file.path),
            length = file.length(),
            lastModified = file.lastModified(),
            fileKey = fileKey(file),
        )
    }

    internal fun clearProbeCache() {
        synchronized(probeCache) { probeCache.clear() }
    }

    private fun applicationContext(context: Context): Context = context.applicationContext ?: context

    private fun indexedPaths(context: Context): List<String> = SettingsRuntime.prefs(context)
        .getString(SettingIds.KEY_CUSTOM_READER_FONT_LIST, "")
        .orEmpty()
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    private fun discoverManagedFiles(context: Context): List<String> {
        val dir = fontsDir(context)
        if (!dir.isDirectory || isSymbolicLink(dir)) return emptyList()
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        return children.asSequence()
            .filter { file ->
                file.isFile &&
                    isFontFile(file.name) &&
                    !isTemporaryName(file.name) &&
                    !isSymbolicLink(file)
            }
            .map { file -> normalizePath(file.path) }
            .filter(String::isNotBlank)
            .distinct()
            .sortedWith(compareBy<String> { File(it).name.lowercase(Locale.US) }.thenBy { File(it).name })
            .toList()
    }

    private fun probeTypeface(file: File, logicalName: String = file.name): FontFileState {
        val normalized = normalizePath(file.path)
        val exists = runCatching { file.exists() }.getOrDefault(false)
        val length = if (exists) runCatching { file.length() }.getOrDefault(-1L) else -1L
        val modified = if (exists) runCatching { file.lastModified() }.getOrDefault(-1L) else -1L
        val key = ProbeKey(normalized, length, modified, fileKey(file), logicalName.lowercase(Locale.US))
        synchronized(probeCache) {
            probeCache[key]?.let { return it }
        }

        val state = when {
            !exists || !file.isFile -> FontFileState.MISSING
            isSymbolicLink(file) -> FontFileState.INVALID_FORMAT
            !isFontFile(logicalName) -> FontFileState.INVALID_FORMAT
            length <= 0L -> FontFileState.INVALID_FORMAT
            length > MAX_FONT_BYTES -> FontFileState.TOO_LARGE
            else -> {
                val opened = runCatching {
                    FileInputStream(file).use { input ->
                        val header = ByteArray(4)
                        var offset = 0
                        while (offset < header.size) {
                            val count = input.read(header, offset, header.size - offset)
                            if (count <= 0) break
                            offset += count
                        }
                        offset == header.size
                    }
                }.getOrElse { false }
                if (!opened) {
                    FontFileState.UNREADABLE
                } else {
                    runCatching {
                        Typeface.createFromFile(file)
                        FontFileState.READY
                    }.getOrDefault(FontFileState.INVALID_FORMAT)
                }
            }
        }
        // Permission changes and files appearing after a manual copy must be observed on the
        // next refresh, so transient states are deliberately not cached.
        if (state != FontFileState.UNREADABLE && state != FontFileState.MISSING) {
            synchronized(probeCache) {
                probeCache[key] = state
                while (probeCache.size > PROBE_CACHE_LIMIT) {
                    val iterator = probeCache.entries.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
        }
        return state
    }

    private fun normalizeDistinct(paths: List<String>): List<String> {
        val unique = LinkedHashSet<String>(paths.size)
        paths.forEach { path -> normalizePath(path).takeIf(String::isNotBlank)?.let(unique::add) }
        return unique.toList()
    }

    private fun saveIndex(context: Context, paths: List<String>): Boolean = SettingsRuntime.prefs(context).edit()
        .putString(SettingIds.KEY_CUSTOM_READER_FONT_LIST, paths.joinToString("\n"))
        .commit()

    private fun appendToIndex(context: Context, path: String): Boolean {
        val ordered = appendFontWithoutReordering(readFontEntries(context).map(FontEntry::path), path)
        val saved = saveIndex(context, ordered)
        if (saved) FontLibraryChanges.notifyChanged()
        return saved
    }

    private fun fontsDir(context: Context): File = File(applicationContext(context).filesDir, FONT_DIR)

    private fun trashDir(context: Context): File = File(fontsDir(context), TRASH_DIR)

    private fun stageForDeletion(context: Context, source: File): StagedFile? {
        val trash = trashDir(context)
        if ((!trash.exists() && !trash.mkdirs()) || !trash.isDirectory || isSymbolicLink(trash)) return null
        val batch = File(trash, "delete-${System.nanoTime()}")
        if (!batch.mkdirs()) return null
        val staged = File(batch, source.name)
        return if (moveFile(source, staged)) StagedFile(source, staged, batch) else {
            deleteTree(batch)
            null
        }
    }

    private fun deleteStagedFile(staged: StagedFile): Boolean {
        val deleted = (!staged.staged.exists() || staged.staged.delete()) && deleteTree(staged.batch)
        if (!deleted) RuntimeLog.w("Font library pending delete remains: ${staged.staged.name}")
        return deleted
    }

    private fun restoreStagedFile(staged: StagedFile): Boolean {
        if (!staged.staged.exists()) return true
        val restored = moveFile(staged.staged, staged.original)
        if (!restored) RuntimeLog.w("Font library delete rollback failed: ${staged.original.name}")
        return restored
    }

    private fun discardStagedBatch(batch: File) {
        if (!deleteTree(batch)) RuntimeLog.w("Font library rollback staging remains: ${batch.name}")
    }

    private fun markStagedCommitted(staged: StagedFile) {
        runCatching { File(staged.batch, COMMITTED_MARKER).createNewFile() }
            .onFailure { throwable ->
                RuntimeLog.w("Font library commit marker failed: ${throwable.javaClass.name}")
            }
    }

    private fun listManagedFilesForClear(context: Context): List<File>? {
        val dir = fontsDir(context)
        if (!dir.exists()) return emptyList()
        if (!dir.isDirectory || isSymbolicLink(dir)) return null
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return null
        return children.filter { file ->
            file.isFile && !isSymbolicLink(file) &&
                (isFontFile(file.name) || isTemporaryName(file.name))
        }
    }

    private fun stageFilesForClear(context: Context, files: List<File>): StageBatchResult {
        if (files.isEmpty()) return StageBatchResult(emptyList())
        val parent = trashDir(context)
        val batchDir = File(parent, "clear-${System.nanoTime()}")
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory || isSymbolicLink(parent) || !batchDir.mkdirs()) {
            return StageBatchResult.Failed
        }
        val staged = mutableListOf<StagedFile>()
        for (source in files) {
            val target = File(batchDir, source.name)
            if (!moveFile(source, target)) {
                staged.asReversed().forEach(::restoreStagedFile)
                deleteTree(batchDir)
                return StageBatchResult.Failed
            }
            staged += StagedFile(source, target, batchDir)
        }
        return StageBatchResult(staged, batchDir)
    }

    private fun cleanupTrash(context: Context): Boolean {
        val trash = trashDir(context)
        if (!trash.exists()) return true
        if (!trash.isDirectory || isSymbolicLink(trash)) return false
        val children = runCatching { trash.listFiles() }.getOrNull() ?: return false
        var success = true
        children.forEach { child ->
            if (child.isDirectory && !isSymbolicLink(child) && !File(child, COMMITTED_MARKER).isFile) {
                if (!restoreTrashBatch(context, child)) success = false
            } else if (!deleteTree(child)) {
                success = false
            }
        }
        if (success && trash.exists()) trash.delete()
        return success && !trash.exists()
    }

    private fun restoreTrashBatch(context: Context, batch: File): Boolean {
        val children = runCatching { batch.listFiles() }.getOrNull() ?: return false
        var success = true
        children.filterNot { it.name == COMMITTED_MARKER }.forEach { staged ->
            if (isSymbolicLink(staged)) {
                success = false
                return@forEach
            }
            val target = File(fontsDir(context), staged.name)
            if (target.exists() || !moveFile(staged, target)) success = false
        }
        return success && deleteTree(batch)
    }

    private fun deleteTree(file: File): Boolean {
        if (isSymbolicLink(file)) return false
        if (file.isDirectory) {
            val children = runCatching { file.listFiles() }.getOrNull() ?: return false
            var success = true
            children.forEach { child -> if (!deleteTree(child)) success = false }
            if (!success) return false
        }
        return !file.exists() || file.delete()
    }

    private fun isTemporaryName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower == TRASH_DIR || lower.endsWith(PART_SUFFIX) || lower.endsWith(TEMP_SUFFIX)
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        Files.isSymbolicLink(file.toPath())
    }.getOrElse {
        runCatching { file.exists() && file.canonicalPath != file.absolutePath }.getOrDefault(false)
    }

    private fun fileKey(file: File): String? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()?.toString()
    }.getOrNull()

    private fun moveFile(source: File, target: File): Boolean {
        if (target.exists()) return false
        if (source.renameTo(target)) return true
        return runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        }.recoverCatching {
            Files.move(source.toPath(), target.toPath())
            true
        }.getOrDefault(false)
    }

    private fun copyUriToFile(context: Context, uri: Uri, target: File) {
        var copied = 0L
        val input = context.contentResolver.openInputStream(uri)
            ?: throw FontImportException("无法读取字体文件")
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    copied += read.toLong()
                    if (copied > MAX_FONT_BYTES) {
                        throw FontImportException("字体文件过大")
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
                runCatching { output.fd.sync() }
            }
        }
        if (!target.isFile || target.length() <= 0L) {
            throw FontImportException("字体文件为空")
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun uniqueTarget(dir: File, fileName: String): File {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var candidate = File(dir, fileName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_${index}$ext")
            index += 1
        }
        return candidate
    }

    private fun safeFontFileName(name: String): String {
        val fallback = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "custom_font.ttf" }
        return fallback.map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch == '.' || ch == '_' || ch == '-' || ch == ' ' -> ch
                else -> '_'
            }
        }.joinToString("").trim().take(96)
    }

    private fun ensureFontExtension(name: String, mimeType: String?): String {
        if (isFontFile(name)) return name
        val extension = when (mimeType?.lowercase(Locale.US)) {
            "font/otf",
            "application/x-font-otf",
            "application/x-font-opentype",
            "application/vnd.ms-opentype" -> ".otf"
            "font/collection",
            "application/x-font-ttc",
            "application/x-font-collection" -> ".ttc"
            "font/woff",
            "application/font-woff",
            "application/x-font-woff" -> ".woff"
            "font/woff2",
            "application/font-woff2",
            "application/x-font-woff2" -> ".woff2"
            else -> ".ttf"
        }
        val base = name.substringBeforeLast('.', name).ifBlank { "custom_font" }
        return "$base$extension"
    }

    private data class ProbeKey(
        val path: String,
        val length: Long,
        val lastModified: Long,
        val fileKey: String?,
        val logicalName: String,
    )

    private data class StagedFile(val original: File, val staged: File, val batch: File)

    private sealed class StageBatchResult {
        abstract fun restore()
        abstract fun markCommitted()
        abstract fun delete(): Boolean

        data object Failed : StageBatchResult() {
            override fun restore() = Unit
            override fun markCommitted() = Unit
            override fun delete(): Boolean = false
        }

        class Success(
            private val files: List<StagedFile>,
            private val directory: File? = null,
        ) : StageBatchResult() {
            override fun restore() {
                files.asReversed().forEach(::restoreStagedFile)
                directory?.let(::discardStagedBatch)
            }

            override fun markCommitted() {
                files.firstOrNull()?.let(::markStagedCommitted)
            }

            override fun delete(): Boolean {
                var success = files.all(::deleteStagedFile)
                directory?.let { if (!deleteTree(it)) success = false }
                return success
            }
        }

        companion object {
            operator fun invoke(files: List<StagedFile>, directory: File? = null): StageBatchResult =
                Success(files, directory)
        }
    }

    private class FontImportException(message: String) : Exception(message)
}

internal data class FontFileSignature(
    val path: String,
    val length: Long,
    val lastModified: Long,
    val fileKey: String?,
)

/** Pure ordering/normalization rule shared by the preference and directory views. */
internal fun mergeFontPaths(
    indexed: List<String>,
    referenced: List<String>,
    discovered: List<String>,
): List<String> {
    val unique = LinkedHashSet<String>(indexed.size + referenced.size + discovered.size)
    (indexed + referenced + discovered).forEach { path ->
        FontLibraryStore.normalizePath(path).takeIf(String::isNotBlank)?.let(unique::add)
    }
    return unique.toList()
}

internal fun appendFontWithoutReordering(existing: List<String>, path: String): List<String> {
    val target = FontLibraryStore.normalizePath(path)
    val ordered = LinkedHashSet<String>(existing.size + 1)
    existing.forEach { value -> FontLibraryStore.normalizePath(value).takeIf(String::isNotBlank)?.let(ordered::add) }
    if (target.isNotBlank()) ordered += target
    return ordered.toList()
}

sealed class FontImportResult {
    data class Success(val file: File) : FontImportResult()
    data class Failure(val message: String) : FontImportResult()
}

data class FontClearResult(
    val settingsCleared: Boolean,
    val filesCleared: Boolean,
)

data class FontDeleteResult(
    val settingsUpdated: Boolean,
    val fileDeleted: Boolean,
    val clearedRoles: Set<FontRole>,
)
