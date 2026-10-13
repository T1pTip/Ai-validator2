package com.aiorigin.detector

/** Labels for the plain-text report, so the app can share it in Hebrew. */
data class ReportLabels(
    val title: String = "AI Origin Detector — local scan report",
    val status: String = "Status",
    val source: String = "Source",
    val evidence: String = "Evidence",
    val positive: String = "Positive prompt (unverified)",
    val negative: String = "Negative prompt (unverified)",
    val settings: String = "Generation settings (unverified)",
    val notFound: String = "Not found",
    val limitations: String = "Limitations"
)

/** Plain-text report for user-initiated sharing; never silently uploads media. */
object ScanReport {
    private val newlines = Regex("[\\r\\n]+")

    fun format(result: ScanResult, labels: ReportLabels = ReportLabels()): String = buildString {
        appendLine(labels.title)
        appendLine("${labels.status}: ${result.status}")
        appendLine("${labels.source}: ${result.source}")
        appendLine()
        appendLine("${labels.evidence}:")
        result.evidence.forEach { appendLine("- ${it.replace(newlines, " ")}") }
        appendLine()
        appendLine("${labels.positive}:")
        appendLine(result.prompt ?: labels.notFound)
        appendLine()
        appendLine("${labels.negative}:")
        appendLine(result.negativePrompt ?: labels.notFound)
        if (result.settings.isNotEmpty()) {
            appendLine()
            appendLine("${labels.settings}:")
            result.settings.forEach { (k, v) -> appendLine("- $k: ${v.replace(newlines, " ")}") }
        }
        appendLine()
        appendLine("${labels.limitations}: ${result.caveat}")
    }
}
