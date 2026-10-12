package com.aiorigin.detector

/** Parsed AUTOMATIC1111-style infotext. This text is editable and is not authenticated. */
data class GenerationParameters(
    val positive: String,
    val negative: String?,
    val settings: Map<String, String>
)

object GenerationParametersParser {
    private val settingsStart = Regex("(?:^|\\n)(Steps\\s*:\\s*\\d+\\s*,)", RegexOption.IGNORE_CASE)
    private val negativeStart = Regex("(?:^|\\n)Negative prompt\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val settingsField = Regex("(?:^|,)\\s*([A-Za-z][A-Za-z0-9 _./()-]{0,60})\\s*:\\s*")

    /** Returns null unless recognizable generation settings are present. */
    fun parse(input: String): GenerationParameters? {
        if (input.length > 200_000) return null
        val normalized = input.replace("\r\n", "\n").replace('\r', '\n').trim()
        val settingsMatch = settingsStart.find(normalized) ?: return null
        val settingsOffset = settingsMatch.range.first + if (normalized[settingsMatch.range.first] == '\n') 1 else 0
        val settingsText = normalized.substring(settingsOffset).trim()
        val settings = linkedMapOf<String, String>()
        val matches = settingsField.findAll(settingsText).toList()
        if (matches.isEmpty() || !matches.first().groupValues[1].equals("Steps", true)) return null
        for (index in matches.indices) {
            val m = matches[index]
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else settingsText.length
            val value = settingsText.substring(m.range.last + 1, end).trim().trimEnd(',').trim()
            if (value.isNotEmpty()) settings[m.groupValues[1].trim()] = value.take(1000)
        }
        val head = normalized.substring(0, settingsOffset).trim()
        val negativeMatch = negativeStart.find(head)
        val positive = (if (negativeMatch == null) head else head.substring(0, negativeMatch.range.first)).trim()
        val negative = negativeMatch?.let { head.substring(it.range.last + 1).trim().ifEmpty { null } }
        if (positive.isEmpty() && negative == null) return null
        return GenerationParameters(positive.take(30_000), negative?.take(30_000), settings)
    }
}
