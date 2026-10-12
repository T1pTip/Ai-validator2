package com.aiorigin.detector

import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Bounded RIFF/WebP XMP extractor. XMP is untrusted, and not provenance proof. */
object WebpMetadataReader {
    private const val MAX_CHUNK = 2 * 1024 * 1024
    private const val MAX_SCAN = 32L * 1024 * 1024
    private const val MAX_XMP = 256_000

    fun read(input: InputStream): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val header = readExact(input, 12) ?: return result
        if (!header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) ||
            !header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())) return result
        val riffSize = le32(header, 4)
        if (riffSize < 4) return result
        var remaining = minOf(riffSize - 4, MAX_SCAN)
        while (remaining >= 8) {
            val chunkHeader = readExact(input, 8) ?: break
            remaining -= 8
            val length = le32(chunkHeader, 4)
            val padded = length + (length and 1)
            if (length < 0 || padded > remaining) break
            val kind = String(chunkHeader, 0, 4, StandardCharsets.US_ASCII)
            if (kind == "XMP " && length <= MAX_CHUNK) {
                val bytes = readExact(input, length.toInt()) ?: break
                val text = String(bytes, StandardCharsets.UTF_8).trim('\u0000', ' ', '\n', '\r')
                if (text.isNotBlank()) result["xmp"] = text.take(MAX_XMP)
                if ((length and 1L) != 0L && !skipExact(input, 1)) break
            } else {
                if (!skipExact(input, padded)) break
            }
            remaining -= padded
        }
        return result
    }

    private fun le32(b: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, i -> value or ((b[offset + i].toLong() and 255L) shl (8 * i)) }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val bytes = ByteArray(count)
        var at = 0
        while (at < count) {
            val n = input.read(bytes, at, count - at)
            if (n <= 0) return null
            at += n
        }
        return bytes
    }

    private fun skipExact(input: InputStream, amount: Long): Boolean {
        var left = amount
        val buffer = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n <= 0) return false
            left -= n
        }
        return true
    }
}
