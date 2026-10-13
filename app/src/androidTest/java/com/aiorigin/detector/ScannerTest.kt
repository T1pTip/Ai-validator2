package com.aiorigin.detector

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end scan (metadata + visual check) of the device-QA fixtures. */
@RunWith(AndroidJUnit4::class)
class ScannerTest {
    private val ctx = TestFiles.target

    private fun scan(name: String) = runBlocking { Scanner.scan(ctx, TestFiles.fixture(name)) }

    @Test fun metadataVerdictsOutrankVisualEstimate() {
        assertEquals(ctx.getString(R.string.v_ai_tool), scan("qa_a1111.png").status)
        assertEquals(ctx.getString(R.string.v_ai_tool), scan("qa_comfyui.png").status)
        assertEquals(ctx.getString(R.string.v_ai_declared), scan("qa_iptc_ai.jpg").status)
    }

    @Test fun mediaWithoutStrongMetadataGetsVisualHeadline() {
        for (name in listOf("qa_camera.jpg", "qa_plain.jpg", "qa_c2pa_sample.jpg", "street.png")) {
            val r = scan(name)
            assertNotNull("$name visual", r.visual)
            assertTrue("$name visual headline", r.visualHeadline)
            val expected = when (VisualBand.of(r.visual!!.probability)) {
                VisualBand.LIKELY_AI -> R.string.v_visual_likely
                VisualBand.LEANS_AI -> R.string.v_visual_leans
                VisualBand.UNCLEAR -> R.string.v_visual_unclear
                VisualBand.NO_SIGNS -> R.string.v_visual_none
            }
            assertEquals(name, ctx.getString(expected), r.status)
            assertTrue("$name evidence lists the visual check",
                r.evidence.any { it.contains(LocalDetector.MODEL_NAME) })
        }
    }

    @Test fun everyFixtureGetsAVisualScore() {
        for (name in listOf("qa_a1111.png", "qa_comfyui.png", "qa_iptc_ai.jpg", "qa_prompt.webp", "qa_camera.jpg",
            "qa_plain.jpg", "qa_c2pa_sample.jpg")) {
            val r = scan(name)
            assertNotNull("$name visual score", r.visual)
            assertTrue("$name probability range", r.visual!!.probability in 0f..1f)
        }
    }
}
