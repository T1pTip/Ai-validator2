package com.aiorigin.detector

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Share an image into the app, see the verdict and the visual section, then run a (mocked) cloud check. */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var server: MockWebServer
    private val ctx = TestFiles.target

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        SightengineClient.endpoint = server.url("/").toString().trimEnd('/')
        SecretStore(ctx).save(SightengineClient.Credentials("flow-user", "flow-secret"))
    }

    @After fun tearDown() {
        SightengineClient.endpoint = SightengineClient.DEFAULT_BASE
        SecretStore(ctx).clear()
        server.shutdown()
    }

    private fun share(name: String): ActivityScenario<MainActivity> {
        val intent = Intent(Intent.ACTION_SEND).apply {
            setClassName(ctx, MainActivity::class.java.name)
            type = if (name.endsWith(".png")) "image/png" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, TestFiles.fixture(name))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return ActivityScenario.launch(intent)
    }

    @Test fun sharedPhotoShowsVisualVerdictAndCloudSecondOpinion() {
        server.enqueue(MockResponse().setBody(
            """{"status":"success","request":{"operations":5},"type":{"ai_generated":0.12,"ai_generators":{"flux":0.05}}}"""))
        share("street.png").use {
            compose.waitUntil(120_000) { compose.onAllNodes(hasTestTag("verdict")).fetchSemanticsNodes().isNotEmpty() }
            // The headline also appears in the notice banner, so there may be two matching nodes.
            compose.onAllNodesWithText(ctx.getString(R.string.v_visual_none)).onFirst().assertExists()
            compose.onNodeWithTag("visual").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("cloud_button").performScrollTo().performClick()
            // Nothing is sent before the user confirms.
            assertEquals(0, server.requestCount)
            compose.onNodeWithTag("cloud_confirm").performClick()
            compose.waitUntil(60_000) { compose.onAllNodes(hasTestTag("cloud_result")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(ctx.getString(R.string.cloud_result, 12)).assertExists()
            assertEquals(1, server.requestCount)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("flow-secret") && body.contains("name=\"media\""))
        }
    }

    @Test fun metadataVerdictStillWinsForGenerationFiles() {
        share("qa_a1111.png").use {
            compose.waitUntil(120_000) { compose.onAllNodes(hasTestTag("verdict")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(ctx.getString(R.string.v_ai_tool)).onFirst().assertExists()
            compose.onNodeWithTag("visual").performScrollTo().assertIsDisplayed()
        }
    }
}
