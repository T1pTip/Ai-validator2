package com.aiorigin.detector

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Defensive PNG text reader. Verifies per-chunk CRC, bounds chunk size and inflated output.
 * Image data chunks are skipped without buffering, so text chunks written after large
 * IDAT data are still found. No Android dependencies: can be tested with the Kotlin CLI.
 */
object PngMetadataReader {
    private const val MAX_CHUNK = 2_000_000
    private const val MAX_TOTAL = 512L * 1024 * 1024
    private const val MAX_TEXT = 200_000
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val readTypes = setOf("tEXt", "zTXt", "iTXt", "caBX")

    fun read(stream: InputStream): Map<String, String> {
        val found = linkedMapOf<String, String>()
        if (readExactly(stream, 8)?.contentEquals(signature) != true) return found
        var total = 8L
        while (total < MAX_TOTAL) {
            val lengthBytes = readExactly(stream, 4) ?: break
            val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).int
            if (length < 0 || length.toLong() + total + 12 > MAX_TOTAL) break
            val typeBytes = readExactly(stream, 4) ?: break
            val type = String(typeBytes, Charsets.US_ASCII)
            total += length.toLong() + 12
            if (type == "IEND") break
            if (type !in readTypes || length > MAX_CHUNK) {
                if (!skipExactly(stream, length.toLong() + 4)) break
                continue
            }
            val payload = readExactly(stream, length) ?: break
            val crcBytes = readExactly(stream, 4) ?: break
            val crc = CRC32().apply { update(typeBytes); update(payload) }.value
            val expected = ByteBuffer.wrap(crcBytes).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            if (crc != expected) continue
            if (type == "caBX") {
                C2paSniffer.inspect(payload)?.let { found.putAll(C2paSniffer.toMetadata(it)) }
                continue
            }
            val zero = payload.indexOf(0)
            if (zero !in 1..79) continue
            val key = String(payload, 0, zero, Charsets.ISO_8859_1)
            val text = when (type) {
                "tEXt" -> payload.copyOfRange(zero + 1, payload.size)
                    .takeIf { it.size <= MAX_TEXT }?.toString(Charsets.ISO_8859_1)
                "zTXt" -> if (zero + 2 <= payload.size && payload[zero + 1] == 0.toByte())
                    inflate(payload.copyOfRange(zero + 2, payload.size))
                        ?.toString(Charsets.ISO_8859_1) else null
                else -> {
                    if (zero + 3 > payload.size) null else {
                        val flag = payload[zero + 1].toInt() and 255
                        val method = payload[zero + 2].toInt() and 255
                        if (flag !in 0..1 || method != 0) null else {
                            var pos = zero + 3
                            while (pos < payload.size && payload[pos] != 0.toByte()) pos++
                            pos++
                            while (pos < payload.size && payload[pos] != 0.toByte()) pos++
                            pos++
                            if (pos > payload.size) null else {
                                val bytes = payload.copyOfRange(pos, payload.size)
                                (if (flag == 1) inflate(bytes) else bytes.takeIf { it.size <= MAX_TEXT })
                                    ?.toString(Charsets.UTF_8)
                            }
                        }
                    }
                }
            }
            if (text != null && found.size < 64) found[key] = text
        }
        return found
    }

    private fun inflate(data: ByteArray): ByteArray? = try {
        InflaterInputStream(ByteArrayInputStream(data)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size() + n > MAX_TEXT) return null
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    } catch (_: Exception) { null }

    private fun readExactly(stream: InputStream, length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = stream.read(bytes, offset, length - offset)
            if (n <= 0) return null
            offset += n
        }
        return bytes
    }

    private fun skipExactly(stream: InputStream, amount: Long): Boolean {
        var left = amount
        val buffer = ByteArray(8192)
        while (left > 0) {
            if (Thread.currentThread().isInterrupted) return false
            val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n <= 0) return false
            left -= n
        }
        return true
    }
}
