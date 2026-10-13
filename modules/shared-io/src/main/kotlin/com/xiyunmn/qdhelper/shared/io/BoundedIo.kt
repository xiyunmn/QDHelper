package com.xiyunmn.qdhelper.shared.io

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SizeLimitExceededException(val maxBytes: Long) : IOException("Input exceeds $maxBytes bytes")

fun InputStream.readBytesBounded(maxBytes: Long): ByteArray {
    require(maxBytes >= 0L)
    val initialSize = minOf(maxBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt().coerceAtLeast(32)
    val output = ByteArrayOutputStream(initialSize)
    copyBoundedTo(output, maxBytes)
    return output.toByteArray()
}

fun InputStream.copyBoundedTo(output: OutputStream, maxBytes: Long): Long {
    require(maxBytes >= 0L)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (total < maxBytes) {
        val requested = minOf(buffer.size.toLong(), maxBytes - total).toInt()
        val count = read(buffer, 0, requested)
        if (count < 0) return total
        if (count == 0) continue
        total += count
        output.write(buffer, 0, count)
    }
    if (read() >= 0) throw SizeLimitExceededException(maxBytes)
    return total
}
