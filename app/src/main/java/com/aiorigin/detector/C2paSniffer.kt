package com.aiorigin.detector

/**
 * Detects an embedded C2PA / Content Credentials manifest and reads a few plain-text
 * hints from it (digital source type, generator names).
 *
 * This does NOT validate signatures or the trust chain. Results are labeled unverified:
 * a manifest can be copied, stripped or forged without cryptographic verification.
 */
object C2paSniffer {
    data class Hint(
        val aiGenerated: Boolean,
        val aiEdited: Boolean,
        val generators: List<String>
    )

    private const val MAX_SCAN = 8_000_000
    private val generated = Regex("(?<!compositewith)trainedalgorithmicmedia", RegexOption.IGNORE_CASE)
    private val edited = Regex("compositewithtrainedalgorithmicmedia", RegexOption.IGNORE_CASE)
    private val jsonGenerator = Regex("\"claim_generator\"\\s*:\\s*\"([^\"]{2,120})\"")
    private val generatorKeys = setOf("claim_generator", "claim_generator_info", "softwareAgent")

    fun inspect(bytes: ByteArray): Hint? {
        val data = if (bytes.size > MAX_SCAN) bytes.copyOf(MAX_SCAN) else bytes
        val text = String(data, Charsets.ISO_8859_1)
        if (!text.contains("c2pa", ignoreCase = true)) return null
        val isEdited = edited.containsMatchIn(text)
        val isGenerated = generated.containsMatchIn(text)

        val names = linkedSetOf<String>()
        jsonGenerator.findAll(text).forEach { names.add(it.groupValues[1]) }
        val tokens = cborTextStrings(data)
        for (i in tokens.indices) {
            if (tokens[i] !in generatorKeys) continue
            // The value is a text string, or a map whose "name" entry holds the text.
            val next = tokens.getOrNull(i + 1) ?: continue
            if (next != "name" && next !in generatorKeys) { names.add(next); continue }
            val nameAt = (i + 1..minOf(i + 4, tokens.lastIndex)).firstOrNull { tokens[it] == "name" } ?: continue
            tokens.getOrNull(nameAt + 1)?.let { names.add(it) }
        }
        val cleaned = names.map { it.trim().take(120) }
            .filter { it.length >= 2 && !it.startsWith("c2pa") && it != "name" && it !in generatorKeys }
            .distinct().take(6)
        return Hint(isGenerated, isEdited && !isGenerated, cleaned)
    }

    fun toMetadata(hint: Hint): Map<String, String> = buildMap {
        put("c2pa.present", "true")
        when {
            hint.aiGenerated -> put("c2pa.digital_source_type", "trainedAlgorithmicMedia")
            hint.aiEdited -> put("c2pa.digital_source_type", "compositeWithTrainedAlgorithmicMedia")
        }
        if (hint.generators.isNotEmpty()) put("c2pa.generator", hint.generators.joinToString(", "))
    }

    /**
     * Lenient scan for CBOR major-type-3 (text string) items with definite length up to 65535.
     * Only strings that decode to printable text are kept. Map/array structure is not parsed.
     */
    private fun cborTextStrings(data: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < data.size && out.size < 100_000) {
            val b = data[i].toInt() and 0xff
            val (length, headerSize) = when (b) {
                in 0x60..0x77 -> (b - 0x60) to 1
                0x78 -> if (i + 1 < data.size) (data[i + 1].toInt() and 0xff) to 2 else -1 to 1
                0x79 -> if (i + 2 < data.size) (((data[i + 1].toInt() and 0xff) shl 8) or (data[i + 2].toInt() and 0xff)) to 3 else -1 to 1
                else -> -1 to 1
            }
            if (length >= 2 && i + headerSize + length <= data.size) {
                val start = i + headerSize
                val candidate = String(data, start, length, Charsets.UTF_8)
                if (candidate.none { it == '�' || (it < ' ' && it != '\n') }) {
                    out.add(candidate)
                    i = start + length
                    continue
                }
            }
            i += 1
        }
        return out
    }
}
