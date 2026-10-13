package com.aiorigin.detector

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Optional cloud second opinion (Sightengine "genai" model). Only called after the user
 * confirms each upload. Keys are stored encrypted on the device ([SecretStore]).
 */
class SightengineClient(private val baseUrl: String = endpoint) {
    companion object {
        const val DEFAULT_BASE = "https://api.sightengine.com"
        /** Replaced only by instrumentation tests, which point it at a local mock server. */
        @Volatile var endpoint: String = DEFAULT_BASE
        private const val TIMEOUT_MS = 30_000
    }

    data class Credentials(val user: String, val secret: String)

    data class Verdict(
        val aiProbability: Float,
        /** Generator name -> score, highest first; empty when the service did not return any. */
        val generators: List<Pair<String, Float>>,
        val operations: Int?
    )

    class ApiException(val kind: Kind, message: String) : IOException(message)
    enum class Kind { AUTH, QUOTA, BAD_MEDIA, SERVER, NETWORK }

    fun checkImage(credentials: Credentials, bytes: ByteArray, fileName: String = "image.jpg"): Verdict {
        val boundary = "----aiorigin" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream().apply {
            fun field(name: String, value: String) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("models", "genai")
            field("api_user", credentials.user)
            field("api_secret", credentials.secret)
            write("--$boundary\r\nContent-Disposition: form-data; name=\"media\"; filename=\"$fileName\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val connection = open("$baseUrl/1.0/check.json")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setFixedLengthStreamingMode(body.size)
        return execute(connection) { it.outputStream.use { out -> out.write(body) } }
    }

    /** For media checked by link: the service fetches the public URL itself, nothing is uploaded. */
    fun checkUrl(credentials: Credentials, mediaUrl: String): Verdict {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        // Sent as a form body rather than a query string, so the secret never appears in a URL.
        val body = "models=genai&url=${enc(mediaUrl)}&api_user=${enc(credentials.user)}&api_secret=${enc(credentials.secret)}"
            .toByteArray()
        val connection = open("$baseUrl/1.0/check.json")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.setFixedLengthStreamingMode(body.size)
        return execute(connection) { it.outputStream.use { out -> out.write(body) } }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = false
    }

    private fun execute(connection: HttpURLConnection, send: (HttpURLConnection) -> Unit): Verdict {
        try {
            send(connection)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            return parse(code, text)
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(httpCode: Int, text: String): Verdict {
        val json = try { JSONObject(text) } catch (_: Exception) {
            throw ApiException(Kind.SERVER, "HTTP $httpCode")
        }
        if (json.optString("status") != "success") {
            val error = json.optJSONObject("error")
            val message = error?.optString("message").orEmpty().ifEmpty { "HTTP $httpCode" }
            // Classified by HTTP status and wording; the exact error codes are not relied on.
            val kind = when {
                httpCode == 401 || httpCode == 403 || message.contains("credential", true) ||
                    message.contains("api_user", true) || message.contains("api_secret", true) -> Kind.AUTH
                httpCode == 429 || message.contains("limit", true) || message.contains("quota", true) -> Kind.QUOTA
                httpCode in 400..499 || message.contains("media", true) || message.contains("image", true) -> Kind.BAD_MEDIA
                else -> Kind.SERVER
            }
            throw ApiException(kind, message.take(200))
        }
        val type = json.optJSONObject("type") ?: throw ApiException(Kind.SERVER, "missing type")
        if (!type.has("ai_generated")) throw ApiException(Kind.SERVER, "missing ai_generated")
        val probability = type.getDouble("ai_generated").toFloat().coerceIn(0f, 1f)
        val generators = mutableListOf<Pair<String, Float>>()
        type.optJSONObject("ai_generators")?.let { g ->
            for (key in g.keys()) generators.add(key to g.optDouble(key, 0.0).toFloat())
        }
        generators.sortByDescending { it.second }
        val operations = json.optJSONObject("request")?.let { if (it.has("operations")) it.optInt("operations") else null }
        return Verdict(probability, generators, operations)
    }
}
