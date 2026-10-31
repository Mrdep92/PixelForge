package com.pixelforge.engine

import org.junit.Assert.*
import org.junit.Test

class ContractsTest {
    private fun spec(cards: List<String>, dom: List<DomItem> = emptyList(), assets: List<String> = emptyList()) = AppSpec(
        "Islam", "pwa", "id",
        listOf(Feature("F1", "Waktu", listOf("tampil"))),
        listOf(
            FileSpec("index.html", listOf("F1"), emptyList(), listOf("js/p.js")),
            FileSpec("js/p.js", listOf("F1"), listOf("computeTimes(lat, lon)", "formatHHMM(x)"), emptyList(), cards)
        ),
        emptyList(), emptyList(), dom, assets
    )

    @Test fun cardExportsAreInjectedAndReplaceSameNameSignatures() {
        val s = Contracts.withCardExports(spec(listOf("prayer-times", "qibla")))
        val ex = s.files.first { it.path == "js/p.js" }.exports
        assertTrue(ex.contains("computeTimes(lat, lon)"))
        assertTrue(ex.contains("prayerTimes(lat, lon, year, month, day, tz, fajrAngle, ishaAngle)"))
        assertTrue(ex.contains("qiblaBearing(lat, lon)"))
        assertEquals(1, ex.count { it.startsWith("formatHHMM") })
        assertTrue(ex.contains("formatHHMM(hours)"))                     // tanda tangan kartu menggantikan buatan model
    }

    @Test fun finalPlanAddsVectorsAndNonEmptyChecks() {
        val s = Contracts.withCardExports(spec(listOf("prayer-times"), listOf(DomItem("times", "js/p.js"), DomItem("msg", "js/p.js", lazy = true))))
        val plan = Contracts.finalPlan(s, TestPlan(listOf(Check(Check.EXISTS, "judul", selector = "h1"))))
        assertTrue(plan.checks.any { it.label.startsWith("kartu: waktu sholat Jakarta 20 Mar") && it.verified })
        assertTrue(plan.checks.any { it.kind == Check.NONEMPTY && it.selector == "#times" })
        assertTrue(plan.checks.none { it.selector == "#msg" })              // lazy tidak dicek
        assertTrue(PlanValidator.validate(plan, s).isEmpty())               // semua fungsi uji ada di exports
    }

    @Test fun finalPlanWithoutModelTestsStillHasChecks() {
        val s = Contracts.withCardExports(spec(listOf("qibla")))
        val plan = Contracts.finalPlan(s, null)
        assertEquals(3, plan.checks.size)
        assertTrue(plan.checks.all { it.kind == Check.CALL && it.fn == "qiblaBearing" })
    }

    @Test fun vectorsAreMonotonicAndFormatted() {
        val v = Cards.get("prayer-times")!!.vectors.filter { it.fn == "prayerTimes" }
        assertEquals(2, v.size)
        v.forEach { assertTrue(com.google.gson.JsonParser.parseString(it.expect).asJsonObject.size() == 6) }
    }

    @Test fun arabicTextWrittenByModelIsRejectedButAssetsAreNot() {
        val s = spec(emptyList(), assets = listOf("quran-juz30"))
        val files = mapOf("index.html" to "<p>بسم الله</p>", "js/p.js" to "var x = 1;", "data/quran-juz30.json" to "{\"ar\":\"وَٱلۡعَصۡرِ\"}")
        val issues = AppChecks.verify(s, files)
        assertTrue(issues.any { it.file == "index.html" && it.message.contains("teks Arab") })
        assertTrue(issues.none { it.file == "data/quran-juz30.json" })
        assertTrue(Checks.arabic("hello").isEmpty())
    }

    @Test fun assetAndBaseCssPathsAreKnownReferences() {
        val s = spec(emptyList(), assets = listOf("quran-juz30"))
        val js = "fetch('data/quran-juz30.json'); var l='base.css';"
        assertTrue(AppChecks.verify(s, mapOf("js/p.js" to js)).none { it.message.contains("Acuan file") })
    }

    @Test fun baseCssIsInjectedFirstAndNotDuplicated() {
        val s = spec(emptyList())
        val html = "<html><head><link rel=\"stylesheet\" href=\"style.css\"></head><body></body></html>"
        val out = Assemble.injectAssets(html, s.copy(files = s.files + FileSpec("style.css", listOf("F1"), emptyList(), emptyList())))
        assertTrue(out.indexOf("base.css") in 0 until out.indexOf("style.css"))
        assertEquals(out, Assemble.injectAssets(out, s.copy(files = s.files + FileSpec("style.css", listOf("F1"), emptyList(), emptyList()))))
    }

    @Test fun assetsRoundTripAndValidate() {
        val s = spec(emptyList(), assets = listOf("quran-juz30"))
        val back = (SpecParser.parse(SpecJson.write(s)) as SpecResult.Ok).spec
        assertEquals(listOf("quran-juz30"), back.assets)
        assertTrue(SpecValidator.validate(s.copy(assets = listOf("tidak-ada"))).any { it.contains("Asset tidak dikenal") })
        val t = TicketPlanner.plan(s).first { it.file == "js/p.js" }
        assertTrue(t.facts.any { it.contains("data/quran-juz30.json") && it.contains("jangan ditulis ulang") })
    }

    @Test fun nonEmptyFailureIsCodeBugAndImplicatesFillerFile() {
        val s = spec(emptyList(), listOf(DomItem("times", "js/p.js")))
        val plan = Contracts.finalPlan(s, null)
        val report = SandboxReport(true, emptyList(), listOf(CheckResult("terisi: #times", false, "elemen #times kosong setelah halaman dimuat")))
        val f = Triage.classify(report, plan)
        assertEquals(FailureKind.CODE_BUG, f[0].kind)
        assertEquals(listOf("js/p.js"), Implicated.files(f, plan, s))
    }

    @Test fun baseCssAssetFileExistsInResourcesFormat() {
        assertTrue(BaseCss.TEXT.contains(".arabic") && BaseCss.TEXT.contains("section"))
    }
}
