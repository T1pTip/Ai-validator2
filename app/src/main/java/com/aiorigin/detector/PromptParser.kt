package com.aiorigin.detector

import org.json.JSONObject

/** Best-effort extraction from embedded metadata; never establishes authenticity. */
object PromptParser {
    fun extract(raw: String): String {
        val value = raw.trim()
        if (!value.startsWith("{")) return value
        return try {
            val json = JSONObject(value)
            for (key in listOf("positive_prompt", "prompt", "text", "description")) {
                val item = json.opt(key)
                if (item is String && item.isNotBlank()) return item
            }
            // ComfyUI's prompt metadata is a node map, not a single text prompt.
            // Only extract a positive text when its CLIPTextEncode node is referenced
            // by a sampler's positive input. Do not confuse negative prompts.
            val candidates = mutableListOf<String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val node = json.optJSONObject(id) ?: continue
                val classType = node.optString("class_type")
                if (!classType.contains("Sampler", ignoreCase = true)) continue
                val positive = node.optJSONObject("inputs")?.optJSONArray("positive") ?: continue
                val referencedId = positive.opt(0)?.toString() ?: continue
                val textNode = json.optJSONObject(referencedId) ?: continue
                if (textNode.optString("class_type") != "CLIPTextEncode") continue
                val text = textNode.optJSONObject("inputs")?.opt("text")
                if (text is String && text.isNotBlank()) candidates.add(text)
            }
            if (candidates.distinct().size == 1) candidates.first() else value
        } catch (_: Exception) { value }
    }
}
