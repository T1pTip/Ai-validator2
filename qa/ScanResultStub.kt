package com.aiorigin.detector
// Only used in standalone JVM QA; Android build uses ScanResult from Scanner.kt.
data class ScanResult(val status: String, val evidence: List<String>, val prompt: String?, val negativePrompt: String?, val source: String, val caveat: String)
