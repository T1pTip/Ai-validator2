package com.aiorigin.detector

/** Plain-text report for user-initiated sharing; never silently uploads media. */
object ScanReport {
    fun format(result: ScanResult): String = buildString {
        appendLine("AI Origin Detector — local scan report")
        appendLine("Status: ${result.status}")
        appendLine("Source: ${result.source}")
        appendLine()
        appendLine("Evidence:")
        result.evidence.forEach { appendLine("- ${it.replace(Regex("[\\r\\n]+"), " ")}") }
        appendLine()
        appendLine("Positive prompt (unverified):")
        appendLine(result.prompt ?: "Not found")
        appendLine()
        appendLine("Negative prompt (unverified):")
        appendLine(result.negativePrompt ?: "Not found")
        appendLine()
        appendLine("Limitations: ${result.caveat}")
    }
}
