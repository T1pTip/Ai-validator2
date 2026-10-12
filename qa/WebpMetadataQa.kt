import com.aiorigin.detector.WebpMetadataReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private fun chunk(tag: String, content: ByteArray): ByteArray = ByteArrayOutputStream().apply {
    write(tag.toByteArray(Charsets.US_ASCII))
    write(intLE(content.size))
    write(content)
    if (content.size % 2 != 0) write(0)
}.toByteArray()
private fun intLE(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
private fun webp(vararg chunks: ByteArray): ByteArray {
    val body = chunks.fold(ByteArray(0)) { a, b -> a + b }
    return "RIFF".toByteArray() + intLE(body.size + 4) + "WEBP".toByteArray() + body
}
private fun parse(bytes: ByteArray) = WebpMetadataReader.read(ByteArrayInputStream(bytes))
fun main() {
    var passed = 0
    fun checkCase(name: String, condition: Boolean) { check(condition) { name }; passed++; println("PASS $name") }
    val xmp = "<x:xmpmeta>negative_prompt: blur</x:xmpmeta>"
    checkCase("XMP extraction", parse(webp(chunk("XMP ", xmp.toByteArray())))["xmp"] == xmp)
    checkCase("odd-size chunk padding", parse(webp(chunk("VP8 ", byteArrayOf(1,2,3)), chunk("XMP ", "hi".toByteArray())))["xmp"] == "hi")
    checkCase("empty file", parse(byteArrayOf()).isEmpty())
    checkCase("wrong RIFF", parse("NOPE".toByteArray() + webp()).isEmpty())
    checkCase("wrong WEBP", parse("RIFF".toByteArray() + intLE(4) + "WAVE".toByteArray()).isEmpty())
    checkCase("truncated chunk", parse(webp(chunk("XMP ", "prompt".toByteArray())).dropLast(3).toByteArray()).isEmpty())
    checkCase("oversized chunk", parse(webp(chunk("XMP ", ByteArray(2 * 1024 * 1024 + 1)))).isEmpty())
    checkCase("malformed chunk length", parse("RIFF".toByteArray() + intLE(20) + "WEBP".toByteArray() + "XMP ".toByteArray() + intLE(Int.MAX_VALUE)).isEmpty())
    checkCase("unicode", parse(webp(chunk("XMP ", "שלום 🎨".toByteArray())))["xmp"] == "שלום 🎨")
    checkCase("unrelated chunk", parse(webp(chunk("VP8 ", byteArrayOf(1,2,3,4)))).isEmpty())
    println("$passed/10 WebP QA passed")
}
