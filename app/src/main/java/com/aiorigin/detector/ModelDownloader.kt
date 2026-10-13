package com.aiorigin.detector

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the Florence-2 caption model once, on the user's request, from a pinned Hugging Face
 * revision and verifies every file's SHA-256 before use. Nothing about the user is sent.
 */
object ModelDownloader {
    data class ModelFile(val path: String, val bytes: Long, val sha256: String)

    const val REPO = "onnx-community/Florence-2-base-ft"
    const val REVISION = "e88a44eaf3791a35eae0c5a47b3dbcd36e67eb6f"
    /** Replaced only by instrumentation tests. */
    @Volatile var baseUrl = "https://huggingface.co/$REPO/resolve/$REVISION/"

    // uint8 exports: Android's ONNX Runtime has no ConvInteger kernel for the int8 vision encoder.
    val FLORENCE = listOf(
        ModelFile("onnx/vision_encoder_uint8.onnx", 93_746_540, "3b79d54f23f666f731549db23cb070c35a979ce19cbd9720e90e67a78dc9768c"),
        ModelFile("onnx/embed_tokens_uint8.onnx", 39_390_432, "3bb20ac178b52950364abf1a6c0a2f3c3a172a27ea4fdb7c1c3299bca35f8879"),
        ModelFile("onnx/encoder_model_uint8.onnx", 43_651_512, "653648259f099dfb5ed74e1b8b47c41020f3a90f922390aa25fd9bc5805e2fdd"),
        ModelFile("onnx/decoder_model_merged_uint8.onnx", 98_177_678, "c0bfddc38e75915ab899018832056a6741fa367525bf76fa760aac6c56e00bb1")
    )
    val TOTAL_BYTES = FLORENCE.sumOf { it.bytes }

    fun dir(context: Context) = File(context.noBackupFilesDir, "florence").apply { mkdirs() }

    fun file(context: Context, f: ModelFile) = File(dir(context), f.path.substringAfterLast('/'))

    /** Present files are trusted only if their size matches; content was verified when downloaded. */
    fun isReady(context: Context) = FLORENCE.all { file(context, it).length() == it.bytes }

    /** [progress] receives (downloaded bytes, total bytes). Interruptible between chunks. */
    fun download(context: Context, progress: (Long, Long) -> Unit) {
        var done = FLORENCE.filter { file(context, it).length() == it.bytes }.sumOf { it.bytes }
        for (f in FLORENCE) {
            val target = file(context, f)
            if (target.length() == f.bytes) continue
            val part = File(target.path + ".part")
            part.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            var url = baseUrl + f.path
            var connection: HttpURLConnection
            var redirects = 0
            while (true) {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                }
                val code = connection.responseCode
                if (code in 300..399 && redirects < 5) {
                    val next = connection.getHeaderField("Location") ?: throw IOException("redirect without location")
                    url = URL(URL(url), next).toString()
                    if (!url.startsWith("https://") && !url.startsWith(baseUrl)) throw IOException("insecure redirect")
                    connection.disconnect(); redirects++; continue
                }
                if (code != 200) throw IOException("HTTP $code for ${f.path}")
                break
            }
            try {
                connection.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var written = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            written += n
                            if (written > f.bytes) throw IOException("larger than expected: ${f.path}")
                            progress(done + written, TOTAL_BYTES)
                        }
                    }
                }
            } finally { connection.disconnect() }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (part.length() != f.bytes || sha != f.sha256) {
                part.delete()
                throw IOException("checksum mismatch for ${f.path}")
            }
            if (!part.renameTo(target)) throw IOException("rename failed for ${f.path}")
            done += f.bytes
        }
        progress(TOTAL_BYTES, TOTAL_BYTES)
    }
}
