package com.aiorigin.detector

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads media the user explicitly asked to check, by link. Only runs on user action.
 * A link to a web page is resolved to the page's main media (og:image / og:video).
 * Note: websites and CDNs often strip metadata, so a downloaded copy may carry less
 * evidence than the original file.
 */
object UrlFetcher {
    const val MAX_BYTES = 200L * 1024 * 1024
    private const val MAX_HTML = 1_000_000
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Mobile AIOriginDetector"

    class FetchException(val reason: Reason, message: String) : IOException(message)
    enum class Reason { INVALID, NOT_MEDIA, TOO_BIG, HTTP, NETWORK }

    data class Fetched(
        val file: File,
        val contentType: String,
        val finalUrl: String,
        val bytes: Long,
        val pageUrl: String?
    )

    private val urlInText = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val bareDomain = Regex("^[\\w.-]+\\.[a-z]{2,}(/\\S*)?$", RegexOption.IGNORE_CASE)

    /** Finds the first link in user text (shared text may contain other words). HTTP is upgraded to HTTPS. */
    fun normalize(input: String): String? {
        val text = input.trim()
        val found = urlInText.find(text)?.value ?: if (bareDomain.matches(text)) "https://$text" else return null
        val cleaned = found.trimEnd('.', ',', ')', ']', '}', '!', '?')
        val https = if (cleaned.startsWith("http://", ignoreCase = true)) "https://" + cleaned.substring(7) else cleaned
        return try { URL(https).toString() } catch (_: Exception) { null }
    }

    private val metaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attr = Regex("(\\w[\\w:-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val preferred = listOf("og:video:secure_url", "og:video:url", "og:video", "og:image:secure_url", "og:image:url",
        "og:image", "twitter:image", "twitter:image:src")

    /** Main media URL declared by a web page, resolved against the page URL. Videos are preferred. */
    fun mediaUrlFromHtml(html: String, pageUrl: String): String? {
        val found = mutableMapOf<String, String>()
        for (tag in metaTag.findAll(html)) {
            val attrs = attr.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
            val key = (attrs["property"] ?: attrs["name"])?.lowercase() ?: continue
            val content = attrs["content"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            found.putIfAbsent(key, XmpFields.decodeEntities(content))
        }
        val raw = preferred.firstNotNullOfOrNull { found[it] } ?: return null
        return try { URL(URL(pageUrl), raw).toString().replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://") } catch (_: Exception) { null }
    }

    fun fetch(url: String, target: File): Fetched = fetch(url, target, null, 0)

    private fun fetch(url: String, target: File, pageUrl: String?, depth: Int): Fetched {
        val connection = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) {
            throw FetchException(Reason.INVALID, e.javaClass.simpleName)
        }
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "image/*,video/*,text/html;q=0.8,*/*;q=0.5")
            val code = try { connection.responseCode } catch (e: IOException) {
                throw FetchException(Reason.NETWORK, e.javaClass.simpleName)
            }
            if (code !in 200..299) throw FetchException(Reason.HTTP, "HTTP $code")
            val type = (connection.contentType ?: "").substringBefore(';').trim().lowercase()
            val length = connection.contentLengthLong
            if (length > MAX_BYTES) throw FetchException(Reason.TOO_BIG, "$length")
            val finalUrl = connection.url.toString()
            if (type.startsWith("text/html") || type.startsWith("application/xhtml")) {
                if (depth > 0) throw FetchException(Reason.NOT_MEDIA, type)
                val html = connection.inputStream.use { readText(it, MAX_HTML) }
                val media = mediaUrlFromHtml(html, finalUrl) ?: throw FetchException(Reason.NOT_MEDIA, type)
                return fetch(media, target, finalUrl, depth + 1)
            }
            if (!(type.startsWith("image/") || type.startsWith("video/") || type == "application/octet-stream" || type.isEmpty())) {
                throw FetchException(Reason.NOT_MEDIA, type)
            }
            val bytes = connection.inputStream.use { copy(it, target) }
            return Fetched(target, type, finalUrl, bytes, pageUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun readText(input: InputStream, limit: Int): String {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val n = input.read(buffer, total, limit - total)
            if (n <= 0) break
            total += n
        }
        return String(buffer, 0, total, Charsets.UTF_8)
    }

    private fun copy(input: InputStream, target: File): Long {
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw FetchException(Reason.NETWORK, "cancelled")
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw FetchException(Reason.TOO_BIG, "$total")
                out.write(buffer, 0, n)
            }
        }
        return total
    }
}
