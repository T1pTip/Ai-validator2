package com.aiorigin.detector

fun main() {
    var passed = 0
    fun test(name: String, ok: Boolean) { check(ok) { "FAIL: $name" }; println("PASS: $name"); passed++ }
    test("plain https", UrlFetcher.normalize("https://example.com/a.jpg") == "https://example.com/a.jpg")
    test("http upgraded", UrlFetcher.normalize("http://example.com/a.png") == "https://example.com/a.png")
    test("link inside shared text", UrlFetcher.normalize("look at this https://cdn.site.com/x/y.webp?w=1 wow") == "https://cdn.site.com/x/y.webp?w=1")
    test("trailing punctuation", UrlFetcher.normalize("(https://a.com/b.jpg).") == "https://a.com/b.jpg")
    test("bare domain", UrlFetcher.normalize("example.com/pic.jpg") == "https://example.com/pic.jpg")
    test("not a link", UrlFetcher.normalize("hello world") == null)
    test("empty", UrlFetcher.normalize("   ") == null)
    val page = """<html><head><meta property="og:title" content="t"><meta property='og:image' content='/img/main.jpg?a=1&amp;b=2'></head></html>"""
    test("og:image relative + entity", UrlFetcher.mediaUrlFromHtml(page, "https://site.com/post/1") == "https://site.com/img/main.jpg?a=1&b=2")
    val video = """<meta content="https://v.site.com/clip.mp4" property="og:video"><meta property="og:image" content="https://i.site.com/p.jpg">"""
    test("video preferred over image", UrlFetcher.mediaUrlFromHtml(video, "https://site.com") == "https://v.site.com/clip.mp4")
    test("twitter:image fallback", UrlFetcher.mediaUrlFromHtml("""<meta name="twitter:image" content="http://x.com/i.png">""", "https://x.com") == "https://x.com/i.png")
    test("no media", UrlFetcher.mediaUrlFromHtml("<html><body>hi</body></html>", "https://a.com") == null)
    println("$passed/11 URL tests passed")
}
