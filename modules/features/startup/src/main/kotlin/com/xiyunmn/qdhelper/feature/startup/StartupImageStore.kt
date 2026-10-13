package com.xiyunmn.qdhelper.feature.startup

import android.content.Context
import android.util.AtomicFile
import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import com.xiyunmn.qdhelper.foundation.runtime.HostSessionStore
import com.xiyunmn.qdhelper.host.api.target.HostTargetResolver
import com.xiyunmn.qdhelper.host.api.target.StartupTargetKeys
import com.xiyunmn.qdhelper.shared.io.HttpRequest
import com.xiyunmn.qdhelper.shared.io.HttpStatusException
import com.xiyunmn.qdhelper.shared.io.HttpTransport
import com.xiyunmn.qdhelper.shared.io.SizeLimitExceededException
import com.xiyunmn.qdhelper.shared.io.copyBoundedTo
import com.xiyunmn.qdhelper.config.SettingsRuntime
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.net.URL
import java.util.Locale
import java.util.zip.CRC32
import java.util.concurrent.CancellationException

object StartupImageStore {
    private const val PAPER_ID_BASE = 900_000_000_000L
    private const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L

    fun paperIdForUrl(url: String): Long {
        val crc = CRC32()
        crc.update(url.toByteArray(Charsets.UTF_8))
        return PAPER_ID_BASE + crc.value
    }

    data class SplashTarget(
        val url: String,
        val paperId: Long,
        val suffix: String,
        val source: SplashSource,
        val darkLogo: Boolean,
    ) {
        fun sameImage(other: SplashTarget?): Boolean = other != null &&
            url == other.url && paperId == other.paperId && suffix == other.suffix && source == other.source
    }

    enum class SplashSource {
        Official,
        CustomUrl,
        LocalFile,
    }

    /**
     * 当前生效的启动图目标——注入（buildCustomBootWallPapers）与同步（sync）的单一真源，保证写入缓存的
     * 文件名 paperId 与注入 Paper 的 paperId 完全一致（修官方 Paper ID 导致文件名/注入不匹配的 bug）。
     *
     * 三个手动源（相册选图 / 自定义直链 / 手动官方）互斥；宿主页面本地选择官方图时会原子关闭
     * 三个手动源。此处按相册 > 自定义直链 > 本地选择官方 > 手动官方选首个有效源，其余忽略。
     * 返回 null 表示当前无有效启动图配置（未开启，或路径/直链为空、无图片扩展名）。
     */
    fun activeSplashTarget(context: Context?): SplashTarget? {
        val localPath = SettingsRuntime.localSplashImagePath(context).trim()
        if (SettingsRuntime.isLocalSplashImageEnabled(context) && localPath.isNotBlank()) {
            val suffix = hostSuffixOrNull(localPath)
            if (suffix != null && File(localPath).isFile) {
                return SplashTarget(
                    localPath,
                    paperIdForUrl(localPath),
                    suffix,
                    SplashSource.LocalFile,
                    SettingsRuntime.isCustomSplashImageDarkLogoEnabled(context),
                )
            }
        }

        val customUrl = SettingsRuntime.customSplashImageUrl(context).trim()
        if (SettingsRuntime.isCustomSplashImageEnabled(context) && customUrl.isNotBlank()) {
            val suffix = hostSuffixOrNull(customUrl)
            if (suffix != null) {
                val isLocal = customUrl.startsWith("/") || File(customUrl).isAbsolute
                val source = if (isLocal) SplashSource.LocalFile else SplashSource.CustomUrl
                return SplashTarget(
                    customUrl,
                    paperIdForUrl(customUrl),
                    suffix,
                    source,
                    SettingsRuntime.isCustomSplashImageDarkLogoEnabled(context),
                )
            }
        }

        val localOfficialUrl = SettingsRuntime.localOfficialSplashImageUrl(context).trim()
        val localOfficialPaperId = SettingsRuntime.localOfficialSplashImagePaperId(context)
        if (
            SettingsRuntime.isOfficialSplashImageLocalUnlockEnabled(context) &&
            isValidOfficialSelection(localOfficialPaperId, localOfficialUrl)
        ) {
            val suffix = requireNotNull(hostSuffixOrNull(localOfficialUrl))
            return SplashTarget(
                localOfficialUrl,
                requireNotNull(localOfficialPaperId),
                suffix,
                SplashSource.Official,
                SettingsRuntime.isLocalOfficialSplashImageDarkLogoEnabled(context),
            )
        }

        val officialUrl = SettingsRuntime.officialSplashImageUrl(context).trim()
        if (SettingsRuntime.isOfficialSplashImageEnabled(context) && officialUrl.isNotBlank()) {
            val suffix = hostSuffixOrNull(officialUrl)
            if (suffix != null) {
                val paperId = SettingsRuntime.officialSplashImagePaperId(context) ?: paperIdForUrl(officialUrl)
                return SplashTarget(
                    officialUrl,
                    paperId,
                    suffix,
                    SplashSource.Official,
                    SettingsRuntime.isCustomSplashImageDarkLogoEnabled(context),
                )
            }
        }

        return null
    }

    internal fun isValidOfficialSelection(paperId: Long?, imageUrl: String): Boolean {
        return paperId != null && paperId > 0L && hostSuffixOrNull(imageUrl) != null
    }

    internal fun isSameOfficialSelection(
        storedPaperId: Long?,
        storedImageUrl: String,
        candidatePaperId: Long,
    ): Boolean {
        return candidatePaperId > 0L &&
            storedPaperId == candidatePaperId &&
            isValidOfficialSelection(storedPaperId, storedImageUrl)
    }

    fun hostSuffixOrNull(url: String): String? {
        val value = url.trim().substringBefore('?').substringBefore('#')
        val lastDot = value.lastIndexOf('.')
        val lastSlash = maxOf(value.lastIndexOf('/'), value.lastIndexOf('\\'))
        if (lastDot <= lastSlash || lastDot == value.lastIndex) {
            return null
        }
        val suffix = value.substring(lastDot).lowercase(Locale.US)
        return suffix.takeIf { it in ALLOWED_IMAGE_SUFFIXES }
    }

    internal fun sourceOrNull(value: String): StartupImageSource? {
        val trimmed = value.trim()
        val suffix = hostSuffixOrNull(trimmed) ?: return null
        if (trimmed.startsWith("file://", ignoreCase = true)) {
            val file = runCatching { File(URL(trimmed).toURI()) }.getOrNull() ?: return null
            return StartupImageSource.Local(file, suffix, trimmed)
        }
        val localFile = File(trimmed)
        if (localFile.isFile) {
            return StartupImageSource.Local(localFile, suffix, trimmed)
        }
        val protocol = runCatching { URL(trimmed).protocol.lowercase(Locale.US) }.getOrNull()
        if (protocol == "http" || protocol == "https") {
            return StartupImageSource.Remote(trimmed, suffix)
        }
        return null
    }

    internal fun sync(context: Context, active: SplashTarget, isCurrent: () -> Boolean): SyncResult {
        return runCatching {
            checkCurrent(isCurrent)
            val cacheDir = splashCacheDir(context)
            val targetFile = File(cacheDir, "${active.paperId}${active.suffix}")
            targetFile.parentFile?.let { parent ->
                if (!parent.isDirectory) {
                    check(parent.mkdirs()) { "无法创建启动图缓存目录" }
                }
            }

            when (active.source) {
                SplashSource.LocalFile -> {
                    val sourceFile = File(active.url)
                    if (!sourceFile.isFile) {
                        return SyncResult(false, "本地文件不存在：${active.url}")
                    }
                    copyFile(sourceFile, targetFile, isCurrent)
                }
                SplashSource.CustomUrl, SplashSource.Official -> {
                    val source = sourceOrNull(active.url)
                        ?: return SyncResult(false, "启动图链接需要使用 http/https/file 图片直链")
                    when (source) {
                        is StartupImageSource.Local -> copyFile(source.file, targetFile, isCurrent)
                        is StartupImageSource.Remote -> download(source.url, targetFile, isCurrent)
                    }
                }
            }
            SyncResult(true, "启动图已同步")
        }.getOrElse { throwable ->
            if (throwable is CancellationException) SyncResult(false, "同步已取消", cancelled = true)
            else SyncResult(false, "同步失败：${throwable.message ?: throwable.javaClass.simpleName}")
        }
    }

    fun splashCacheDir(context: Context): String {
        val method = HostTargetResolver.resolveEarlyMethod(
            context = context,
            classLoader = context.classLoader,
            processName = HostSessionStore.processName,
            key = StartupTargetKeys.splashDownloadCacheDir,
        ) ?: error("SplashDownloadUtil cache-dir method missing")
        val ownerClass = method.declaringClass
        val instanceField = FeatureHooks.allDeclaredFields(ownerClass).firstOrNull { field ->
            Modifier.isStatic(field.modifiers) && ownerClass.isAssignableFrom(field.type)
        } ?: error("SplashDownloadUtil instance missing")
        val instance = FeatureHooks.read(instanceField, null) ?: error("SplashDownloadUtil instance is null")
        val path = FeatureHooks.invoke(method, instance) as? String
        if (path.isNullOrBlank()) {
            error("Splash cache path is empty")
        }
        return path
    }

    private fun download(value: String, target: File, isCurrent: () -> Boolean) {
        try {
            val response = HttpTransport.execute(
                HttpRequest(
                    url = value,
                    maxResponseBytes = MAX_IMAGE_BYTES,
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 15_000,
                ),
            )
            if (isRejectedRemoteContentType(response.contentType)) {
                error("response is not an image")
            }
            writeAtomically(target, isCurrent) { output -> output.write(response.body) }
        } catch (_: SizeLimitExceededException) {
            error("image too large")
        } catch (exception: HttpStatusException) {
            error("HTTP ${exception.statusCode}")
        }
    }

    private fun copyFile(source: File, target: File, isCurrent: () -> Boolean) {
        if (!source.isFile) {
            error("source file missing")
        }
        if (source.length() > MAX_IMAGE_BYTES) {
            error("image too large")
        }
        if (source.canonicalPath == target.canonicalPath) {
            checkCurrent(isCurrent)
            return
        }
        FileInputStream(source).use { input ->
            writeAtomically(target, isCurrent) { output ->
                try {
                    input.copyBoundedTo(output, MAX_IMAGE_BYTES)
                } catch (_: SizeLimitExceededException) {
                    error("image too large")
                }
            }
        }
    }

    internal fun writeAtomically(target: File, isCurrent: () -> Boolean, write: (FileOutputStream) -> Unit) {
        // AtomicFile reuses a fixed .new path. The interned monitor also spans module ClassLoaders
        // while an old hot-reload worker is finishing, without holding a lock during downloading.
        synchronized(("qdhelper.startup-image:" + target.canonicalPath).intern()) {
            checkCurrent(isCurrent)
            val file = AtomicFile(target)
            val output = file.startWrite()
            try {
                write(output)
                checkCurrent(isCurrent)
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
        }
    }

    private fun checkCurrent(isCurrent: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || !isCurrent()) throw CancellationException("Splash selection changed")
    }

    internal fun isRejectedRemoteContentType(contentType: String?): Boolean {
        val normalized = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        if (normalized.isBlank() || normalized.startsWith("image/")) {
            return false
        }
        return normalized.startsWith("text/") ||
            normalized in REJECTED_REMOTE_CONTENT_TYPES
    }

    private val ALLOWED_IMAGE_SUFFIXES = setOf(
        ".jpg",
        ".jpeg",
        ".png",
        ".webp",
        ".gif",
    )
    private val REJECTED_REMOTE_CONTENT_TYPES = setOf(
        "application/json",
        "application/xml",
        "application/xhtml+xml",
        "application/javascript",
    )
}

internal sealed class StartupImageSource(
    open val suffix: String,
    open val identity: String,
) {
    data class Local(
        val file: File,
        override val suffix: String,
        override val identity: String,
    ) : StartupImageSource(suffix, identity)

    data class Remote(
        val url: String,
        override val suffix: String,
    ) : StartupImageSource(suffix, url)
}

data class SyncResult(
    val success: Boolean,
    val message: String,
    val cancelled: Boolean = false,
)
