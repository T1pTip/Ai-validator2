package com.aiorigin.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Prepares what is sent to Sightengine (only after the user confirms) and aggregates the answers. */
object CloudCheck {
    const val VIDEO_FRAMES = 3
    private const val MAX_UPLOAD_BYTES = 12L * 1024 * 1024
    private const val MAX_EDGE = 4096

    data class Outcome(
        val probability: Float,
        val frames: Int,
        val topGenerator: Pair<String, Float>?,
        val operations: Int?
    )

    /** Original bytes when small and in a common format; otherwise a high-quality JPEG copy. */
    fun prepareImage(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val common = bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp")
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (common && size in 1..MAX_UPLOAD_BYTES && maxOf(bounds.outWidth, bounds.outHeight) <= MAX_EDGE) {
            return resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("unreadable")
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("undecodable")
        return try { jpeg(bitmap) } finally { bitmap.recycle() }
    }

    fun prepareVideoFrames(context: Context, uri: Uri): List<ByteArray> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = mutableListOf<ByteArray>()
            for (i in 0 until VIDEO_FRAMES) {
                val timeUs = if (duration > 0) ((i + 0.5) / VIDEO_FRAMES * duration * 1000.0).toLong() else 0L
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                try { frames.add(jpeg(frame)) } finally { frame.recycle() }
            }
            if (frames.isEmpty()) throw IllegalStateException("no frames")
            return frames
        } finally { retriever.release() }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val scale = MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val src = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        try {
            return ByteArrayOutputStream().use { out -> src.compress(Bitmap.CompressFormat.JPEG, 95, out); out.toByteArray() }
        } finally { if (src !== bitmap) src.recycle() }
    }

    /** The highest frame score is reported for videos, with the generator from that frame. */
    fun aggregate(verdicts: List<SightengineClient.Verdict>): Outcome {
        val best = verdicts.maxBy { it.aiProbability }
        val ops = verdicts.mapNotNull { it.operations }.takeIf { it.isNotEmpty() }?.sum()
        return Outcome(best.aiProbability, verdicts.size, best.generators.firstOrNull(), ops)
    }
}
