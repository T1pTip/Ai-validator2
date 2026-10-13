package com.aiorigin.detector

/**
 * Text-only XMP field extraction shared by the JPEG, PNG and WebP readers.
 * No XML parser, entity expansion or external resource resolution is used.
 * XMP is editable and never proves provenance.
 */
object XmpFields {
    private const val MAX_TEXT = 200_000
    private const val NAMES = "prompt|negative_prompt|description|software|creatortool|digitalsourcetype|parameters"

    /** Real XMP packets are a few KB; anything larger is truncated before scanning. */
    private const val MAX_XMP = 512_000
    private val nameSet = NAMES.split('|').toSet()

    // Every pattern below is bounded and backtracking-free; closing quotes/tags are found with
    // memoised indexOf so a crafted packet full of unclosed tags stays linear-time.
    private val attributeStart = Regex(
        "(?:[A-Za-z_][\\w.-]{0,40}:)?($NAMES)\\s{0,20}=\\s{0,20}([\"'])",
        RegexOption.IGNORE_CASE
    )
    // rdf:Description is the XMP container element, not a description field.
    private val openTag = Regex(
        "<((?:[A-Za-z_][\\w.-]{0,40}:)?($NAMES))\\b[^<>]{0,2000}>",
        RegexOption.IGNORE_CASE
    )
    private val tag = Regex("<[^<>]{0,2000}>")
    private val space = Regex("\\s+")

    fun looksLikeXmp(text: String): Boolean =
        text.contains("x:xmpmeta", ignoreCase = true) || text.contains("<rdf:RDF", ignoreCase = true)

    fun extract(input: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val xml = if (input.length > MAX_XMP) input.substring(0, MAX_XMP) else input

        val nextQuote = HashMap<Char, Int>()
        for (m in attributeStart.findAll(xml)) {
            checkInterrupted()
            val quote = m.groupValues[2][0]
            val from = m.range.last + 1
            var close = nextQuote[quote]
            if (close == null || (close in 0 until from)) {
                close = xml.indexOf(quote, from)
                nextQuote[quote] = close
            }
            if (close < 0) continue
            if (close - from in 1..100_000) put(result, m.groupValues[1], xml.substring(from, close))
        }

        val nextClose = HashMap<String, Int>()
        for (m in openTag.findAll(xml)) {
            checkInterrupted()
            val qualified = m.groupValues[1].lowercase()
            if (qualified.startsWith("rdf:") || m.groupValues[2].lowercase() !in nameSet) continue
            val from = m.range.last + 1
            var close = nextClose[qualified]
            if (close == null || (close in 0 until from)) {
                close = xml.indexOf("</$qualified", from, ignoreCase = true)
                nextClose[qualified] = close
            }
            if (close < 0 || close - from > 200_000) continue
            val inner = xml.substring(from, close).replace(tag, " ").replace(space, " ").trim()
            put(result, m.groupValues[2], inner)
        }
        return result
    }

    /** Scanning runs inside runInterruptible; honour Cancel between matches. */
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private fun put(result: MutableMap<String, String>, rawName: String, rawValue: String) {
        val value = decodeEntities(rawValue).trim()
        if (value.isEmpty()) return
        val key = when (rawName.lowercase()) {
            "digitalsourcetype" -> "digital_source_type"
            else -> rawName.lowercase()
        }
        result.putIfAbsent(key, value.take(MAX_TEXT))
    }

    fun decodeEntities(value: String) = value.replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
