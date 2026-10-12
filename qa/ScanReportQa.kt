package com.aiorigin.detector
fun main() {
    var passed = 0
    fun test(name: String, ok: Boolean) { check(ok) { name }; println("PASS: $name"); passed++ }
    val report = ScanReport.format(ScanResult("Origin unknown", listOf("note\ninjected"), null, "blur", "Unverified", "No visual detection"))
    test("status", "Status: Origin unknown" in report)
    test("negative prompt", "Negative prompt (unverified):\nblur" in report)
    test("missing positive", "Positive prompt (unverified):\nNot found" in report)
    test("no multiline evidence", "- note injected" in report)
    test("limitations", "No visual detection" in report)
    println("$passed/5 scan-report tests passed")
}
