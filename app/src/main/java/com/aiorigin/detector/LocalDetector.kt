package com.aiorigin.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * On-device visual check with the Community Forensics ViT-S/384 detector (Park & Owens,
 * CVPR 2025; MIT licence). Runs fully on the phone. It is a hint, not a verdict: open
 * detectors are much weaker on the newest commercial generators.
 */
object LocalDetector {
    const val MODEL_ASSET = "models/commfor_vit_s384.onnx"
    const val MODEL_NAME = "Community Forensics ViT-S/384"

    /** Images are decoded at full size unless the short side exceeds this; then halved by the decoder. */
    private const val MAX_DECODE_SHORT_SIDE = 4_000
    private const val VIDEO_FRAMES = 3

    data class Score(
        val probability: Float,
        val frames: Int,
        val frameProbabilities: List<Float>,
        val millis: Long,
        /** Raw model output of the reported image/frame (probability = sigmoid(logit)). */
        val logit: Float
    )

    @Volatile private var session: OrtSession? = null
    private val lock = Any()

    fun isAvailable(context: Context): Boolean = try {
        context.assets.openFd(MODEL_ASSET).use { it.length > 0 }
    } catch (_: Exception) {
        // Compressed assets cannot be opened as fd; fall back to listing.
        context.assets.list("models")?.contains(MODEL_ASSET.substringAfter('/')) == true
    }

    private fun session(context: Context): OrtSession = session ?: synchronized(lock) {
        session ?: run {
            val file = modelFile(context)
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            }
            OrtEnvironment.getEnvironment().createSession(file.absolutePath, options).also { session = it }
        }
    }

    /** ONNX Runtime needs a file path for memory-mapped loading; the asset is copied once per app version. */
    private fun modelFile(context: Context): File {
        val dir = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val file = File(dir, "commfor-$version.onnx")
        if (!file.exists()) {
            dir.listFiles()?.forEach { it.delete() }
            val tmp = File(dir, "copy.tmp")
            context.assets.open(MODEL_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            if (!tmp.renameTo(file)) throw IllegalStateException("model copy failed")
        }
        return file
    }

    /** Model logit for the pixels; sigmoid(logit) is the probability that they are AI-generated. */
    fun logitForPixels(context: Context, argb: IntArray, width: Int, height: Int): Float {
        val tensor = ClipPreprocessor.toTensor(argb, width, height)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, 3, ClipPreprocessor.CROP.toLong(), ClipPreprocessor.CROP.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), shape).use { input ->
            session(context).run(mapOf("pixel_values" to input)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val logits = (output[0].value as Array<FloatArray>)
                return logits[0][0]
            }
        }
    }

    fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    fun scoreImage(context: Context, uri: Uri): Score {
        val start = System.currentTimeMillis()
        val bitmap = decode(context, uri) ?: throw IllegalArgumentException("undecodable image")
        val logit = try { logitForBitmap(context, bitmap) } finally { bitmap.recycle() }
        val p = sigmoid(logit)
        return Score(p, 1, listOf(p), System.currentTimeMillis() - start, logit)
    }

    /** Scores [VIDEO_FRAMES] frames spread over the video; the reported value is the highest frame score. */
    fun scoreVideo(context: Context, uri: Uri): Score {
        val start = System.currentTimeMillis()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val logits = mutableListOf<Float>()
            for (i in 0 until VIDEO_FRAMES) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val timeUs = if (duration > 0) ((i + 0.5) / VIDEO_FRAMES * duration * 1000.0).toLong() else 0L
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                try { logits.add(logitForBitmap(context, frame)) } finally { frame.recycle() }
            }
            if (logits.isEmpty()) throw IllegalArgumentException("no decodable frames")
            val best = logits.max()
            return Score(sigmoid(best), logits.size, logits.map(::sigmoid), System.currentTimeMillis() - start, best)
        } finally { retriever.release() }
    }

    /** Raw pixel values, as Pillow sees them: no colour-space conversion, no premultiplied alpha. */
    fun pixels(bitmap: Bitmap): IntArray {
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        try {
            val w = src.width
            val h = src.height
            val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            src.copyPixelsToBuffer(buffer)
            buffer.rewind()
            val out = IntArray(w * h)
            // ARGB_8888 is stored as R, G, B, A bytes in memory.
            for (i in 0 until w * h) {
                val r = buffer.get().toInt() and 0xff
                val g = buffer.get().toInt() and 0xff
                val b = buffer.get().toInt() and 0xff
                val a = buffer.get().toInt() and 0xff
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return out
        } finally { if (src !== bitmap) src.recycle() }
    }

    private fun logitForBitmap(context: Context, bitmap: Bitmap): Float =
        logitForPixels(context, pixels(bitmap), bitmap.width, bitmap.height)

    fun decode(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        // Typical photos decode at full size (identical to the Pillow reference); only very large
        // images (e.g. 200 MP) are halved by the decoder to keep memory bounded.
        while (minOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_SHORT_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
