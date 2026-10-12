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
}
