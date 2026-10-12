package com.aiorigin.detector

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

/** Sample a few scaled key frames; no full video decoding or remote upload. */
object VideoSampler {
    data class Summary(val durationMs: Long, val framesExamined: Int, val sceneChanges: Int, val width: Int, val height: Int)

    fun inspect(context: Context, uri: Uri, maxFrames: Int = 12): Summary {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (duration <= 0) return Summary(duration, 0, 0, width, height)
            val count = maxFrames.coerceIn(1, 24)
            var previous: IntArray? = null
            var examined = 0
            var changes = 0
            for (i in 0 until count) {
                val timeUs = ((i + 0.5) / count * duration * 1000.0).toLong()
                val frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 64)
                    ?: continue
                try {
                    val signature = signature(frame)
                    if (previous != null && distance(previous, signature) > 20) changes++
                    previous = signature
                    examined++
                } finally { frame.recycle() }
            }
            return Summary(duration, examined, changes, width, height)
        } finally { retriever.release() }
    }

    private fun signature(bitmap: Bitmap): IntArray {
        val pixels = IntArray(64)
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        for (y in 0 until 8) for (x in 0 until 8) {
            val pixel = bitmap.getPixel((x * w / 8).coerceAtMost(w - 1), (y * h / 8).coerceAtMost(h - 1))
            val r = (pixel ushr 16) and 255
            val g = (pixel ushr 8) and 255
            val b = pixel and 255
            pixels[y * 8 + x] = (r * 299 + g * 587 + b * 114) / 1000
        }
        val average = pixels.average()
        return IntArray(64) { if (pixels[it] >= average) 1 else 0 }
    }

    private fun distance(a: IntArray, b: IntArray): Int = a.indices.count { a[it] != b[it] }
}
