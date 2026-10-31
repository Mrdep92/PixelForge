package com.pixelforge.engine

import org.junit.Assert.*
import org.junit.Test

class AppChecksTest {
    private val spec = AppSpec(
        "Islam", "pwa", "id",
        listOf(Feature("F1", "Waktu", listOf("tampil"))),
        listOf(
            FileSpec("index.html", listOf("F1"), emptyList(), listOf("js/prayer.js", "js/app.js")),
            FileSpec("js/prayer.js", listOf("F1"), listOf("getPrayerTimes(lat, lon)"), emptyList()),
            FileSpec("js/app.js", listOf("F1"), listOf("initApp()"), listOf("js/prayer.js")),
            FileSpec("style.css", listOf("F1"), emptyList(), emptyList())
        ),
        emptyList(), emptyList(),
        listOf(DomItem("times", "js/app.js"), DomItem("title", "js/app.js"))
    )

    @Test fun injectsMissingScriptsInDependencyOrderBeforeFirstScript() {
        val html = "<html><head></head><body><div id=\"times\"></div><script src=\"js/app.js\"></script></body></html>"
        val out = Assemble.injectAssets(html, spec)
        assertTrue(out.indexOf("js/prayer.js") in 0 until out.indexOf("js/app.js"))
        assertTrue(out.contains("<link rel=\"stylesheet\" href=\"style.css\">"))
        assertEquals(out, Assemble.injectAssets(out, spec))            // idempoten
    }

    @Test fun flagsInlineScriptAndMissingDomIds() {
        val html = "<html><body><h1 id=\"title\"></h1><script>initApp();</script></body></html>"
        val i = AppChecks.verify(spec, mapOf("index.html" to html))
        assertTrue(i.any { it.file == "index.html" && it.message.contains("inline") })
        assertTrue(i.any { it.message.contains("id=\"times\"") })
    }

    @Test fun flagsJsThatBuildsThePageAndUsesUnknownIds() {
        val html = "<html><body><h1 id=\"title\"></h1><div id=\"times\"></div></body></html>"
        val js = "function initApp(){ var d=document.createElement('div'); document.body.appendChild(d); document.getElementById('nope'); }"
        val i = AppChecks.verify(spec, mapOf("index.html" to html, "js/app.js" to js))
        assertTrue(i.any { it.file == "js/app.js" && it.message.contains("document.body") })
        assertTrue(i.any { it.file == "js/app.js" && it.message.contains("nope") })
    }

    @Test fun flagsReferencesToFilesNotInSpec() {
        val js = "navigator.serviceWorker.register('/sw.js'); fetch('./data.json');"
        val i = AppChecks.verify(spec, mapOf("js/app.js" to js))
        assertTrue(i.any { it.file == "js/app.js" && it.message.contains("/sw.js") && it.message.contains("./data.json") })
    }

    @Test fun cleanAppHasNoIssues() {
        val html = "<html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body><h1 id=\"title\"></h1><div id=\"times\"></div>" +
            "<script src=\"js/prayer.js\"></script><script src=\"js/app.js\"></script></body></html>"
        val js = "function initApp(){ document.getElementById('times').textContent='x'; document.getElementById('title').textContent='y'; } initApp();"
        assertTrue(AppChecks.verify(spec, mapOf("index.html" to html, "js/app.js" to js, "js/prayer.js" to "function getPrayerTimes(a,b){}")).isEmpty())
    }

    @Test fun domContractRoundTripsAndIsValidated() {
        val back = (SpecParser.parse(SpecJson.write(spec)) as SpecResult.Ok).spec
        assertEquals(spec.dom, back.dom)
        val bad = spec.copy(dom = listOf(DomItem("times", "js/nope.js"), DomItem("times", "js/app.js"), DomItem("1x", "")))
        val p = SpecValidator.validate(bad)
        assertTrue(p.any { it.contains("ganda") } && p.any { it.contains("tidak ada/bukan .js") } && p.any { it.contains("tidak valid") })
    }

    @Test fun ticketsCarryTheDomContract() {
        val t = TicketPlanner.plan(spec)
        assertEquals(listOf("times", "title"), t.first { it.file == "index.html" }.domOwn)
        assertEquals(listOf("times", "title"), t.first { it.file == "js/app.js" }.domFill)
        assertTrue(Packet.user(t.first { it.file == "js/app.js" }, "id", null, emptyMap(), null).contains("ALREADY IN index.html"))
        assertTrue(Packet.user(t.first { it.file == "index.html" }, "id", null, emptyMap(), null).contains("MUST CONTAIN"))
    }

    @Test fun ticketLevelJsMayOnlyUseContractIds() {
        val t = TicketPlanner.plan(spec).first { it.file == "js/app.js" }
        val paths = spec.files.map { it.path }
        val bad = Checks.references(t, "function initApp(){ document.getElementById('doa-container'); }", emptyMap(), paths)
        assertTrue(bad.any { it.contains("doa-container") && it.contains("times") })
        val ok = Checks.references(t, "function initApp(){ document.getElementById('times'); document.getElementById('title'); }", emptyMap(), paths)
        assertTrue(ok.none { it.contains("kontrak DOM") })
    }
}
