import com.aiorigin.detector.FlorenceText
import com.aiorigin.detector.PromptBuilder
import com.aiorigin.detector.PromptTarget
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

/** args: VECTOR_DIR (from qa/clip_vectors.py). Uses the bundled vocab and the Florence goldens. */
fun main(args: Array<String>) {
    val dir = File(args[0])
    // 1. Pixel preprocessing equals the Pillow reference.
    for (png in dir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }) {
        val name = png.name.removeSuffix(".png")
        val img = ImageIO.read(png)
        val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val got = FlorenceText.pixelValues(argb, img.width, img.height)
        val exp = ByteBuffer.wrap(File(dir, "$name.florence.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        var max = 0f
        for (i in got.indices) max = maxOf(max, abs(got[i] - exp.get(i)))
        check("$name florence pixels", exp.remaining() == got.size && max <= 1e-5f, "max diff $max")
    }
    // 2. Decoding generated ids gives the reference captions.
    val vocab = FlorenceText.parseVocab(File("app/src/main/assets/florence/vocab.json").readText())
    check("vocab size", vocab.size == 50265, "${vocab.size}")
    val goldens = File("app/src/androidTest/assets/florence_goldens.json").readText()
    val entry = Regex("\"caption\": \"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"ids\": \\[([0-9,\\s]*)\\]")
    var n = 0
    for (m in entry.findAll(goldens)) {
        val caption = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
        val ids = m.groupValues[2].split(',').map { it.trim().toInt() }
        check("decode golden ${++n}", FlorenceText.decode(ids, vocab) == caption, FlorenceText.decode(ids, vocab))
    }
    check("goldens found", n >= 4, "$n")
    check("unicode decode", FlorenceText.decode(listOf(1121, 42, 2274), vocab) == "In this image")
    // 3. No-repeat trigram rule.
    check("banned trigram", FlorenceText.bannedNext(listOf(5, 6, 7, 5, 6)) == setOf(7))
    check("nothing banned", FlorenceText.bannedNext(listOf(5, 6, 7)).isEmpty())
    // 4. Prompt dialects.
    val caption = "A small yellow duck is standing in the grass. There are small white flowers on the grass around the duck."
    check("phrases", PromptBuilder.phrases(caption) == listOf("a small yellow duck standing in the grass", "small white flowers on the grass around the duck"),
        PromptBuilder.phrases(caption).toString())
    check("lead-in removed", PromptBuilder.phrases("In this image we can see a toy duck on the grass.") == listOf("a toy duck on the grass"))
    check("midjourney target", PromptBuilder.targetFor(listOf("midjourney")) == PromptTarget.MIDJOURNEY)
    check("flux target", PromptBuilder.targetFor(listOf("flux.2-klein-9b")) == PromptTarget.STABLE_DIFFUSION)
    check("comfyui target", PromptBuilder.targetFor(listOf("ComfyUI")) == PromptTarget.STABLE_DIFFUSION)
    check("dalle target", PromptBuilder.targetFor(listOf("dalle")) == PromptTarget.GENERAL)
    check("unknown target", PromptBuilder.targetFor(emptyList()) == PromptTarget.GENERAL)
    check("first hint wins", PromptBuilder.targetFor(listOf("midjourney", "flux")) == PromptTarget.MIDJOURNEY)
    val mj = PromptBuilder.build(caption, PromptTarget.MIDJOURNEY, 1024, 768)
    check("midjourney ar", mj.prompt.endsWith("--ar 4:3") && mj.negative == null, mj.prompt)
    val sd = PromptBuilder.build(caption, PromptTarget.STABLE_DIFFUSION, 512, 512)
    check("sd negative", sd.negative?.contains("blurry") == true && sd.prompt.contains("highly detailed"), sd.prompt)
    check("snapped ratio", PromptBuilder.aspectRatio(719, 480) == "3:2", PromptBuilder.aspectRatio(719, 480))
    check("portrait ratio", PromptBuilder.aspectRatio(1080, 1920) == "9:16")
    println("$passes/${passes + failures} Florence/prompt tests passed")
    if (failures > 0) exitProcess(1)
}
