package com.aiorigin.detector

/** Metadata is user-editable; none of these outcomes authenticate AI origin. */
data class MetadataAssessment(
    val status: String,
    val promptKey: String?,
    val rawPrompt: String?,
    val indicators: List<String>
)

object MetadataAssessor {
    private val generationTools = listOf("comfyui", "automatic1111", "stable diffusion", "fooocus", "invokeai", "novelai", "midjourney")
    private val explicitPromptKeys = listOf("positive_prompt", "positive prompt", "parameters", "generation_data", "prompt", "negative_prompt", "negative prompt")
    private val weakKeys = setOf("description", "comment", "imagedescription", "usercomment")

    fun assess(metadata: Map<String, String>): MetadataAssessment {
        val indicators = mutableListOf<String>()
        val software = metadata.entries.firstOrNull { it.key.contains("software", true) }?.value
        val tool = metadata.values.firstNotNullOfOrNull { value ->
            generationTools.firstOrNull { value.contains(it, ignoreCase = true) }
        }
        if (tool != null) indicators.add("Generation-tool string in editable metadata: $tool")
        if (software != null) indicators.add("Software field: ${software.take(120)}")
        // Prefer positive/generation fields over negative-only data regardless of file field order.
        // A negative prompt alone is never a positive generation prompt.
        val explicit = explicitPromptKeys.firstNotNullOfOrNull { wanted ->
            metadata.entries.firstOrNull { (key, value) ->
                key.trim().equals(wanted, ignoreCase = true) && value.isNotBlank()
            }
        }
        val weak = metadata.entries.firstOrNull { (key, value) ->
            weakKeys.any { key.lowercase().contains(it) } && value.isNotBlank()
        }
        val selected = explicit ?: weak
        val status = when {
            tool != null -> "AI creation metadata found (unverified)"
            explicit != null -> "Prompt-like metadata found (unverified)"
            weak != null -> "Description found; origin unknown"
            else -> "Origin unknown"
        }
        return MetadataAssessment(status, selected?.key, selected?.value, indicators)
    }
}
