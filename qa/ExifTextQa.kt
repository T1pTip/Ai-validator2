import com.aiorigin.detector.ExifText
import kotlin.system.exitProcess

private var failures = 0
private var passes = 0
private fun check(name: String, ok: Boolean) {
    if (ok) { passes++; println("PASS $name") } else { failures++; println("FAIL $name") }
}

fun main() {
    val params = "a cozy cabin\nSteps: 20, Sampler: Euler a, CFG scale: 7, Seed: 1, Size: 512x512"
    val be = "UNICODE\u0000".toByteArray(Charsets.ISO_8859_1) + params.toByteArray(Charsets.UTF_16BE)
    val le = "UNICODE\u0000".toByteArray(Charsets.ISO_8859_1) + params.toByteArray(Charsets.UTF_16LE)
    val ascii = "ASCII\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + "hello".toByteArray()
    val undefined = ByteArray(8) + "קקק prompt".toByteArray(Charsets.UTF_8)
    check("UTF-16BE (piexif)", ExifText.userComment(be) == params)
    check("UTF-16LE", ExifText.userComment(le) == params)
    check("ASCII", ExifText.userComment(ascii) == "hello")
    check("undefined code -> UTF-8", ExifText.userComment(undefined) == "קקק prompt")
    check("no prefix", ExifText.userComment("plain text".toByteArray()) == "plain text")
    check("null", ExifText.userComment(null) == null)
    check("only header", ExifText.userComment("UNICODE\u0000".toByteArray(Charsets.ISO_8859_1)) == null)
    check("odd length", ExifText.userComment(be + byteArrayOf(0x41))?.startsWith("a cozy cabin") == true)
    println("$passes/${passes + failures} EXIF text tests passed")
    if (failures > 0) exitProcess(1)
}
