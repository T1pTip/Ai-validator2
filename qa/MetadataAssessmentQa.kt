import com.aiorigin.detector.MetadataAssessor

fun checkCase(name: String, metadata: Map<String,String>, expected: String) {
    val result = MetadataAssessor.assess(metadata)
    check(result.status == expected) { "$name: ${result.status}" }
    println("PASS: $name")
}
fun main() {
    checkCase("empty", emptyMap(), "Origin unknown")
    checkCase("camera software", mapOf("Software" to "Samsung Camera"), "Origin unknown")
    checkCase("ordinary description", mapOf("ImageDescription" to "A cat on a sofa"), "Description found; origin unknown")
    checkCase("prompt field", mapOf("prompt" to "A fantasy castle"), "Prompt-like metadata found (unverified)")
    checkCase("generation tool", mapOf("Software" to "ComfyUI"), "AI creation metadata found (unverified)")
    checkCase("case insensitive", mapOf("Comment" to "Rendered with STABLE DIFFUSION"), "AI creation metadata found (unverified)")
    checkCase("prefer explicit", mapOf("description" to "generic", "parameters" to "steps: 20"), "Prompt-like metadata found (unverified)")
    check(MetadataAssessor.assess(mapOf("description" to "generic", "prompt" to "specific")).rawPrompt == "specific")
    println("PASS: explicit priority")
    val reordered = MetadataAssessor.assess(linkedMapOf("negative_prompt" to "blur", "positive_prompt" to "a tree"))
    check(reordered.rawPrompt == "a tree")
    println("PASS: positive before negative regardless of map order")
    val uppercase = MetadataAssessor.assess(mapOf(" POSITIVE_PROMPT " to "a forest", "negative_prompt" to "noise"))
    check(uppercase.rawPrompt == "a forest")
    println("PASS: whitespace and case normalized")
    val negativeOnly = MetadataAssessor.assess(mapOf("negative_prompt" to "low quality"))
    check(negativeOnly.rawPrompt == "low quality" && negativeOnly.status.contains("unverified"))
    println("PASS: negative-only metadata still assessed without promotion")
    checkCase("C2PA declared AI", mapOf("c2pa.present" to "true", "c2pa.digital_source_type" to "trainedAlgorithmicMedia"),
        "AI generation declared in metadata (unverified)")
    checkCase("IPTC URL in raw XMP", mapOf("xmp" to "<x>http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia</x>"),
        "AI generation declared in metadata (unverified)")
    checkCase("composite is edit, not generation", mapOf("digital_source_type" to "compositeWithTrainedAlgorithmicMedia"),
        "AI editing declared in metadata (unverified)")
    checkCase("A1111 infotext in EXIF UserComment", mapOf("UserComment" to "a castle\nSteps: 30, Sampler: Euler a, Seed: 1"),
        "AI creation metadata found (unverified)")
    check(MetadataAssessor.assess(mapOf("UserComment" to "a castle\nSteps: 30, Seed: 1")).rawPrompt!!.startsWith("a castle"))
    println("PASS: A1111 UserComment becomes the prompt source")
    checkCase("ambiguous name in description is not a tool", mapOf("ImageDescription" to "Gemini constellation at night"),
        "Description found; origin unknown")
    checkCase("ambiguous name in software field is a tool", mapOf("Software" to "Google Gemini"),
        "AI creation metadata found (unverified)")
    checkCase("C2PA generator ChatGPT", mapOf("c2pa.present" to "true", "c2pa.generator" to "ChatGPT"),
        "AI creation metadata found (unverified)")
    checkCase("C2PA from a camera only", mapOf("c2pa.present" to "true", "c2pa.generator" to "Samsung Camera"), "Origin unknown")
    val comfy = """{"3":{"class_type":"KSampler","inputs":{"positive":["6",0]}},"6":{"class_type":"CLIPTextEncode","inputs":{"text":"a cat"}}}"""
    checkCase("ComfyUI graph without the word ComfyUI", mapOf("prompt" to comfy), "AI creation metadata found (unverified)")
    check(MetadataAssessor.assess(mapOf("prompt" to comfy)).tool == "ComfyUI"); println("PASS: ComfyUI tool name")
    checkCase("ordinary JSON is not ComfyUI", mapOf("comment" to """{"class_type":"photo"}"""), "Description found; origin unknown")
    check(MetadataAssessor.description(mapOf("ImageDescription" to " a red fox ")) == "a red fox"); println("PASS: description shown")
    check(MetadataAssessor.description(mapOf("UserComment" to "x\nSteps: 20, Seed: 1")) == null); println("PASS: A1111 text is not a description")
    // Adversarial review regressions: substring and base64 false positives.
    checkCase("Imagenomic is not Imagen", mapOf("Software" to "Imagenomic Portraiture 3"), "Origin unknown")
    checkCase("LumaFusion is not Luma", mapOf("Software" to "LumaFusion 4.1"), "Origin unknown")
    checkCase("SDXL with version still matches", mapOf("Software" to "SDXL1.0 base"), "AI creation metadata found (unverified)")
    val rnd = java.util.Random(7)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    var falseHits = 0
    repeat(200) {
        val b64 = buildString { repeat(45_000) { append(alphabet[rnd.nextInt(64)]) } } + "sdxl" // worst case: present in raw XMP
        val xmp = """<x:xmpmeta><rdf:RDF><rdf:Description xmp:CreatorTool="Adobe Photoshop 25.0"><xmpGImg:image>$b64</xmpGImg:image></rdf:Description></rdf:RDF></x:xmpmeta>"""
        if (MetadataAssessor.assess(mapOf("xmp" to xmp, "creatortool" to "Adobe Photoshop 25.0")).tool != null) falseHits++
    }
    check(falseHits == 0) { "base64 thumbnail produced $falseHits tool matches" }
    println("PASS: base64 thumbnails in raw XMP never produce a tool match")
}
