package com.aiorigin.detector

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeminiClientTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }
    private fun client() = GeminiClient(server.url("/").toString().trimEnd('/'))
    private val models = """{"models":[
        {"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]},
        {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},
        {"name":"models/gemini-3.6-flash","supportedGenerationMethods":["generateContent"]},
        {"name":"models/gemini-4.0-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
        {"name":"models/text-embedding-005","supportedGenerationMethods":["embedContent"]}]}"""

    @Test fun choosesNewestStableFlash() {
        assertEquals("models/gemini-3.8-flash", GeminiClient.chooseModel(listOf(
            "models/gemini-3.5-flash-lite", "models/gemini-3.8-flash", "models/gemini-3.6-flash", "models/gemini-4.0-flash-preview-tts")))
        assertEquals("models/gemini-4.1-flash-preview", GeminiClient.chooseModel(listOf("models/gemini-4.1-flash-preview", "models/gemini-3.5-flash-lite")))
        assertEquals("models/gemini-3.5-flash-lite", GeminiClient.chooseModel(listOf("models/gemini-3.5-flash-lite")))
        assertNull(GeminiClient.chooseModel(listOf("models/gemini-2.5-pro")))
    }

    @Test fun reconstructsWithGroundingAndParsesFoundPrompt() {
        server.enqueue(MockResponse().setBody(models))
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"Here you go: {\"prompt\": \"a duckling\", \"negative_prompt\": \"blurry\", \"generator_guess\": \"Midjourney\", \"found_online\": {\"url\": \"https://civitai.com/images/1\", \"prompt\": \"cute duckling, clover\"}, \"notes\": \"נמצא\"}"}]},
            "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://civitai.com/images/1","title":"civitai.com"}},{"web":{"uri":"https://x","title":"civitai.com"}}]}}]}"""))
        val r = client().reconstruct("k1", byteArrayOf(1, 2, 3), "A duck.", "midjourney", PromptTarget.MIDJOURNEY)
        assertEquals("models/gemini-3.8-flash", r.model)
        assertEquals("a duckling", r.prompt)
        assertEquals("blurry", r.negativePrompt)
        assertEquals("https://civitai.com/images/1", r.foundUrl)
        assertEquals("cute duckling, clover", r.foundPrompt)
        assertEquals(listOf("civitai.com"), r.sources)
        assertTrue(r.grounded)
        assertEquals("/v1beta/models?pageSize=1000", server.takeRequest().path)
        val gen = server.takeRequest()
        val body = gen.body.readUtf8()
        assertTrue(body.contains("google_search") && body.contains("\"mime_type\":\"image\\/jpeg\"") && body.contains("Midjourney"))
        assertFalse(gen.path!!.contains("k1"))
    }

    @Test fun retriesWithoutSearchWhenGroundingIsRejected() {
        server.enqueue(MockResponse().setBody(models))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"Search grounding is not supported","status":"INVALID_ARGUMENT"}}"""))
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"plain prompt text without json"}]}}]}"""))
        val r = client().reconstruct("k1", byteArrayOf(1), null, null, PromptTarget.GENERAL)
        assertFalse(r.grounded)
        assertEquals("plain prompt text without json", r.prompt)
        server.takeRequest(); server.takeRequest()
        assertFalse(server.takeRequest().body.readUtf8().contains("google_search"))
    }

    @Test fun errorsAreClassified() {
        fun expect(code: Int, body: String, kind: GeminiClient.Kind) {
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            try { client().listModels("bad"); fail("expected $kind") } catch (e: GeminiClient.ApiException) { assertEquals(body, kind, e.kind) }
        }
        expect(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""", GeminiClient.Kind.AUTH)
        expect(429, """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}""", GeminiClient.Kind.QUOTA)
        expect(503, """{"error":{"code":503,"message":"overloaded","status":"UNAVAILABLE"}}""", GeminiClient.Kind.SERVER)
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-2.5-pro"}]}"""))
        try { client().reconstruct("k", byteArrayOf(1), null, null, PromptTarget.GENERAL); fail() } catch (e: GeminiClient.ApiException) {
            assertEquals(GeminiClient.Kind.NO_MODEL, e.kind)
        }
    }
}
