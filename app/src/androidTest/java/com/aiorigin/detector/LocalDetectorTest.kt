package com.aiorigin.detector

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.math.abs

/**
 * The on-device pipeline (Android decoder + Kotlin preprocessing + ONNX Runtime) must reproduce
 * the Pillow/onnxruntime reference scores in goldens.json (tools/model/goldens.py).
 */
@RunWith(AndroidJUnit4::class)
class LocalDetectorTest {
    private val goldens = JSONArray(TestFiles.asset("goldens.json"))

    @Test fun modelIsBundled() {
        assertTrue("model asset missing", LocalDetector.isAvailable(TestFiles.target))
    }

    @Test fun decodedPixelsMatchPillow() {
        for (i in 0 until goldens.length()) {
            val g = goldens.getJSONObject(i)
            val name = g.getString("file")
            // JPEG decoders may legitimately differ by rounding; PNG and lossless paths must be exact.
            if (!name.endsWith(".png")) continue
            val bitmap = LocalDetector.decode(TestFiles.target, TestFiles.fixture(name))!!
            val argb = LocalDetector.pixels(bitmap)
            val rgb = ByteArray(argb.size * 3)
            for (p in argb.indices) {
                rgb[p * 3] = (argb[p] ushr 16).toByte(); rgb[p * 3 + 1] = (argb[p] ushr 8).toByte(); rgb[p * 3 + 2] = argb[p].toByte()
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(rgb).joinToString("") { "%02x".format(it) }
            assertEquals("$name size", g.getInt("width") to g.getInt("height"), bitmap.width to bitmap.height)
            assertEquals("$name decoded pixels", g.getString("rgbSha256"), sha)
            bitmap.recycle()
        }
    }

    @Test fun scoresMatchReference() {
        for (i in 0 until goldens.length()) {
            val g = goldens.getJSONObject(i)
            val name = g.getString("file")
            val score = LocalDetector.scoreImage(TestFiles.target, TestFiles.fixture(name))
            val logit = score.logit.toDouble()
            val expected = g.getDouble("logit")
            Log.i("LocalDetectorTest", "$name logit=$logit expected=$expected p=${score.probability} ${score.millis}ms")
            // PNG: identical pixels, so only float-kernel differences remain. JPEG: decoder rounding too.
            val tolerance = if (name.endsWith(".png")) 0.02 else 0.35
            assertTrue("$name logit $logit vs $expected", abs(logit - expected) <= tolerance)
        }
    }
}
