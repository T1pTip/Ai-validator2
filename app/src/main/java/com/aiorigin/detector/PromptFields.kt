package com.aiorigin.detector

enum class PromptOrigin { EXPLICIT, SETTINGS, PROMPT_FIELD, NONE }

/** Embedded metadata is editable and cannot authenticate authorship. */
data class PromptFields(
    val positive: String?,
    val negative: String?,
    val origin: String,
    val originKind: PromptOrigin = PromptOrigin.NONE,
    val settings: Map<String, String> = emptyMap()
)

object PromptFieldsResolver {
    private val positiveKeys = setOf("positive_prompt", "positive prompt", "positiveprompt")
    private val negativeKeys = setOf("negative_prompt", "negative prompt", "negativeprompt")
    private val combinedKeys = setOf("parameters", "generation_data")
    private val genericKeys = setOf("prompt")
    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.take(30_000)
    private fun isStructured(value: String) = value.trimStart().let { it.startsWith("{") || it.startsWith("[") }

    fun resolve(metadata: Map<String, String>, extracted: String?): PromptFields {
        val normalized = metadata.entries.associate { it.key.trim().lowercase() to it.value }
        val positive = positiveKeys.firstNotNullOfOrNull { clean(normalized[it]) }
        val negative = negativeKeys.firstNotNullOfOrNull { clean(normalized[it]) }
        val combined = combinedKeys.firstNotNullOfOrNull { clean(normalized[it]) }
        // AUTOMATIC1111 writes its infotext to EXIF UserComment for JPEG/WebP. The A1111 structure
        // (prompt + "Steps: N," settings line) is specific enough to accept from any field.
        val parsed = combined?.takeUnless(::isStructured)?.let { GenerationParametersParser.parse(it) }
            ?: normalized.values.firstNotNullOfOrNull { v ->
                clean(v)?.takeUnless(::isStructured)?.let { GenerationParametersParser.parse(it) }
            }
        val generic = genericKeys.firstNotNullOfOrNull { clean(normalized[it])?.takeUnless(::isStructured) }
        // A description/comment or an arbitrary JSON graph must not be presented as a recovered prompt.
        // Only accept an extracted value when it comes from an explicitly prompt-bearing field.
        val explicitSource = normalized.keys.any { it in positiveKeys || it in genericKeys || it in combinedKeys }
        val extractedPrompt = clean(extracted)?.takeUnless(::isStructured)
            ?.takeIf { explicitSource && (negative == null || it != negative) }
            ?.takeUnless { parsed != null && GenerationParametersParser.parse(it) != null }
        val kind = when {
            positive != null || negative != null -> PromptOrigin.EXPLICIT
            parsed != null -> PromptOrigin.SETTINGS
            generic != null || extractedPrompt != null -> PromptOrigin.PROMPT_FIELD
            else -> PromptOrigin.NONE
        }
        return PromptFields(
            positive ?: clean(parsed?.positive) ?: generic ?: extractedPrompt,
            negative ?: clean(parsed?.negative),
            when (kind) {
                PromptOrigin.EXPLICIT -> "Explicitly labeled embedded metadata (unverified)"
                PromptOrigin.SETTINGS -> "Generation settings in embedded metadata (unverified)"
                PromptOrigin.PROMPT_FIELD -> "Prompt-bearing embedded metadata (unverified)"
                PromptOrigin.NONE -> "No reliable prompt-bearing field"
            },
            kind,
            parsed?.settings ?: emptyMap()
        )
    }
}
