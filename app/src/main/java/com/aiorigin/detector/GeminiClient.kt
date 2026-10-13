package com.aiorigin.detector

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional prompt reconstruction with Google Gemini (free tier). Called only after the user
 * confirms each upload. The model is chosen at run time from the models the key can use, because
 * Gemini model names change often. Google Search grounding is requested so Gemini can look for
 * the original prompt online; if the key's tier rejects it, the request is retried without it.
 */
class GeminiClient(private val baseUrl: String = endpoint) {
    companion object {
        const val DEFAULT_BASE = "https://generativelanguage.googleapis.com"
        /** Replaced only by instrumentation tests. */
        @Volatile var endpoint: String = DEFAULT_BASE
        private const val TIMEOUT_MS = 90_000
        private val stableFlash = Regex("^models/gemini-(\\d+(?:\\.\\d+)?)-flash$")
        private val anyFlash = Regex("^models/gemini-(\\d+(?:\\.\\d+)?)-flash[\\w.-]*$")
        private val excluded = listOf("tts", "image", "live", "audio", "embedding", "thinking-exp")

        /** Picks the newest stable "gemini-X-flash" model; falls back to any newest flash variant. */
        fun chooseModel(names: List<String>): String? {
            fun version(name: String, r: Regex) = r.find(name)?.groupValues?.get(1)?.toDoubleOrNull()
            names.filter { stableFlash.matches(it) }.maxByOrNull { version(it, stableFlash) ?: 0.0 }?.let { return it }
            return names.filter { n -> anyFlash.matches(n) && excluded.none { n.contains(it) } && !n.contains("lite") }
                .maxByOrNull { version(it, anyFlash) ?: 0.0 }
                ?: names.filter { n -> anyFlash.matches(n) && excluded.none { n.contains(it) } }
                    .maxByOrNull { version(it, anyFlash) ?: 0.0 }
        }

        fun instructions(localCaption: String?, generator: String?, target: PromptTarget): String = buildString {
            append("You help reconstruct the text-to-image prompt that most likely produced this image.\n")
            if (!localCaption.isNullOrBlank()) append("A small on-device model described it as: \"").append(localCaption).append("\"\n")
            append("Generator detected by an AI-image detector: ").append(generator ?: "unknown").append(".\n")
            append("Write the prompt for: ").append(target.label).append(". ")
            when (target) {
                PromptTarget.MIDJOURNEY -> append("Use Midjourney style: comma-separated phrases, then parameters such as --ar and --style if they fit.\n")
                PromptTarget.STABLE_DIFFUSION -> append("Use Stable Diffusion / Flux style: descriptive phrases and quality tags; also give a negative prompt.\n")
                PromptTarget.GENERAL -> append("Use a natural-language paragraph as for GPT-image or Imagen.\n")
            }
            append("Cover subject, composition, camera/lens or art medium, lighting, colour palette and style.\n")
            append("Use Google Search to look for this exact image or its original prompt online (for example Civitai, ")
            append("Lexica, PromptHero, Reddit, X). Only report an online prompt if you actually found it.\n")
            append("Answer with JSON only, no markdown: {\"prompt\": string, \"negative_prompt\": string or null, ")
            append("\"generator_guess\": string or null, \"found_online\": {\"url\": string, \"prompt\": string} or null, ")
            append("\"notes\": short explanation in Hebrew}")
        }
    }

    data class Result(
        val model: String,
        val prompt: String,
        val negativePrompt: String?,
        val generatorGuess: String?,
        val foundUrl: String?,
        val foundPrompt: String?,
        val notes: String?,
        val sources: List<String>,
        val grounded: Boolean
    )

    class ApiException(val kind: Kind, message: String) : IOException(message)
    enum class Kind { AUTH, QUOTA, BAD_REQUEST, SERVER, NETWORK, NO_MODEL }

    fun listModels(apiKey: String): List<String> {
        val json = request("GET", "$baseUrl/v1beta/models?pageSize=1000", apiKey, null)
        val models = json.optJSONArray("models") ?: JSONArray()
        return (0 until models.length()).mapNotNull { i ->
            val m = models.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")
            val generates = methods == null || (0 until methods.length()).any { methods.getString(it) == "generateContent" }
            m.optString("name").takeIf { it.isNotEmpty() && generates }
        }
    }

    fun reconstruct(apiKey: String, jpeg: ByteArray, localCaption: String?, generator: String?, target: PromptTarget): Result {
        val model = chooseModel(listModels(apiKey)) ?: throw ApiException(Kind.NO_MODEL, "no Gemini Flash model available")
        val text = instructions(localCaption, generator, target)
        val response = try {
            generate(apiKey, model, jpeg, text, grounded = true)
        } catch (e: ApiException) {
            if (e.kind != Kind.BAD_REQUEST) throw e
            null
        }
        val (json, grounded) = if (response != null) response to true else generate(apiKey, model, jpeg, text, grounded = false) to false
        return parse(model, json, grounded)
    }

    private fun generate(apiKey: String, model: String, jpeg: ByteArray, text: String, grounded: Boolean): JSONObject {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                .put(JSONObject().put("text", text)))))
            if (grounded) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        return request("POST", "$baseUrl/v1beta/$model:generateContent", apiKey, body.toString())
    }

    fun parse(model: String, json: JSONObject, grounded: Boolean): Result {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw ApiException(Kind.SERVER, json.optJSONObject("promptFeedback")?.optString("blockReason")?.ifEmpty { null }
                ?: "empty response")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }.trim()
        if (text.isEmpty()) throw ApiException(Kind.SERVER, candidate.optString("finishReason").ifEmpty { "no text" })
        val sources = mutableListOf<String>()
        candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
            for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                (web.optString("title").ifEmpty { web.optString("uri") }).takeIf { it.isNotEmpty() }?.let { sources.add(it) }
            }
        }
        val obj = extractJson(text)
        if (obj == null) return Result(model, text, null, null, null, null, null, sources.distinct().take(5), grounded)
        val found = obj.optJSONObject("found_online")
        fun str(o: JSONObject?, k: String) = o?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
        return Result(
            model = model,
            prompt = str(obj, "prompt") ?: text,
            negativePrompt = str(obj, "negative_prompt"),
            generatorGuess = str(obj, "generator_guess"),
            foundUrl = str(found, "url"),
            foundPrompt = str(found, "prompt"),
            notes = str(obj, "notes"),
            sources = sources.distinct().take(5),
            grounded = grounded
        )
    }

    /** First JSON object in the text, tolerating markdown fences or prose around it. */
    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
    }

    private fun request(method: String, url: String, apiKey: String, body: String?): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            // The key goes in a header, never in the URL.
            setRequestProperty("x-goog-api-key", apiKey)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().decodeToString() }.orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code in 200..299) return json
            val error = json.optJSONObject("error")
            val message = (error?.optString("message").orEmpty().ifEmpty { "HTTP $code" }).take(200)
            val status = error?.optString("status").orEmpty()
            throw ApiException(
                when {
                    code == 401 || code == 403 || status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" ||
                        message.contains("API key", true) -> Kind.AUTH
                    code == 429 || status == "RESOURCE_EXHAUSTED" -> Kind.QUOTA
                    code in 400..499 -> Kind.BAD_REQUEST
                    else -> Kind.SERVER
                },
                message
            )
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }
}
