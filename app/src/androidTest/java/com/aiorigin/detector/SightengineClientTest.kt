package com.aiorigin.detector

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SightengineClientTest {
    private lateinit var server: MockWebServer
    private val creds = SightengineClient.Credentials("user123", "s3cret-value")

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun client() = SightengineClient(server.url("/").toString().trimEnd('/'))

    @Test fun uploadsMultipartAndParsesScores() {
        server.enqueue(MockResponse().setBody(
            """{"status":"success","request":{"id":"req_1","timestamp":1,"operations":5},
               "type":{"ai_generated":0.93,"ai_generators":{"midjourney":0.71,"flux":0.12,"dalle":0.02}},
               "media":{"id":"med_1","uri":"image.jpg"}}"""))
        val verdict = client().checkImage(creds, byteArrayOf(1, 2, 3, 4), "image.jpg")
        assertEquals(0.93f, verdict.aiProbability, 1e-6f)
        assertEquals("midjourney", verdict.generators.first().first)
        assertEquals(5, verdict.operations)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/1.0/check.json", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"models\"\r\n\r\ngenai"))
        assertTrue(body.contains("name=\"media\"; filename=\"image.jpg\""))
        assertTrue(body.contains("name=\"api_secret\"\r\n\r\ns3cret-value"))
        assertFalse("secret must not be in the URL", request.path!!.contains("s3cret"))
    }

    @Test fun urlCheckSendsFormBodyWithoutSecretInUrl() {
        server.enqueue(MockResponse().setBody("""{"status":"success","type":{"ai_generated":0.04}}"""))
        val verdict = client().checkUrl(creds, "https://example.com/a b.jpg")
        assertEquals(0.04f, verdict.aiProbability, 1e-6f)
        assertTrue(verdict.generators.isEmpty())
        val request = server.takeRequest()
        assertEquals("/1.0/check.json", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("models=genai"))
        assertTrue(body.contains("url=https%3A%2F%2Fexample.com%2Fa+b.jpg"))
    }

    private fun expectKind(code: Int, body: String, kind: SightengineClient.Kind) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        try {
            client().checkImage(creds, byteArrayOf(9))
            fail("expected $kind")
        } catch (e: SightengineClient.ApiException) {
            assertEquals(body, kind, e.kind)
        }
    }

    @Test fun errorsAreClassified() {
        expectKind(401, """{"status":"failure","error":{"type":"credentials_error","code":1,"message":"Incorrect api_user or api_secret"}}""",
            SightengineClient.Kind.AUTH)
        expectKind(429, """{"status":"failure","error":{"type":"usage_limit","message":"Daily usage limit reached"}}""",
            SightengineClient.Kind.QUOTA)
        expectKind(400, """{"status":"failure","error":{"type":"media_error","message":"Media could not be read"}}""",
            SightengineClient.Kind.BAD_MEDIA)
        expectKind(500, "<html>oops</html>", SightengineClient.Kind.SERVER)
        expectKind(200, """{"status":"success","type":{}}""", SightengineClient.Kind.SERVER)
    }

    @Test fun networkFailureIsReported() {
        val dead = SightengineClient("http://127.0.0.1:1")
        try {
            dead.checkImage(creds, byteArrayOf(1))
            fail("expected network error")
        } catch (e: SightengineClient.ApiException) {
            assertEquals(SightengineClient.Kind.NETWORK, e.kind)
        }
    }

    @Test fun videoAggregationReportsHighestFrame() {
        val outcome = CloudCheck.aggregate(listOf(
            SightengineClient.Verdict(0.2f, listOf("flux" to 0.1f), 5),
            SightengineClient.Verdict(0.8f, listOf("sora" to 0.6f), 5),
            SightengineClient.Verdict(0.5f, emptyList(), null)
        ))
        assertEquals(0.8f, outcome.probability, 1e-6f)
        assertEquals("sora", outcome.topGenerator?.first)
        assertEquals(3, outcome.frames)
        assertEquals(10, outcome.operations)
    }

    @Test fun secretsRoundTripEncrypted() {
        val store = SecretStore(TestFiles.target)
        store.clear()
        assertEquals(null, store.load())
        store.save(SightengineClient.Credentials(" u-42 ", "sec ret"))
        assertEquals(SightengineClient.Credentials("u-42", "sec ret"), store.load())
        val raw = TestFiles.target.getSharedPreferences("secrets", 0).all.values.joinToString()
        assertFalse("stored in plain text", raw.contains("sec ret") || raw.contains("u-42"))
        store.clear()
        assertEquals(null, store.load())
    }
}
