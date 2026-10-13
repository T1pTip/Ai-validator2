package com.aiorigin.detector

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible

data class ScanResult(
    val status: String,
    val evidence: List<String>,
    val prompt: String?,
    val negativePrompt: String?,
    val source: String,
    val caveat: String,
    val settings: Map<String, String> = emptyMap(),
    val level: OriginLevel = OriginLevel.UNKNOWN,
    val explanation: String = "",
    val isVideo: Boolean = false,
    /** Embedded description/comment. Shown separately and never presented as a prompt. */
    val description: String? = null
)

/**
 * Runs the independent local checks in parallel (file fingerprint, embedded metadata,
 * video frame sampling) and merges them into one result. Nothing leaves the device.
 */
object Scanner {
    private class MetadataOutcome(val metadata: Map<String, String>, val evidence: List<String>, val readFailed: Boolean = false)

    /** [typeHint] is the server's Content-Type for downloaded files, since file:// URIs carry no type. */
    suspend fun scan(
        context: Context, uri: Uri, sourceEvidence: List<String> = emptyList(), typeHint: String? = null
    ): ScanResult = coroutineScope {
        val type = context.contentResolver.getType(uri) ?: typeHint?.lowercase() ?: ""
        val header = runInterruptible(Dispatchers.IO) { readHeader(context, uri, 16) }
        val isVideo = type.startsWith("video/") || (!type.startsWith("image/") && isVideoContainer(header))

        val fingerprint = async(Dispatchers.IO) { runInterruptible { fingerprint(context, uri) } }
        val metadata = async(Dispatchers.IO) {
            runInterruptible { if (isVideo) videoMetadata(context, uri) else imageMetadata(context, uri, header) }
        }
        val frames = if (isVideo) async(Dispatchers.IO) { runInterruptible { videoFrames(context, uri) } } else null

        val evidence = sourceEvidence.toMutableList()
        evidence.add(fingerprint.await())
        val meta = metadata.await()
        evidence.addAll(meta.evidence)
        frames?.let { evidence.addAll(it.await()) }
        build(context, meta.metadata, evidence, isVideo, meta.readFailed)
    }

    private fun build(
        context: Context, metadata: Map<String, String>, evidence: MutableList<String>,
        isVideo: Boolean, readFailed: Boolean
    ): ScanResult {
        val assessment = MetadataAssessor.assess(metadata)
        if (assessment.level == OriginLevel.AI_DECLARED) evidence.add(context.getString(R.string.e_declared))
        if (assessment.level == OriginLevel.AI_EDITED) evidence.add(context.getString(R.string.e_declared_edit))
        if (assessment.c2paPresent) evidence.add(context.getString(R.string.e_c2pa))
        assessment.c2paGenerator?.let { evidence.add(context.getString(R.string.e_c2pa_gen, it.take(120))) }
        assessment.tool?.let { evidence.add(context.getString(R.string.e_tool, it)) }
        if (assessment.generationSettings) evidence.add(context.getString(R.string.e_settings))
        assessment.software?.let { evidence.add(context.getString(R.string.e_software, it.take(120))) }

        val extracted = assessment.rawPrompt?.let { PromptParser.extract(it) }
        val fields = PromptFieldsResolver.resolve(metadata, extracted)
        fields.negative?.let { evidence.add(context.getString(R.string.e_negative_len, it.length)) }

        val (title, explanation) = when (assessment.level) {
            OriginLevel.AI_DECLARED -> R.string.v_ai_declared to R.string.x_declared
            OriginLevel.AI_EDITED -> R.string.v_ai_edited to R.string.x_edited
            OriginLevel.AI_TOOL -> R.string.v_ai_tool to R.string.x_tool
            OriginLevel.PROMPT -> R.string.v_prompt to R.string.x_prompt
            OriginLevel.DESCRIPTION -> R.string.v_description to R.string.x_description
            // Nothing was found because the file could not be opened: say so instead of "no signs found".
            OriginLevel.UNKNOWN -> if (readFailed) R.string.v_unreadable to R.string.x_unreadable
                else R.string.v_unknown to R.string.x_unknown
        }
        val source = context.getString(
            when (fields.originKind) {
                PromptOrigin.EXPLICIT -> R.string.o_explicit
                PromptOrigin.SETTINGS -> R.string.o_settings
                PromptOrigin.PROMPT_FIELD -> R.string.o_field
                PromptOrigin.NONE -> R.string.o_none
            }
        )
        val shownSettings = fields.settings.filterKeys { key ->
            listOf("Model", "Model hash", "Sampler", "Steps", "CFG scale", "Seed", "Size", "Scheduler")
                .any { it.equals(key, ignoreCase = true) }
        }
        return ScanResult(
            status = context.getString(title),
            evidence = evidence,
            prompt = fields.positive,
            negativePrompt = fields.negative,
            source = source,
            caveat = context.getString(R.string.caveat),
            settings = shownSettings,
            level = assessment.level,
            explanation = context.getString(explanation),
            isVideo = isVideo,
            description = MetadataAssessor.description(metadata)?.takeIf { it != fields.positive }
        )
    }

    private fun fingerprint(context: Context, uri: Uri): String = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val hash = MediaFingerprint.sha256(stream)
            if (hash != null) context.getString(R.string.e_fingerprint, hash)
            else context.getString(R.string.e_fingerprint_skipped)
        } ?: context.getString(R.string.e_fingerprint_failed)
    } catch (_: Exception) {
        context.getString(R.string.e_fingerprint_failed)
    }

    private fun imageMetadata(context: Context, uri: Uri, header: ByteArray): MetadataOutcome {
        val metadata = linkedMapOf<String, String>()
        val evidence = mutableListOf<String>()
        var readFailed = false
        try {
            val opened = context.contentResolver.openInputStream(uri)
            if (opened == null) readFailed = true
            opened?.use { input ->
                when {
                    header.size >= 8 && header.copyOf(8).contentEquals(PNG_SIGNATURE) -> {
                        metadata.putAll(PngMetadataReader.read(input)); evidence.add(context.getString(R.string.e_png))
                    }
                    header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> {
                        metadata.putAll(WebpMetadataReader.read(input)); evidence.add(context.getString(R.string.e_webp))
                    }
                    header.size >= 2 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> {
                        metadata.putAll(JpegMetadataReader.read(input)); evidence.add(context.getString(R.string.e_jpeg))
                    }
                    else -> evidence.add(context.getString(R.string.e_unsupported, context.contentResolver.getType(uri) ?: "?"))
                }
            }
            // XMP stored as a PNG iTXt chunk (XML:com.adobe.xmp) or raw XMP from other readers.
            for (value in metadata.values.toList()) {
                if (XmpFields.looksLikeXmp(value)) for ((k, v) in XmpFields.extract(value)) metadata.putIfAbsent(k, v)
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                try {
                    val exif = ExifInterface(input)
                    // UserComment carries its own character code (A1111 writes UTF-16); decode it from raw bytes.
                    ExifText.userComment(exif.getAttributeBytes(ExifInterface.TAG_USER_COMMENT))
                        ?.let { metadata.putIfAbsent(ExifInterface.TAG_USER_COMMENT, it.take(200_000)) }
                    for (tag in listOf(ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_SOFTWARE,
                        ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_ARTIST)) {
                        exif.getAttribute(tag)?.trim('\u0000', ' ')?.takeIf { it.isNotBlank() }
                            ?.let { metadata.putIfAbsent(tag, it.take(200_000)) }
                    }
                    val camera = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
                        .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")
                    evidence.add(context.getString(R.string.e_exif))
                    if (camera.isNotEmpty()) evidence.add(context.getString(R.string.e_camera, camera.take(80)))
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            readFailed = true
            evidence.add(context.getString(R.string.e_meta_failed, e.javaClass.simpleName))
        }
        return MetadataOutcome(metadata, evidence, readFailed)
    }

    private fun videoMetadata(context: Context, uri: Uri): MetadataOutcome {
        val metadata = linkedMapOf<String, String>()
        val evidence = mutableListOf<String>()
        val retriever = MediaMetadataRetriever()
        var readFailed = false
        try {
            retriever.setDataSource(context, uri)
            val keys = mapOf(
                MediaMetadataRetriever.METADATA_KEY_TITLE to "title",
                MediaMetadataRetriever.METADATA_KEY_AUTHOR to "author",
                MediaMetadataRetriever.METADATA_KEY_WRITER to "software"
            )
            for ((key, name) in keys) {
                retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }?.let { metadata[name] = it.take(2_000) }
            }
            evidence.add(context.getString(R.string.e_video_meta))
        } catch (e: Exception) {
            readFailed = true
            evidence.add(context.getString(R.string.e_meta_failed, e.javaClass.simpleName))
        } finally {
            retriever.release()
        }
        return MetadataOutcome(metadata, evidence, readFailed)
    }

    private fun videoFrames(context: Context, uri: Uri): List<String> = try {
        val summary = VideoSampler.inspect(context, uri)
        listOf(
            context.getString(R.string.e_video_summary, summary.width, summary.height, (summary.durationMs / 1000).toInt()),
            context.getString(R.string.e_video_frames, summary.framesExamined, summary.sceneChanges)
        )
    } catch (e: Exception) {
        listOf(context.getString(R.string.e_video_failed, e.javaClass.simpleName))
    }

    private fun readHeader(context: Context, uri: Uri, size: Int): ByteArray = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(size)
            var count = 0
            while (count < size) {
                val n = input.read(header, count, size - count)
                if (n <= 0) break
                count += n
            }
            header.copyOf(count)
        } ?: ByteArray(0)
    } catch (_: Exception) { ByteArray(0) }

    /** ISO-BMFF "ftyp" box that is not an image brand (HEIC/AVIF also use ftyp). */
    private fun isVideoContainer(header: ByteArray): Boolean {
        // Matroska / WebM start with the EBML magic number.
        if (header.size >= 4 && header[0] == 0x1a.toByte() && header[1] == 0x45.toByte() &&
            header[2] == 0xdf.toByte() && header[3] == 0xa3.toByte()) return true
        if (header.size < 12 || String(header, 4, 4, Charsets.US_ASCII) != "ftyp") return false
        val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
        return brand !in setOf("heic", "heix", "heim", "heis", "mif1", "msf1", "avif", "avis")
    }

    private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
}
