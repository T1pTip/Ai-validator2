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
        var exact = 0
        for (i in 0 until goldens.length()) {
            val g = goldens.getJSONObject(i)
            val name = g.getString("file")
            val bitmap = LocalDetector.decode(ctx, TestFiles.fixture(name))!!
            val caption = FlorenceCaptioner.caption(ctx, LocalDetector.pixels(bitmap), bitmap.width, bitmap.height)
            bitmap.recycle()
            val expectedIds = (0 until g.getJSONArray("ids").length()).map { g.getJSONArray("ids").getInt(it) }
            Log.i("FlorenceTest", "$name ${caption.millis} ms: ${caption.text}")
            if (caption.ids == expectedIds) exact++
            else Log.w("FlorenceTest", "$name differs from reference: ${g.getString("caption")}")
            // quantized kernels can differ slightly per CPU; the first sentence must still agree.
            val firstSentence = g.getString("caption").substringBefore('.')
            assertTrue("$name: '${caption.text}' vs '${g.getString("caption")}'", caption.text.startsWith(firstSentence))
        }
        Log.i("FlorenceTest", "exact token match: $exact/${goldens.length()}")
        assertTrue("at least 3 of 4 captions identical to the reference ($exact)", exact >= 3)
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
