package com.aiorigin.detector

/** Embedded metadata is editable and cannot authenticate authorship. */
data class PromptFields(val positive: String?, val negative: String?, val origin: String)

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
        val parsed = combined?.takeUnless(::isStructured)?.let { GenerationParametersParser.parse(it) }
        val generic = genericKeys.firstNotNullOfOrNull { clean(normalized[it])?.takeUnless(::isStructured) }
        // A description/comment or an arbitrary JSON graph must not be presented as a recovered prompt.
        // Only accept an extracted value when it comes from an explicitly prompt-bearing field.
        val explicitSource = normalized.keys.any { it in positiveKeys || it in genericKeys || it in combinedKeys }
        val extractedPrompt = clean(extracted)?.takeUnless(::isStructured)
            ?.takeIf { explicitSource && (negative == null || it != negative) }
        return PromptFields(
            positive ?: clean(parsed?.positive) ?: generic ?: extractedPrompt,
            negative ?: clean(parsed?.negative),
            when {
                positive != null || negative != null -> "Explicitly labeled embedded metadata (unverified)"
                parsed != null -> "Generation settings in embedded metadata (unverified)"
                generic != null || extractedPrompt != null -> "Prompt-bearing embedded metadata (unverified)"
                else -> "No reliable prompt-bearing field"
            }
        )
    }
}
