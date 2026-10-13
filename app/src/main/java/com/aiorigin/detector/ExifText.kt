package com.aiorigin.detector

/**
 * Decodes the EXIF UserComment value, whose first 8 bytes name its character code.
 * AUTOMATIC1111 (via piexif) writes "UNICODE\0" followed by UTF-16 text; a plain ASCII
 * decode would interleave NULs and hide the generation parameters.
 */
object ExifText {
    private const val MAX_BYTES = 400_000

    fun userComment(raw: ByteArray?): String? {
        if (raw == null || raw.isEmpty()) return null
        val bytes = if (raw.size > MAX_BYTES) raw.copyOf(MAX_BYTES) else raw
        val prefix = if (bytes.size >= 8) String(bytes, 0, 8, Charsets.ISO_8859_1) else ""
        val text = when {
            prefix.startsWith("UNICODE") -> utf16(bytes, 8)
            prefix.startsWith("ASCII") -> String(bytes, 8, bytes.size - 8, Charsets.US_ASCII)
            prefix == "\u0000".repeat(8) -> String(bytes, 8, bytes.size - 8, Charsets.UTF_8)
            prefix.startsWith("JIS") -> null
            else -> String(bytes, Charsets.UTF_8)
        } ?: return null
        return text.trim('\u0000', ' ').takeIf { it.isNotBlank() }
    }

    /** piexif writes big-endian; some tools write little-endian. Pick by where the NULs fall. */
    private fun utf16(bytes: ByteArray, offset: Int): String {
        val start = offset
        val count = bytes.size - start
        if (count <= 0) return ""
        val bom = if (count >= 2) ((bytes[start].toInt() and 0xff) shl 8) or (bytes[start + 1].toInt() and 0xff) else 0
        if (bom == 0xfeff) return String(bytes, start + 2, count - 2, Charsets.UTF_16BE)
        if (bom == 0xfffe) return String(bytes, start + 2, count - 2, Charsets.UTF_16LE)
        var evenZero = 0
        var oddZero = 0
        var k = start
        val end = minOf(bytes.size - 1, start + 4_000)
        while (k < end) {
            if (bytes[k].toInt() == 0) evenZero++
            if (bytes[k + 1].toInt() == 0) oddZero++
            k += 2
        }
        val length = count - (count % 2)
        return String(bytes, start, length, if (oddZero > evenZero) Charsets.UTF_16LE else Charsets.UTF_16BE)
    }
}
