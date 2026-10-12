package com.aiorigin.detector

fun main() {
    var passed = 0
    fun checkCase(name: String, test: () -> Boolean) {
        check(test()) { "FAIL: $name" }
        passed++
        println("PASS: $name")
    }
    val example = """cinematic mountain, golden sunrise
Negative prompt: blurry, low quality
Steps: 30, Sampler: DPM++ 2M Karras, CFG scale: 7, Seed: 123, Size: 768x512, Model: example-model"""
    checkCase("positive text") { GenerationParametersParser.parse(example)?.positive == "cinematic mountain, golden sunrise" }
    checkCase("negative text") { GenerationParametersParser.parse(example)?.negative == "blurry, low quality" }
    checkCase("model field") { GenerationParametersParser.parse(example)?.settings?.get("Model") == "example-model" }
    checkCase("sampler with plus signs") { GenerationParametersParser.parse(example)?.settings?.get("Sampler") == "DPM++ 2M Karras" }
    checkCase("without negative") { GenerationParametersParser.parse("a cat\nSteps: 20, Seed: 2")?.positive == "a cat" }
    checkCase("CRLF input") { GenerationParametersParser.parse("a cat\r\nSteps: 20, Seed: 2")?.settings?.get("Seed") == "2" }
    checkCase("ordinary description is not A1111") { GenerationParametersParser.parse("a cat by a window") == null }
    checkCase("oversized input rejected") { GenerationParametersParser.parse("x".repeat(200_001)) == null }
    checkCase("missing positive and negative") { GenerationParametersParser.parse("Steps: 20, Seed: 2") == null }
    checkCase("case-insensitive steps") { GenerationParametersParser.parse("portrait\nsteps: 30, Seed: 9")?.positive == "portrait" }
    checkCase("multiline negative prompt") {
        GenerationParametersParser.parse("scene\nNegative prompt: blur,\nwatermark, artifacts\nSteps: 22, Seed: 3")?.negative == "blur,\nwatermark, artifacts"
    }
    checkCase("negative-only generation") {
        val r = GenerationParametersParser.parse("Negative prompt: noisy\nSteps: 12, Seed: 5")
        r?.positive == "" && r.negative == "noisy"
    }
    checkCase("no false negative field in positive text") {
        val r = GenerationParametersParser.parse("a sign saying negative prompt: test\nSteps: 12, Seed: 5")
        r?.negative == null && r?.positive == "a sign saying negative prompt: test"
    }
    checkCase("negative prompt line case insensitive") {
        GenerationParametersParser.parse("scene\nNEGATIVE PROMPT: bad\nSteps: 12, Seed: 5")?.negative == "bad"
    }
    println("$passed/14 generation-parameter tests passed")
}
