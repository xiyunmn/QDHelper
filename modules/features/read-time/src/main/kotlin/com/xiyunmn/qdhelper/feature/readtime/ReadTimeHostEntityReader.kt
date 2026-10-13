package com.xiyunmn.qdhelper.feature.readtime

import com.xiyunmn.qdhelper.feature.api.runtime.FeatureHooks
import java.lang.reflect.Field

internal class ReadTimeHostEntityReader(entityClass: Class<*>) {
    private val fields = FIELD_NAMES.associateWith { name -> FeatureHooks.findFieldOrNull(entityClass, name) }

    data class EncodingSnapshot(val available: Boolean, val value: Any?)

    fun details(entity: Any): ReadTimeEntityDetails = ReadTimeEntityDetails(
        dateStart = text(entity, "DateStart", 16),
        startTime = long(entity, "StartTime"),
        endTime = long(entity, "Endtime"),
        bookId = long(entity, "QDBookID"),
        bookName = text(entity, "QDBookName", 120),
        bookType = int(entity, "QDBookType"),
        startChapterId = long(entity, "StartChapterid"),
        endChapterId = long(entity, "EndChapterid"),
        chapterVip = int(entity, "ChapterVIP"),
        scrollMode = int(entity, "ScrollMode"),
        unlockStatus = int(entity, "UnlockStatus"),
        unlockReason = long(entity, "UnlockReason"),
        source = text(entity, "sp", 120),
        extraJson = text(entity, "extraJson", 400),
        userId = (read(entity, "UserID") as? Number)?.toLong()?.takeIf { it >= 0L },
    )

    fun captureEncoding(entity: Any): EncodingSnapshot {
        val field = fields["EncodeReadTime"] ?: return EncodingSnapshot(false, null)
        return EncodingSnapshot(true, runCatching { FeatureHooks.read(field, entity) }.getOrNull())
    }

    fun encodingPresent(entity: Any): Boolean =
        (read(entity, "EncodeReadTime") as? String).orEmpty().isNotBlank()

    fun restoreEncoding(entity: Any, snapshot: EncodingSnapshot): Boolean {
        if (!snapshot.available) return true
        val field = fields["EncodeReadTime"] ?: return false
        return FeatureHooks.write(field, entity, snapshot.value)
    }

    private fun read(entity: Any, name: String): Any? = fields[name]?.let { field ->
        runCatching { FeatureHooks.read(field, entity) }.getOrNull()
    }

    private fun long(entity: Any, name: String): Long = (read(entity, name) as? Number)?.toLong() ?: 0L

    private fun int(entity: Any, name: String): Int = (read(entity, name) as? Number)?.toInt() ?: 0

    private fun text(entity: Any, name: String, maxLength: Int): String? =
        (read(entity, name) as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(maxLength)

    private companion object {
        val FIELD_NAMES = listOf(
            "UserID",
            "DateStart",
            "StartTime",
            "Endtime",
            "QDBookID",
            "QDBookName",
            "QDBookType",
            "StartChapterid",
            "EndChapterid",
            "ChapterVIP",
            "ScrollMode",
            "UnlockStatus",
            "UnlockReason",
            "sp",
            "extraJson",
            "EncodeReadTime",
        )
    }
}

internal class ReadTimeReportedBatchReader(reportEntityClass: Class<*>) {
    private val bookId = field(reportEntityClass, "bookId")
    private val chapterId = field(reportEntityClass, "chapterId")
    private val startTime = field(reportEntityClass, "startTime")
    private val endTime = field(reportEntityClass, "endTime")
    private val totalTime = field(reportEntityClass, "totalTime")

    fun keys(batch: Any?): List<ReadTimeReportKey> = (batch as? Iterable<*>)
        ?.mapNotNull(::key)
        .orEmpty()

    private fun key(entity: Any?): ReadTimeReportKey? {
        entity ?: return null
        return ReadTimeReportKey(
            bookId = readLong(bookId, entity) ?: return null,
            endChapterId = readLong(chapterId, entity) ?: return null,
            startTime = readLong(startTime, entity) ?: return null,
            endTime = readLong(endTime, entity) ?: return null,
            totalMillis = readLong(totalTime, entity) ?: return null,
        )
    }

    private fun readLong(field: Field?, entity: Any): Long? =
        (field?.let { runCatching { FeatureHooks.read(it, entity) }.getOrNull() } as? Number)?.toLong()

    private fun field(clazz: Class<*>, name: String): Field? = FeatureHooks.findFieldOrNull(clazz, name)
}
