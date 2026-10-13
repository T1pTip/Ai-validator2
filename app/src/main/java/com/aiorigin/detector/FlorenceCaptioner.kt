package com.aiorigin.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device image description with Florence-2-base-ft (Microsoft, MIT licence; uint8 ONNX export by
 * onnx-community). Greedy decoding with forced BOS and no-repeat trigrams, identical to
 * tools/model/florence_reference.py, which the instrumentation test compares against.
 */
object FlorenceCaptioner {
    private const val SIZE = FlorenceText.SIZE
    private const val LAYERS = 6
    private const val HEADS = 12
    private const val HEAD_DIM = 64
    private const val MAX_NEW_TOKENS = 256

    /** [agreement]: with teacher forcing, the share of steps where the model's own choice matched. */
    data class Caption(val text: String, val ids: List<Int>, val millis: Long, val agreement: Float? = null)

    private class Sessions(val vision: OrtSession, val embed: OrtSession, val encoder: OrtSession, val decoder: OrtSession)

    @Volatile private var sessions: Sessions? = null
    @Volatile private var vocab: Array<String?>? = null
    private val lock = Any()

    private fun sessions(context: Context): Sessions = sessions ?: synchronized(lock) {
        sessions ?: run {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            fun load(i: Int) = env.createSession(ModelDownloader.file(context, ModelDownloader.FLORENCE[i]).absolutePath, options)
            Sessions(load(0), load(1), load(2), load(3)).also { sessions = it }
        }
    }

    private fun vocab(context: Context): Array<String?> = vocab ?: synchronized(lock) {
        vocab ?: FlorenceText.parseVocab(
            context.assets.open("florence/vocab.json").use { it.readBytes().decodeToString() }
        ).also { vocab = it }
    }

    /**
     * [teacherForce] (tests only): follow these token ids instead of the model's choices and report
     * how often the model agreed. This checks the whole pipeline without depending on argmax ties,
     * which quantized kernels on different CPUs break differently.
     */
    fun caption(context: Context, argb: IntArray, width: Int, height: Int, teacherForce: List<Int>? = null): Caption {
        val start = System.currentTimeMillis()
        val s = sessions(context)
        val env = OrtEnvironment.getEnvironment()
        val toClose = ArrayList<AutoCloseable>()
        try {
            fun check() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
            val pixels = OnnxTensor.createTensor(env, FloatBuffer.wrap(FlorenceText.pixelValues(argb, width, height)),
                longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).also { toClose.add(it) }
            val visionOut = s.vision.run(mapOf("pixel_values" to pixels)).also { toClose.add(it) }
            val features = floats(visionOut[0])
            check()
            val promptIds = OnnxTensor.createTensor(env, LongBuffer.wrap(FlorenceText.MORE_DETAILED_CAPTION_IDS),
                longArrayOf(1, FlorenceText.MORE_DETAILED_CAPTION_IDS.size.toLong())).also { toClose.add(it) }
            val promptOut = s.embed.run(mapOf("input_ids" to promptIds)).also { toClose.add(it) }
            val prompt = floats(promptOut[0])
            val featTokens = features.size / 768
            val promptTokens = prompt.size / 768
            val seq = featTokens + promptTokens
            val joined = FloatArray(seq * 768)
            System.arraycopy(features, 0, joined, 0, features.size)
            System.arraycopy(prompt, 0, joined, features.size, prompt.size)
            val inputsEmbeds = OnnxTensor.createTensor(env, FloatBuffer.wrap(joined), longArrayOf(1, seq.toLong(), 768))
                .also { toClose.add(it) }
            val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seq) { 1L }), longArrayOf(1, seq.toLong()))
                .also { toClose.add(it) }
            val encoderOut = s.encoder.run(mapOf("attention_mask" to mask, "inputs_embeds" to inputsEmbeds)).also { toClose.add(it) }
            val hidden = encoderOut[0] as OnnxTensor
            check()

            val empty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, HEADS.toLong(), 0, HEAD_DIM.toLong()))
                .also { toClose.add(it) }
            val past = HashMap<String, OnnxTensor>()
            for (l in 0 until LAYERS) for (kind in listOf("decoder", "encoder")) for (kv in listOf("key", "value")) {
                past["past_key_values.$l.$kind.$kv"] = empty
            }
            val outputNames = s.decoder.outputNames.toList()
            val generated = ArrayList<Int>()
            var token = FlorenceText.DECODER_START
            var previousStep: OrtSession.Result? = null
            var agreed = 0
            var compared = 0
            val steps = teacherForce?.size ?: MAX_NEW_TOKENS
            for (step in 0 until steps) {
                check()
                val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(token)), longArrayOf(1, 1))
                val embOut = s.embed.run(mapOf("input_ids" to ids))
                val useCache = OnnxTensor.createTensor(env, booleanArrayOf(step > 0))
                val feed = HashMap<String, OnnxTensor>(past)
                feed["encoder_attention_mask"] = mask
                feed["encoder_hidden_states"] = hidden
                feed["inputs_embeds"] = embOut[0] as OnnxTensor
                feed["use_cache_branch"] = useCache
                val result = s.decoder.run(feed)
                ids.close(); embOut.close(); useCache.close()
                @Suppress("UNCHECKED_CAST")
                val logits = result.get("logits").get().value as Array<Array<FloatArray>>
                val row = logits[0][logits[0].size - 1]
                for (name in outputNames) {
                    if (!name.startsWith("present.")) continue
                    val key = name.replace("present.", "past_key_values.")
                    // Cross-attention caches come from the first step and stay valid for the whole run.
                    if (step == 0 || key.contains(".decoder.")) past[key] = result.get(name).get() as OnnxTensor
                }
                if (step == 0) {
                    toClose.add(result) // holds the encoder caches
                } else {
                    previousStep?.close() // its decoder caches were replaced above
                    previousStep = result
                }
                token = if (step == 0) FlorenceText.BOS else {
                    val banned = FlorenceText.bannedNext(generated)
                    var best = -1
                    var bestValue = Float.NEGATIVE_INFINITY
                    for (i in row.indices) if (i !in banned && row[i] > bestValue) { bestValue = row[i]; best = i }
                    if (teacherForce != null) {
                        compared++
                        if (best == teacherForce[step]) agreed++
                        teacherForce[step].toLong()
                    } else best.toLong()
                }
                generated.add(token.toInt())
                if (token == FlorenceText.EOS) break
            }
            previousStep?.close()
            return Caption(FlorenceText.decode(generated, vocab(context)), generated, System.currentTimeMillis() - start,
                if (compared > 0) agreed.toFloat() / compared else null)
        } finally {
            toClose.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun floats(value: OnnxValue): FloatArray {
        val t = value as OnnxTensor
        val buffer = t.floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
}
