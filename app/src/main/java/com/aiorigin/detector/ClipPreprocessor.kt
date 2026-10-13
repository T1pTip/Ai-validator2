package com.aiorigin.detector

/**
 * Pure-JVM image preprocessing for the on-device detector, written to match the Hugging Face
 * CLIPImageProcessor used when the model was trained/exported, bit for bit where possible:
 *  1. resize so the shortest edge is [SHORT_EDGE] (long edge truncated like transformers),
 *     using Pillow's antialiased bicubic filter in 8-bit fixed point (two passes, horizontal first);
 *  2. center crop to [CROP] (offsets rounded down, like transformers);
 *  3. rescale to 0..1 and normalise with the CLIP mean/std, NCHW float layout.
 * No Android classes are used, so the same code is verified against Pillow on the JVM.
 */
object ClipPreprocessor {
    const val SHORT_EDGE = 440
    const val CROP = 384
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    /** Output size of the shortest-edge resize, as transformers computes it. */
    fun resizedSize(width: Int, height: Int): Pair<Int, Int> =
        if (width <= height) SHORT_EDGE to (SHORT_EDGE.toLong() * height / width).toInt()
        else (SHORT_EDGE.toLong() * width / height).toInt() to SHORT_EDGE

    /** [argb] holds width*height packed ARGB pixels (alpha is ignored, as Pillow's convert("RGB") does). */
    fun toTensor(argb: IntArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0 && argb.size >= width * height)
        val (rw, rh) = resizedSize(width, height)
        val rgb = toRgbPlanes(argb, width, height)
        val resized = PillowBicubic.resize(rgb, width, height, rw, rh)
        val left = (rw - CROP) / 2
        val top = (rh - CROP) / 2
        val out = FloatArray(3 * CROP * CROP)
        for (c in 0 until 3) {
            val plane = resized[c]
            val base = c * CROP * CROP
            for (y in 0 until CROP) {
                val row = (top + y) * rw + left
                for (x in 0 until CROP) {
                    val v = (plane[row + x].toInt() and 0xff) / 255f
                    out[base + y * CROP + x] = (v - MEAN[c]) / STD[c]
                }
            }
        }
        return out
    }

    private fun toRgbPlanes(argb: IntArray, width: Int, height: Int): Array<ByteArray> {
        val n = width * height
        val r = ByteArray(n); val g = ByteArray(n); val b = ByteArray(n)
        for (i in 0 until n) {
            val p = argb[i]
            r[i] = (p ushr 16).toByte(); g[i] = (p ushr 8).toByte(); b[i] = p.toByte()
        }
        return arrayOf(r, g, b)
    }
}

/**
 * Port of Pillow's ImagingResample (libImaging/Resample.c) for 8-bit channels with the
 * BICUBIC filter (a = -0.5): per-axis normalised coefficients, 22-bit fixed point, rounding
 * and clipping to 0..255 after each pass. Horizontal pass first, then vertical, as Pillow does.
 */
object PillowBicubic {
    private const val PRECISION_BITS = 32 - 8 - 2
    private const val SUPPORT = 2.0

    private fun bicubic(xIn: Double): Double {
        val a = -0.5
        val x = if (xIn < 0) -xIn else xIn
        return when {
            x < 1.0 -> ((a + 2.0) * x - (a + 3.0)) * x * x + 1
            x < 2.0 -> (((x - 5) * x + 8) * x - 4) * a
            else -> 0.0
        }
    }

    private class Coeffs(val bounds: IntArray, val kk: IntArray, val ksize: Int)

    private fun precompute(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterscale = if (scale < 1.0) 1.0 else scale
        val support = SUPPORT * filterscale
        val ksize = Math.ceil(support).toInt() * 2 + 1
        val kk = IntArray(outSize * ksize)
        val bounds = IntArray(outSize * 2)
        val k = DoubleArray(ksize)
        val ss = 1.0 / filterscale
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            var ww = 0.0
            for (x in 0 until xmax) {
                val w = bicubic((x + xmin - center + 0.5) * ss)
                k[x] = w
                ww += w
            }
            for (x in 0 until xmax) {
                val w = if (ww != 0.0) k[x] / ww else k[x]
                // Pillow: (int)(0.5 + k * (1 << PRECISION_BITS)) for k >= 0, (int)(-0.5 + ...) otherwise.
                val scaled = w * (1 shl PRECISION_BITS)
                kk[xx * ksize + x] = if (scaled < 0) (-0.5 + scaled).toInt() else (0.5 + scaled).toInt()
            }
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coeffs(bounds, kk, ksize)
    }

    private fun clip8(v: Int): Byte {
        val shifted = v shr PRECISION_BITS
        return (if (shifted < 0) 0 else if (shifted > 255) 255 else shifted).toByte()
    }

    /** Resamples each 8-bit plane from inW x inH to outW x outH. */
    fun resize(planes: Array<ByteArray>, inW: Int, inH: Int, outW: Int, outH: Int): Array<ByteArray> {
        val needH = outW != inW
        val needV = outH != inH
        val hc = if (needH) precompute(inW, outW) else null
        val vc = if (needV) precompute(inH, outH) else null
        // Pillow only horizontally resamples the rows the vertical pass will read.
        var yFirst = 0
        var yLast = inH
        if (vc != null) {
            yFirst = vc.bounds[0]
            yLast = vc.bounds[(outH - 1) * 2] + vc.bounds[(outH - 1) * 2 + 1]
            for (yy in 0 until outH) vc.bounds[yy * 2] -= yFirst
        }
        val midH = yLast - yFirst
        val half = 1 shl (PRECISION_BITS - 1)
        return Array(planes.size) { c ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val src = planes[c]
            val mid: ByteArray
            val midW: Int
            if (hc != null) {
                mid = ByteArray(outW * midH)
                midW = outW
                for (y in 0 until midH) {
                    val rowIn = (y + yFirst) * inW
                    val rowOut = y * outW
                    for (xx in 0 until outW) {
                        val xmin = hc.bounds[xx * 2]
                        val xmax = hc.bounds[xx * 2 + 1]
                        val kBase = xx * hc.ksize
                        var ss = half
                        for (x in 0 until xmax) ss += (src[rowIn + x + xmin].toInt() and 0xff) * hc.kk[kBase + x]
                        mid[rowOut + xx] = clip8(ss)
                    }
                }
            } else {
                midW = inW
                mid = if (yFirst == 0 && midH == inH) src else src.copyOfRange(yFirst * inW, yLast * inW)
            }
            if (vc == null) return@Array mid
            val out = ByteArray(midW * outH)
            for (yy in 0 until outH) {
                val ymin = vc.bounds[yy * 2]
                val ymax = vc.bounds[yy * 2 + 1]
                val kBase = yy * vc.ksize
                val rowOut = yy * midW
                for (xx in 0 until midW) {
                    var ss = half
                    for (y in 0 until ymax) ss += (mid[(y + ymin) * midW + xx].toInt() and 0xff) * vc.kk[kBase + y]
                    out[rowOut + xx] = clip8(ss)
                }
            }
            out
        }
    }
}
