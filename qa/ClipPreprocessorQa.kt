import com.aiorigin.detector.ClipPreprocessor
import com.aiorigin.detector.PillowBicubic
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

private var failures = 0
private var passes = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { passes++; println("PASS $name") } else { failures++; println("FAIL $name $detail") }
}

/** Compares the Kotlin port with Pillow vectors written by qa/clip_vectors.py (dir in argv[0]). */
fun main(args: Array<String>) {
    val dir = File(args.getOrNull(0) ?: error("usage: ClipPreprocessorQa VECTOR_DIR"))
    val inputs = dir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
    check("vectors present", inputs.size >= 7, "found ${inputs.size}")
    for (png in inputs) {
        val name = png.name.removeSuffix(".png")
        val image = ImageIO.read(png)
        val w = image.width
        val h = image.height
        val argb = image.getRGB(0, 0, w, h, null, 0, w)

        val (rw, rh) = ClipPreprocessor.resizedSize(w, h)
        val expectedResized = File(dir, "$name.resized.rgb").readBytes()
        check("$name resized size", expectedResized.size == rw * rh * 3, "${rw}x$rh vs ${expectedResized.size}")
        val planes = arrayOf(ByteArray(w * h), ByteArray(w * h), ByteArray(w * h))
        for (i in 0 until w * h) {
            planes[0][i] = (argb[i] ushr 16).toByte(); planes[1][i] = (argb[i] ushr 8).toByte(); planes[2][i] = argb[i].toByte()
        }
        val resized = PillowBicubic.resize(planes, w, h, rw, rh)
        var mismatches = 0
        for (i in 0 until rw * rh) for (c in 0 until 3) {
            if (resized[c][i] != expectedResized[i * 3 + c]) mismatches++
        }
        check("$name bicubic matches Pillow exactly", mismatches == 0, "$mismatches of ${rw * rh * 3} bytes differ")

        val tensor = ClipPreprocessor.toTensor(argb, w, h)
        val expected = ByteBuffer.wrap(File(dir, "$name.tensor.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        check("$name tensor length", expected.remaining() == tensor.size)
        var maxDiff = 0f
        for (i in tensor.indices) maxDiff = maxOf(maxDiff, abs(tensor[i] - expected.get(i)))
        check("$name tensor within 1e-5 of reference", maxDiff <= 1e-5f, "max diff $maxDiff")
    }
    println("$passes/${passes + failures} CLIP preprocessing tests passed")
    if (failures > 0) exitProcess(1)
}
