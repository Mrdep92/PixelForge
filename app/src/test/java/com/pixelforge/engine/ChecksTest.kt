package com.pixelforge.engine

import org.junit.Assert.*
import org.junit.Test

class ChecksTest {
    private fun ticket(file: String, exports: List<String> = emptyList()) =
        Ticket("T1", file, emptyList(), "g", emptyList(), emptyList(), emptyList(), exports)

    @Test fun balancedJsPasses() {
        val js = "function f(a) { const s = '}'; // )\n return [a, `x\${a}`]; }\nconst r = /[)(]/.test('x');"
        assertTrue(Checks.bracketErrors(js, true).isEmpty())
    }

    @Test fun truncatedJsFails() {
        assertFalse(Checks.bracketErrors("function f() { if (x) {", true).isEmpty())
        assertFalse(Checks.bracketErrors("f(1))", true).isEmpty())
        assertFalse(Checks.bracketErrors("var s = 'abc", true).isEmpty())
    }

    @Test fun jsonAndCss() {
        assertTrue(Checks.syntax("m.json", "{\"a\": 1}").isEmpty())
        assertFalse(Checks.syntax("m.json", "{\"a\": ").isEmpty())
        assertFalse(Checks.syntax("s.css", "body { color: red;").isEmpty())
    }

    @Test fun htmlTags() {
        assertTrue(Checks.htmlErrors("<!doctype html><html><body><p>a<p>b<br><ul><li>x<li>y</ul></body></html>").isEmpty())
        assertFalse(Checks.htmlErrors("<html><body><div><span></div></body></html>").isEmpty())
        assertFalse(Checks.htmlErrors("<!doctype html><html><body><div>").isEmpty())
        assertTrue(Checks.htmlErrors("<html><body><script>if (a < b) { x('</div>') }</script></body></html>").isEmpty())
    }

    @Test fun leftovers() {
        assertFalse(Checks.leftovers("```js\nx\n```").isEmpty())
        assertFalse(Checks.leftovers("a\n=== END FILE ===").isEmpty())
        assertTrue(Checks.leftovers("ok").isEmpty())
    }

    @Test fun fence() {
        assertEquals(1, Checks.fence(ticket("a.js"), listOf("a.js", "b.js")).size)
    }

    @Test fun promisedExportMustExist() {
        val t = ticket("js/p.js", listOf("computeTimes(lat, lon)"))
        assertTrue(Checks.references(t, "function computeTimes(a, b) {}", emptyMap(), emptyList()).isEmpty())
        assertTrue(Checks.references(t, "const computeTimes = (a, b) => 1", emptyMap(), emptyList()).isEmpty())
        assertFalse(Checks.references(t, "function other() {}", emptyMap(), emptyList()).isEmpty())
    }

    @Test fun htmlReferencesAndCdn() {
        val t = ticket("index.html")
        val html = "<script src=\"js/a.js\"></script><script src=\"https://evil.example/x.js\"></script><link href=\"missing.css\" rel=stylesheet>"
        val e = Checks.references(t, html, mapOf("index.html" to html), listOf("index.html", "js/a.js"))
        assertTrue(e.any { "evil.example" in it })
        assertTrue(e.any { "missing.css" in it })
        assertTrue(e.none { "js/a.js" in it })
        val ok = "<script src=\"https://cdn.jsdelivr.net/npm/x@1/x.js\"></script>"
        assertTrue(Checks.references(t, ok, mapOf("index.html" to ok), listOf("index.html")).isEmpty())
    }

    @Test fun domIdsMustExistInHtml() {
        val t = ticket("js/a.js")
        val files = mapOf("index.html" to "<div id=\"out\"></div>", "js/a.js" to "document.getElementById('out'); document.getElementById('nope')")
        val e = Checks.references(t, files.getValue("js/a.js"), files, emptyList())
        assertEquals(1, e.size)
        assertTrue("nope" in e[0])
    }
}
