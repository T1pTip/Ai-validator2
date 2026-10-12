package com.aiorigin.detector

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

fun main() {
    var passed = 0
    fun test(name: String, predicate: () -> Boolean) { check(predicate()) { name }; passed++; println("PASS: $name") }
    fun jpeg(vararg segments: Pair<Int, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        for ((marker, data) in segments) {
            val size = data.size + 2
            output.write(byteArrayOf(0xff.toByte(), marker.toByte(), (size shr 8).toByte(), size.toByte()))
            output.write(data)
        }
        output.write(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
        return output.toByteArray()
    }
    fun parse(bytes: ByteArray) = JpegMetadataReader.read(ByteArrayInputStream(bytes))
    val comment = "a forest\nNegative prompt: blurry\nSteps: 20, Seed: 1"
    test("COM prompt") { parse(jpeg(0xfe to comment.toByteArray()))["comment"] == comment }
    test("negative prompt remains in COM") { parse(jpeg(0xfe to comment.toByteArray()))["comment"]!!.contains("Negative prompt:") }
    test("empty input") { parse(byteArrayOf()).isEmpty() }
    test("bad signature") { parse(byteArrayOf(1, 2, 3)).isEmpty() }
    test("truncated segment") { parse(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xfe.toByte(),0,9,1)).isEmpty() }
    test("SOS stops scanning") { parse(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xda.toByte())).isEmpty() }
    val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><rdf:Description ai:prompt=\"a cat\" ai:negative_prompt=\"blurry\" /></x:xmpmeta>"
    test("XMP attributes") { parse(jpeg(0xe1 to xmp.toByteArray()))["prompt"] == "a cat" }
    test("XMP negative prompt") { parse(jpeg(0xe1 to xmp.toByteArray()))["negative_prompt"] == "blurry" }
    test("unrelated APP1 ignored") { parse(jpeg(0xe1 to "hello".toByteArray())).isEmpty() }
    test("multiple comments") { parse(jpeg(0xfe to "first".toByteArray(), 0xfe to "second".toByteArray())).size == 2 }
    test("zero-length comment") { parse(jpeg(0xfe to byteArrayOf())).isEmpty() }
    println("$passed/11 JPEG tests passed")
}
