package com.aiorigin.detector

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Bounded JPEG COM/XMP/C2PA extraction. Does not authenticate provenance. */
object JpegMetadataReader {
    private const val MAX_SEGMENT = 65_533
    private const val MAX_TOTAL = 16_000_000
    private const val MAX_TEXT = 200_000
    private const val MAX_C2PA = 4_000_000
    private val xmpPrefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)

    fun read(input: InputStream): Map<String, String> {
        val result = linkedMapOf<String, String>()
        if (input.read() != 0xff || input.read() != 0xd8) return result
        var consumed = 2
        var comments = 0
        val c2pa = ByteArrayOutputStream()
        while (consumed < MAX_TOTAL) {
            var markerStart = input.read()
            if (markerStart != 0xff) break
            consumed++
            do {
                markerStart = input.read()
                consumed++
            } while (markerStart == 0xff && consumed < MAX_TOTAL)
            if (markerStart < 0 || markerStart == 0xda || markerStart == 0xd9) break
            if (markerStart == 0x01 || markerStart in 0xd0..0xd7) continue
            val hi = input.read(); val lo = input.read()
            if (hi < 0 || lo < 0) break
            consumed += 2
            val length = hi * 256 + lo - 2
            if (length < 0 || length > MAX_SEGMENT || consumed + length > MAX_TOTAL) break
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(bytes, offset, length - offset)
                if (n <= 0) return finish(result, c2pa)
                offset += n
            }
            consumed += length
            if (markerStart == 0xfe && comments < 4) {
                val value = bytes.toString(StandardCharsets.UTF_8).trim('\u0000', ' ', '\n', '\r')
                if (value.isNotBlank()) result[if (comments == 0) "comment" else "comment_$comments"] = value.take(MAX_TEXT)
                comments++
            } else if (markerStart == 0xe1 && bytes.size > xmpPrefix.size && bytes.copyOfRange(0, xmpPrefix.size).contentEquals(xmpPrefix)) {
                val xml = bytes.copyOfRange(xmpPrefix.size, bytes.size).toString(StandardCharsets.UTF_8)
                for ((k, v) in XmpFields.extract(xml)) result.putIfAbsent(k, v)
                result.putIfAbsent("xmp", xml.take(MAX_TEXT))
            } else if (markerStart == 0xeb && c2pa.size() + bytes.size <= MAX_C2PA) {
                // APP11 carries JUMBF boxes; C2PA manifests may span several segments.
                c2pa.write(bytes)
            }
        }
        return finish(result, c2pa)
    }

    private fun finish(result: MutableMap<String, String>, c2pa: ByteArrayOutputStream): Map<String, String> {
        if (c2pa.size() > 0) C2paSniffer.inspect(c2pa.toByteArray())?.let { result.putAll(C2paSniffer.toMetadata(it)) }
        return result
    }
}
