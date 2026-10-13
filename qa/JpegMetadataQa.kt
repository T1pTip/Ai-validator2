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
    val apostrophe = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><rdf:Description ai:prompt=\"an artist's studio\"/></x:xmpmeta>"
    test("apostrophe inside double-quoted XMP value") { parse(jpeg(0xe1 to apostrophe.toByteArray()))["prompt"] == "an artist's studio" }
    val alt = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><rdf:RDF><rdf:Description rdf:about=\"\"><dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">a red fox</rdf:li></rdf:Alt></dc:description></rdf:Description></rdf:RDF></x:xmpmeta>"
    test("rdf:Alt description, not the rdf:Description container") { parse(jpeg(0xe1 to alt.toByteArray()))["description"] == "a red fox" }
    val iptc = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><rdf:Description Iptc4xmpExt:DigitalSourceType=\"http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia\"/></x:xmpmeta>"
    test("IPTC digital source type") { parse(jpeg(0xe1 to iptc.toByteArray()))["digital_source_type"]!!.endsWith("trainedAlgorithmicMedia") }
    val jumbf = byteArrayOf(0x4a, 0x50, 0, 1, 0, 0, 0, 1) + "jumb\u0000c2pa\u0000".toByteArray() +
        byteArrayOf(0x6f) + "claim_generator".toByteArray() + byteArrayOf(0x67) + "ChatGPT".toByteArray() +
        byteArrayOf(0x71) + "digitalSourceType".toByteArray() + byteArrayOf(0x78, 0x40) +
        "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia".toByteArray()
    val c2pa = parse(jpeg(0xeb to jumbf))
    test("APP11 C2PA manifest detected") { c2pa["c2pa.present"] == "true" }
    test("C2PA AI source type") { c2pa["c2pa.digital_source_type"] == "trainedAlgorithmicMedia" }
    test("C2PA claim generator") { c2pa["c2pa.generator"] == "ChatGPT" }
    test("APP11 without c2pa ignored") { parse(jpeg(0xeb to "JPxxxx".toByteArray())).isEmpty() }
    println("$passed/18 JPEG tests passed")
}
