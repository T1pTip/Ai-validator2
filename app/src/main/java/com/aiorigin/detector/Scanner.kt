package com.aiorigin.detector

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

data class ScanResult(
    val status: String,
    val evidence: List<String>,
    val prompt: String?,
    val negativePrompt: String?,
    val source: String,
    val caveat: String
)

object Scanner {
    private val promptKeys = listOf("prompt", "parameters", "workflow", "description", "comment")

    fun scan(context: Context, uri: Uri): ScanResult {
        val type = context.contentResolver.getType(uri) ?: ""
        val evidence = mutableListOf<String>()
        val metadata = linkedMapOf<String, String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val hash = MediaFingerprint.sha256(stream)
                if (hash != null) evidence.add("SHA-256 file fingerprint: $hash")
                else evidence.add("Fingerprint skipped (file over 1 GiB or interrupted)")
            }
        } catch (_: Exception) {
            evidence.add("Fingerprint unavailable")
        }
        try {
            if (type.startsWith("video/")) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val keys = mapOf(
                        MediaMetadataRetriever.METADATA_KEY_TITLE to "title",
                        MediaMetadataRetriever.METADATA_KEY_AUTHOR to "author"
                    )
                    for ((key, name) in keys) {
                        retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }
                            ?.let { metadata[name] = it }
                    }
                    evidence.add("Video metadata inspected")
                    try {
                        val summary = VideoSampler.inspect(context, uri)
                        evidence.add("Video: ${summary.width}x${summary.height}, ${summary.durationMs / 1000}s")
                        evidence.add("Sampled ${summary.framesExamined} frames; ${summary.sceneChanges} visual changes (not AI detection)")
                    } catch (e: Exception) {
                        evidence.add("Frame sampling unavailable: ${e.javaClass.simpleName}")
                    }
                } finally { retriever.release() }
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val header = ByteArray(8)
                    var count = 0
                    while (count < header.size) {
                        val n = input.read(header, count, header.size - count)
                        if (n <= 0) break
                        count += n
                    }
                    if (count == 8 && header.contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))) {
                        metadata.putAll(PngMetadataReader.read(java.io.SequenceInputStream(java.io.ByteArrayInputStream(header), input)))
                        evidence.add("PNG textual metadata inspected")
                    } else if (count == 8 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF") {
                        metadata.putAll(WebpMetadataReader.read(java.io.SequenceInputStream(java.io.ByteArrayInputStream(header), input)))
                        evidence.add("WebP XMP metadata inspected")
                    } else if (count >= 2 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte()) {
                        metadata.putAll(JpegMetadataReader.read(java.io.SequenceInputStream(java.io.ByteArrayInputStream(header.copyOf(count)), input)))
                        evidence.add("JPEG comment/XMP metadata inspected")
                    }
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    try {
                        val exif = ExifInterface(input)
                        for (tag in listOf(ExifInterface.TAG_IMAGE_DESCRIPTION,
                            ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_USER_COMMENT,
                            ExifInterface.TAG_ARTIST)) {
                            exif.getAttribute(tag)?.takeIf { it.isNotBlank() }
                                ?.let { metadata[tag] = it.take(200_000) }
                        }
                        evidence.add("EXIF fields inspected")
                    } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            evidence.add("Metadata read failed: ${e.javaClass.simpleName}")
        }
        val assessment = MetadataAssessor.assess(metadata)
        evidence.addAll(assessment.indicators)
        val generationParameters = assessment.rawPrompt?.let { GenerationParametersParser.parse(it) }
        val extracted = assessment.rawPrompt?.let { PromptParser.extract(it) }
        val fields = PromptFieldsResolver.resolve(metadata, extracted)
        val prompt = fields.positive
        val negativePrompt = fields.negative
        if (generationParameters != null) {
            evidence.add("A1111-style generation settings found (editable, unverified)")
            generationParameters.negative?.let { evidence.add("Separate negative prompt found (${it.length} characters)") }
            for ((key, value) in generationParameters.settings) {
                if (key.equals("Model", true) || key.equals("Model hash", true) || key.equals("Sampler", true) || key.equals("Steps", true) || key.equals("CFG scale", true)) {
                    evidence.add("$key: ${value.take(120)}")
                }
            }
        }
        if (prompt != null) evidence.add("Positive text extracted from embedded metadata")
        if (negativePrompt != null) evidence.add("Negative prompt extracted from embedded metadata")
        return ScanResult(
            assessment.status, evidence, prompt, negativePrompt,
            if (prompt == null && negativePrompt == null) "No original prompt found" else fields.origin,
            "This version does not perform visual AI detection, C2PA verification, web search or prompt reconstruction. Video frame sampling is descriptive only. Metadata can be edited or removed. An embedded description is not proof of an original prompt."
        )
    }

}
