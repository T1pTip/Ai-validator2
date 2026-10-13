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

    @Test fun videoGetsFrameScoresAndCloudFrames() {
        val uri = TestFiles.fixture("clip.mp4")
        val r = runBlocking { Scanner.scan(ctx, uri, typeHint = "video/mp4") }
        assertTrue("detected as video", r.isVideo)
        val v = r.visual
        assertNotNull("video visual score", v)
        assertTrue("frames scored: ${v!!.frames}", v.frames in 1..3)
        assertEquals(v.frames, v.frameProbabilities.size)
        assertEquals(v.frameProbabilities.max(), v.probability, 1e-6f)
        val frames = CloudCheck.prepareVideoFrames(ctx, uri)
        assertTrue("cloud frames: ${frames.size}", frames.size in 1..CloudCheck.VIDEO_FRAMES)
        frames.forEach { assertTrue("JPEG", it.size > 1000 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte()) }
    }

    @Test fun smallImagesAreUploadedUnchanged() {
        val uri = TestFiles.fixture("qa_camera.jpg")
        val original = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(original.contentEquals(CloudCheck.prepareImage(ctx, uri)))
    }
}
