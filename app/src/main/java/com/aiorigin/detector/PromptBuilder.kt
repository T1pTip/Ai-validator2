package com.aiorigin.detector

/** Prompt dialects. The target is chosen automatically from what was detected about the file. */
enum class PromptTarget(val label: String) {
    MIDJOURNEY("Midjourney"),
    STABLE_DIFFUSION("Stable Diffusion / Flux"),
    GENERAL("GPT-image / general")
}

/** A reconstructed prompt; nothing here is the original prompt, it is the closest description. */
data class BuiltPrompt(val target: PromptTarget, val prompt: String, val negative: String?)

/**
 * Turns a local image description (Florence-2 caption) into a text-to-image prompt in the dialect
 * of the detected generator. Pure Kotlin so it is unit-tested on the JVM.
 */
object PromptBuilder {
    private val midjourney = listOf("midjourney")
    private val stableDiffusion = listOf(
        "stable diffusion", "stable-diffusion", "sdxl", "sd3", "sd 3", "sd_xl", "flux", "comfyui", "automatic1111",
        "a1111", "fooocus", "invokeai", "novelai", "illustrious", "pony", "juggernaut", "dreamshaper", "z-image", "wan",
        "hidream", "qwen", "seedream", "kolors", "playground", "leonardo", "ideogram", "recraft"
    )
    private val general = listOf("gpt", "dall", "openai", "imagen", "gemini", "firefly", "chatgpt", "grok", "aurora", "meta")

    /**
     * [hints] are generator names from metadata or the cloud check, strongest first.
     * Unknown or absent names give the general dialect.
     */
    fun targetFor(hints: List<String>): PromptTarget {
        for (hint in hints) {
            val h = hint.lowercase()
            if (midjourney.any { h.contains(it) }) return PromptTarget.MIDJOURNEY
            if (general.any { h.contains(it) }) return PromptTarget.GENERAL
            if (stableDiffusion.any { h.contains(it) }) return PromptTarget.STABLE_DIFFUSION
        }
        return PromptTarget.GENERAL
    }

    private val leadIns = listOf(
        Regex("^(in )?this (image|picture|photo)( we can see| shows| is)?\\s*", RegexOption.IGNORE_CASE),
        Regex("^the (image|picture|photo) (shows|depicts|features|is of)\\s*", RegexOption.IGNORE_CASE),
        Regex("^(a|an) (picture|photo|image) of\\s*", RegexOption.IGNORE_CASE),
        Regex("^there (is|are)\\s*", RegexOption.IGNORE_CASE)
    )
    private val progressive = Regex("\\b(is|are) (\\w+ing)\\b", RegexOption.IGNORE_CASE)

    /** Caption sentences rewritten as comma-separated prompt phrases. */
    fun phrases(caption: String): List<String> = caption
        .split(Regex("(?<=[.!?])\\s+"))
        .map { it.trim().trimEnd('.', '!', '?').trim() }
        .filter { it.isNotEmpty() }
        .map { sentence ->
            var s = sentence
            for (r in leadIns) s = s.replace(r, "")
            s = s.replace(progressive, "$2")
            s.replaceFirstChar { it.lowercase() }.trim()
        }
        .filter { it.isNotEmpty() }
        .distinct()

    /** Midjourney aspect ratio, reduced (e.g. 1024x768 -> 4:3). */
    fun aspectRatio(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "1:1"
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val g = gcd(width, height)
        var w = width / g
        var h = height / g
        // Odd sizes (e.g. 719x480) are snapped to the nearest common ratio.
        if (w > 32 || h > 32) {
            val common = listOf(1 to 1, 4 to 3, 3 to 4, 3 to 2, 2 to 3, 16 to 9, 9 to 16, 5 to 4, 4 to 5, 21 to 9, 2 to 1, 1 to 2)
            val r = width.toDouble() / height
            val best = common.minBy { Math.abs(Math.log(it.first.toDouble() / it.second / r)) }
            w = best.first; h = best.second
        }
        return "$w:$h"
    }

    fun build(caption: String, target: PromptTarget, width: Int, height: Int): BuiltPrompt {
        val parts = phrases(caption)
        return when (target) {
            PromptTarget.MIDJOURNEY -> BuiltPrompt(
                target, parts.joinToString(", ") + " --ar ${aspectRatio(width, height)}", null
            )
            PromptTarget.STABLE_DIFFUSION -> BuiltPrompt(
                target,
                (parts + listOf("highly detailed", "sharp focus")).joinToString(", "),
                "blurry, low quality, deformed, watermark, text"
            )
            PromptTarget.GENERAL -> BuiltPrompt(
                target,
                caption.trim().let { if (it.endsWith('.')) it else "$it." } +
                    " Aspect ratio ${aspectRatio(width, height)}.",
                null
            )
        }
    }
}
