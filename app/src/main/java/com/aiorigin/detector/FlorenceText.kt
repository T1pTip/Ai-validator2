package com.aiorigin.detector

/**
 * Text side of Florence-2 (BART byte-level BPE), pure Kotlin so it is tested on the JVM:
 * the fixed task prompt token ids and decoding of generated ids back to text, matching the
 * Hugging Face `tokenizers` ByteLevel decoder with special tokens skipped.
 */
object FlorenceText {
    /** "Describe with a paragraph what is shown in the image." (the <MORE_DETAILED_CAPTION> task). */
    val MORE_DETAILED_CAPTION_IDS = longArrayOf(0, 47066, 21700, 19, 10, 17818, 99, 16, 2343, 11, 5, 2274, 4, 2)
    const val DECODER_START = 2L
    const val BOS = 0L
    const val EOS = 2L
    /** Ids 0..3 (<s>, <pad>, </s>, <unk>) and every added token from <mask> (50264) up are special. */
    private const val FIRST_ADDED = 50264

    private val byteDecoder: Map<Char, Int> by lazy {
        // GPT-2 bytes_to_unicode: printable bytes map to themselves, the rest to 256 + n.
        val bs = ArrayList<Int>()
        bs.addAll(33..126); bs.addAll(161..172); bs.addAll(174..255)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        bs.indices.associate { cs[it].toChar() to bs[it] }
    }

    const val SIZE = 768
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** CLIP-style processor of the model: 768x768 Pillow bicubic (no crop), ImageNet mean/std, NCHW. */
    fun pixelValues(argb: IntArray, width: Int, height: Int): FloatArray {
        val n = width * height
        val planes = arrayOf(ByteArray(n), ByteArray(n), ByteArray(n))
        for (i in 0 until n) {
            val p = argb[i]
            planes[0][i] = (p ushr 16).toByte(); planes[1][i] = (p ushr 8).toByte(); planes[2][i] = p.toByte()
        }
        val resized = PillowBicubic.resize(planes, width, height, SIZE, SIZE)
        val out = FloatArray(3 * SIZE * SIZE)
        for (c in 0 until 3) {
            val plane = resized[c]
            val base = c * SIZE * SIZE
            for (i in 0 until SIZE * SIZE) out[base + i] = ((plane[i].toInt() and 0xff) / 255f - MEAN[c]) / STD[c]
        }
        return out
    }

    fun isSpecial(id: Int) = id < 4 || id >= FIRST_ADDED

    /** [vocab] maps token id to its byte-level token string (from vocab.json). */
    fun decode(ids: List<Int>, vocab: Array<String?>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (isSpecial(id)) continue
            val token = vocab.getOrNull(id) ?: continue
            for (ch in token) bytes.write(byteDecoder[ch] ?: continue)
        }
        return bytes.toByteArray().toString(Charsets.UTF_8).trim()
    }

    /** Parses vocab.json ({"token": id, ...}) into an id-indexed array. */
    fun parseVocab(json: String): Array<String?> {
        val entries = ArrayList<Pair<String, Int>>(51_000)
        var i = json.indexOf('{') + 1
        val sb = StringBuilder()
        while (i < json.length) {
            while (i < json.length && json[i] != '"' && json[i] != '}') i++
            if (i >= json.length || json[i] == '}') break
            i++ // opening quote
            sb.setLength(0)
            while (json[i] != '"') {
                val c = json[i]
                if (c == '\\') {
                    val e = json[i + 1]
                    when (e) {
                        'u' -> { sb.append(json.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        else -> sb.append(e)
                    }
                    i += 2
                } else { sb.append(c); i++ }
            }
            i++ // closing quote
            while (json[i] != ':') i++
            i++
            while (json[i] == ' ') i++
            val start = i
            while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++
            entries.add(sb.toString() to json.substring(start, i).toInt())
        }
        val out = arrayOfNulls<String>((entries.maxOfOrNull { it.second } ?: -1) + 1)
        for ((t, id) in entries) out[id] = t
        return out
    }

    /** Tokens that would repeat a trigram already in [generated] (no_repeat_ngram_size = 3). */
    fun bannedNext(generated: List<Int>, n: Int = 3): Set<Int> {
        if (generated.size < n - 1) return emptySet()
        val prefix = generated.subList(generated.size - (n - 1), generated.size)
        val banned = HashSet<Int>()
        for (i in 0..generated.size - n) {
            var match = true
            for (k in 0 until n - 1) if (generated[i + k] != prefix[k]) { match = false; break }
            if (match) banned.add(generated[i + n - 1])
        }
        return banned
    }
}
