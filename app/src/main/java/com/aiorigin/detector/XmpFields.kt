package com.aiorigin.detector

/**
 * Text-only XMP field extraction shared by the JPEG, PNG and WebP readers.
 * No XML parser, entity expansion or external resource resolution is used.
 * XMP is editable and never proves provenance.
 */
object XmpFields {
    private const val MAX_TEXT = 200_000
    private const val NAMES = "prompt|negative_prompt|description|software|creatortool|digitalsourcetype|parameters"

    // Double- and single-quoted attribute values are matched separately so an apostrophe
    // inside a double-quoted prompt ("artist's studio") does not truncate the value.
    private val attributes = Regex(
        "(?:[A-Za-z_][\\w.-]*:)?($NAMES)\\s*=\\s*(?:\"([^\"]{1,100000})\"|'([^']{1,100000})')",
        RegexOption.IGNORE_CASE
    )

    // rdf:Description is the XMP container element, not a description field.
    private val elements = Regex(
        "<((?!rdf:)(?:[A-Za-z_][\\w.-]*:)?($NAMES))\\b[^>]*>(.{1,200000}?)</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val tag = Regex("<[^>]+>")
    private val space = Regex("\\s+")

    fun looksLikeXmp(text: String): Boolean =
        text.contains("x:xmpmeta", ignoreCase = true) || text.contains("<rdf:RDF", ignoreCase = true)

    fun extract(xml: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        if (xml.length > 4_000_000) return result
        for (m in attributes.findAll(xml)) {
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            put(result, m.groupValues[1], value)
        }
        for (m in elements.findAll(xml)) {
            val inner = m.groupValues[3].replace(tag, " ").replace(space, " ").trim()
            put(result, m.groupValues[2], inner)
        }
        return result
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
