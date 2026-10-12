import com.aiorigin.detector.PngMetadataReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

fun chunk(type: String, data: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    output.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array())
    val name = type.toByteArray(Charsets.US_ASCII)
    output.write(name)
    output.write(data)
    val crc = CRC32().apply { update(name); update(data) }.value.toInt()
    output.write(java.nio.ByteBuffer.allocate(4).putInt(crc).array())
    return output.toByteArray()
}
fun png(vararg chunks: ByteArray): ByteArray = ByteArrayOutputStream().apply {
    write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
    chunks.forEach { write(it) }
    write(chunk("IEND", byteArrayOf()))
}.toByteArray()
fun compressed(data: ByteArray): ByteArray = ByteArrayOutputStream().also {
    DeflaterOutputStream(it).use { zip -> zip.write(data) }
}.toByteArray()
fun verify(condition: Boolean, label: String) {
    check(condition) { "FAIL: $label" }
    println("PASS: $label")
}
fun main() {
    val plain = chunk("tEXt", "parameters\u0000sunset landscape".toByteArray())
    verify(PngMetadataReader.read(ByteArrayInputStream(png(plain)))["parameters"] == "sunset landscape", "tEXt")
    val zipped = chunk("zTXt", "prompt\u0000".toByteArray() + byteArrayOf(0) + compressed("neon city".toByteArray()))
    verify(PngMetadataReader.read(ByteArrayInputStream(png(zipped)))["prompt"] == "neon city", "zTXt")
    val international = chunk("iTXt", "prompt\u0000".toByteArray() + byteArrayOf(0,0,0,0) + "שלום עולם".toByteArray())
    verify(PngMetadataReader.read(ByteArrayInputStream(png(international)))["prompt"] == "שלום עולם", "UTF-8 iTXt")
    val izip = chunk("iTXt", "prompt\u0000".toByteArray() + byteArrayOf(1,0,0,0) + compressed("cinematic".toByteArray()))
    verify(PngMetadataReader.read(ByteArrayInputStream(png(izip)))["prompt"] == "cinematic", "compressed iTXt")
    val broken = plain.clone().also { it[it.lastIndex] = (it.last().toInt() xor 0xff).toByte() }
    verify(PngMetadataReader.read(ByteArrayInputStream(png(broken))).isEmpty(), "bad CRC rejected")
    verify(PngMetadataReader.read(ByteArrayInputStream(byteArrayOf(1,2,3))).isEmpty(), "truncated signature")
    val bomb = chunk("zTXt", "prompt\u0000".toByteArray() + byteArrayOf(0) + compressed(ByteArray(300_000) { 65 }))
    verify(PngMetadataReader.read(ByteArrayInputStream(png(bomb))).isEmpty(), "decompression bomb cap")
    val invalid = byteArrayOf(137.toByte(),80,78,71,13,10,26,10) + byteArrayOf(0x7f,0x7f,0x7f,0x7f)
    verify(PngMetadataReader.read(ByteArrayInputStream(invalid)).isEmpty(), "oversized chunk rejected")
}
