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

    private const val MAX_SCAN = 4_000_000
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

    /** Generator names and keys are short; longer strings are never needed here. */
    private const val MAX_STRING = 256

    /**
     * Lenient scan for CBOR major-type-3 (text string) items with definite length up to [MAX_STRING].
     * Only strings that are valid, printable UTF-8 are kept. Map/array structure is not parsed.
     * Linear time: a precomputed "next bad byte" table rejects most candidates in O(1), and the
     * UTF-8 check allocates only for accepted strings.
     */
    private fun cborTextStrings(data: ByteArray): List<String> {
        val n = data.size
        // nextBad[i] = first index >= i holding a control byte or a byte that never occurs in UTF-8.
        val nextBad = IntArray(n + 1)
        nextBad[n] = n
        for (k in n - 1 downTo 0) {
            val b = data[k].toInt() and 0xff
            val bad = (b < 0x20 && b != 0x0a) || b == 0x7f || b == 0xc0 || b == 0xc1 || b >= 0xf5
            nextBad[k] = if (bad) k else nextBad[k + 1]
        }
        val out = ArrayList<String>()
        var i = 0
        while (i < n && out.size < 100_000) {
            if ((i and 0xffff) == 0 && Thread.currentThread().isInterrupted) throw InterruptedException()
            val b = data[i].toInt() and 0xff
            val (length, headerSize) = when (b) {
                in 0x60..0x77 -> (b - 0x60) to 1
                0x78 -> if (i + 1 < n) (data[i + 1].toInt() and 0xff) to 2 else -1 to 1
                0x79 -> if (i + 2 < n) (((data[i + 1].toInt() and 0xff) shl 8) or (data[i + 2].toInt() and 0xff)) to 3 else -1 to 1
                else -> -1 to 1
            }
            val start = i + headerSize
            if (length in 2..MAX_STRING && start + length <= n && nextBad[start] >= start + length &&
                isValidUtf8(data, start, start + length)
            ) {
                out.add(String(data, start, length, Charsets.UTF_8))
                i = start + length
                continue
            }
            i += 1
        }
        return out
    }

    private fun isValidUtf8(data: ByteArray, from: Int, to: Int): Boolean {
        var k = from
        while (k < to) {
            val b = data[k].toInt() and 0xff
            val extra = when {
                b < 0x80 -> 0
                b in 0xc2..0xdf -> 1
                b in 0xe0..0xef -> 2
                b in 0xf0..0xf4 -> 3
                else -> return false
            }
            if (k + extra >= to) return false
            for (c in 1..extra) if ((data[k + c].toInt() and 0xc0) != 0x80) return false
            k += extra + 1
        }
        return true
    }
}
