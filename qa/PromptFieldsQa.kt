package com.aiorigin.detector
fun main() {
    var passed = 0
    fun test(name: String, ok: Boolean) { check(ok) { name }; println("PASS: $name"); passed++ }
    val both = PromptFieldsResolver.resolve(mapOf("positive_prompt" to "a dog", "negative_prompt" to "blur"), "a dog")
    test("positive", both.positive == "a dog")
    test("negative", both.negative == "blur")
    val only = PromptFieldsResolver.resolve(mapOf("negative_prompt" to "bad"), "bad")
    test("negative-only does not become positive", only.positive == null)
    test("negative-only retained", only.negative == "bad")
    val mixed = PromptFieldsResolver.resolve(mapOf("NeGaTiVe_PrOmPt" to "grain", "POSITIVE_PROMPT" to "landscape"), "landscape")
    test("case-insensitive positive", mixed.positive == "landscape")
    test("case-insensitive negative", mixed.negative == "grain")
    val a1111 = "a bird\nNegative prompt: noisy\nSteps: 20, Seed: 3"
    val parsed = PromptFieldsResolver.resolve(mapOf("parameters" to a1111), a1111)
    test("A1111 positive", parsed.positive == "a bird")
    test("A1111 negative", parsed.negative == "noisy")
    test("blank positive ignored", PromptFieldsResolver.resolve(mapOf("positive_prompt" to "  ", "negative_prompt" to "x"), "x").positive == null)
    test("no data", PromptFieldsResolver.resolve(emptyMap(), null).positive == null)
    test("description not a prompt", PromptFieldsResolver.resolve(mapOf("description" to "a nice picture"), "a nice picture").positive == null)
    test("comment not a prompt", PromptFieldsResolver.resolve(mapOf("comment" to "camera notes"), "camera notes").positive == null)
    test("JSON graph not a prompt", PromptFieldsResolver.resolve(mapOf("prompt" to "{\"1\":{}}"), "{\"1\":{}}").positive == null)
    test("negative plus JSON not positive", PromptFieldsResolver.resolve(mapOf("negative_prompt" to "bad", "prompt" to "{\"1\":{}}"), "{\"1\":{}}").positive == null)
    test("generic prompt retained", PromptFieldsResolver.resolve(mapOf("prompt" to "a cat"), "a cat").positive == "a cat")
    val exif = PromptFieldsResolver.resolve(mapOf("UserComment" to "neon street\nNegative prompt: lowres\nSteps: 25, Seed: 7, Model: x"), null)
    test("A1111 in UserComment positive", exif.positive == "neon street")
    test("A1111 in UserComment negative", exif.negative == "lowres")
    test("A1111 settings exposed", exif.settings["Model"] == "x" && exif.originKind == PromptOrigin.SETTINGS)
    println("$passed/18 prompt-field tests passed")
}
