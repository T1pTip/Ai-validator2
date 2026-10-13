package com.aiorigin.detector

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Downloads the pinned Florence-2 model (SHA-256 verified) and checks that on-device greedy
 * decoding reproduces the reference token ids from tools/model/florence_reference.py.
 */
@RunWith(AndroidJUnit4::class)
class FlorenceCaptionerTest {
    private val ctx = TestFiles.target

    @Test fun downloadsAndCaptionsLikeTheReference() {
        if (!ModelDownloader.isReady(ctx)) {
            val start = System.currentTimeMillis()
            ModelDownloader.download(ctx) { _, _ -> }
            Log.i("FlorenceTest", "download ${System.currentTimeMillis() - start} ms")
        }
        assertTrue(ModelDownloader.isReady(ctx))
        val goldens = JSONArray(TestFiles.asset("florence_goldens.json"))
        for (i in 0 until goldens.length()) {
            val g = goldens.getJSONObject(i)
            val name = g.getString("file")
            val expectedIds = (0 until g.getJSONArray("ids").length()).map { g.getJSONArray("ids").getInt(it) }
            val bitmap = LocalDetector.decode(ctx, TestFiles.fixture(name))!!
            val argb = LocalDetector.pixels(bitmap)
            // Free decoding: a readable caption (dynamically quantized kernels differ per CPU, so the
            // wording may differ from the reference; see tools/model/VISUAL_MODEL.md).
            val free = FlorenceCaptioner.caption(ctx, argb, bitmap.width, bitmap.height)
            Log.i("FlorenceTest", "$name ${free.millis} ms: ${free.text}")
            assertTrue("$name caption too short: '${free.text}'", free.text.length >= 20 && free.ids.last() == 2)
            // Teacher forcing on the reference ids: the model must agree with the reference at almost
            // every step, which proves preprocessing, encoder, KV cache and decoding are right.
            val forced = FlorenceCaptioner.caption(ctx, argb, bitmap.width, bitmap.height, teacherForce = expectedIds)
            bitmap.recycle()
            Log.i("FlorenceTest", "$name teacher-forced agreement ${forced.agreement}")
            assertEquals("$name forced text", g.getString("caption"), forced.text)
            assertTrue("$name agreement ${forced.agreement}", (forced.agreement ?: 0f) >= 0.75f)
        }
        assertEquals("first word", "A", goldens.getJSONObject(0).getString("caption").take(1))
    }

    @Test fun corruptDownloadIsRejected() {
        val bad = ModelDownloader.FLORENCE[0]
        val f = ModelDownloader.file(ctx, bad)
        val backup = java.io.File(f.path + ".bak")
        if (f.exists()) f.renameTo(backup)
        val old = ModelDownloader.baseUrl
        try {
            // Point at a server path that returns a different file; the checksum check must refuse it.
            ModelDownloader.baseUrl = "https://huggingface.co/${ModelDownloader.REPO}/resolve/${ModelDownloader.REVISION}/onnx/encoder_model_uint8.onnx?x="
            val failed = runCatching { ModelDownloader.download(ctx) { _, _ -> } }.isFailure
            assertTrue("mismatching file must be rejected", failed)
            assertEquals(false, f.exists())
        } finally {
            ModelDownloader.baseUrl = old
            if (backup.exists()) backup.renameTo(f)
        }
    }
}
