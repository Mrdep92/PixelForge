package com.pixelforge.engine

import org.junit.Assert.*
import org.junit.Test

class SandboxHarnessTest {
    private val spec = AppSpec(
        "Sholat", "pwa", "id",
        listOf(Feature("F1", "Waktu sholat", listOf("jam tampil"))),
        listOf(FileSpec("js/app.js", listOf("F1"), listOf("prayerTimes(lat, lng)", "Qibla.angle(lat, lng)"), emptyList())),
        emptyList(), emptyList()
    )

    private val planJson = """
        Berikut rencananya:
        {"checks":[
          {"kind":"exists","label":"judul","selector":"h1"},
          {"kind":"text","label":"teks judul","selector":"h1","text":"Sholat"},
          {"kind":"call","label":"subuh jakarta","fn":"prayerTimes","args":[-6.2,106.8],"expect":{"fajr":"04:35"},"verified":true},
          {"kind":"call","label":"kiblat","fn":"Qibla.angle","args":[-6.2,106.8],"expect":295.0,"tolerance":0.5}
        ]}
    """.trimIndent()

    private fun plan(): TestPlan = (PlanParser.parse(planJson) as PlanResult.Ok).plan

    @Test fun parsesPlanAndKinds() {
        val p = plan()
        assertEquals(4, p.checks.size)
        assertEquals(Check.EXISTS, p.checks[0].kind)
        assertTrue(p.checks[2].verified)
        assertEquals(0.5, p.checks[3].tolerance, 0.0)
        assertEquals("""{"fajr":"04:35"}""", p.checks[2].expect)
    }

    @Test fun parseErrors() {
        assertTrue(PlanParser.parse("tidak ada json") is PlanResult.Err)
        assertTrue(PlanParser.parse("""{"x":1}""") is PlanResult.Err)
    }

    @Test fun roundTrip() {
        val again = (PlanParser.parse(PlanParser.write(plan())) as PlanResult.Ok).plan
        assertEquals(plan(), again)
    }

    @Test fun validPlanHasNoProblems() {
        assertTrue(PlanValidator.validate(plan(), spec).isEmpty())
    }

    @Test fun validatorCatchesProblems() {
        val bad = TestPlan(
            listOf(
                Check(Check.CALL, "a", fn = "alert(1)"),
                Check(Check.CALL, "b", fn = "tidakDijanjikan"),
                Check(Check.CALL, "c", fn = "prayerTimes", args = "bukan json"),
                Check(Check.EXISTS, "d"),
                Check("LAIN", "e")
            )
        )
        val p = PlanValidator.validate(bad, spec)
        assertTrue(p.any { it.contains("tidak aman") })
        assertTrue(p.any { it.contains("tidak dijanjikan") })
        assertTrue(p.any { it.contains("args bukan array") })
        assertTrue(p.any { it.contains("selector kosong") })
        assertTrue(p.any { it.contains("tidak dikenal") })
        assertEquals(listOf("Rencana tes kosong."), PlanValidator.validate(TestPlan(emptyList()), spec))
    }

    @Test fun scriptNeverContainsUnsafeFunctionNames() {
        val evil = TestPlan(listOf(Check(Check.CALL, "x", fn = "alert(1);//", args = "[]", expect = "1")))
        val js = SandboxScript.build(evil)
        assertFalse(js.contains("alert(1)"))
    }

    @Test fun scriptEmbedsChecksAndNoPlaceholder() {
        val js = SandboxScript.build(plan())
        assertFalse(js.contains("__CHECKS__"))
        assertTrue(js.contains("prayerTimes") && js.contains("Qibla.angle") && js.contains("EXISTS"))
    }

    @Test fun parsesResultsJson() {
        val r = SandboxReports.parseResults("""[{"label":"a","ok":true,"detail":""},{"label":"b","ok":false,"detail":"x"}]""")!!
        assertEquals(2, r.size)
        assertFalse(r[1].ok)
        assertNull(SandboxReports.parseResults("bukan json"))
    }

    @Test fun triageSeparatesKinds() {
        val p = plan()
        val report = SandboxReport(
            true, listOf("ReferenceError: x is not defined (app.js:3)"),
            listOf(
                CheckResult("judul", false, "tidak ada elemen h1"),
                CheckResult("subuh jakarta", false, """hasil {"fajr":"05:00"}, harapan {"fajr":"04:35"}"""),
                CheckResult("kiblat", false, "hasil 290, harapan 295.0")
            )
        )
        val f = Triage.classify(report, p)
        assertEquals(FailureKind.CODE_BUG, f[0].kind)                       // galat konsol
        assertEquals(FailureKind.CODE_BUG, f[1].kind)                       // elemen hilang
        assertEquals(FailureKind.CODE_BUG, f[2].kind)                       // acuan terverifikasi → bug kode
        assertEquals(FailureKind.TEST_BUG, f[3].kind)                       // acuan belum terverifikasi → tanya manusia
        assertEquals(3, Triage.forDebugger(f).size)
        assertTrue(Triage.render(f).contains("[TEST_BUG]"))
    }

    @Test fun harnessErrorIsNotACodeBug() {
        val f = Triage.classify(SandboxReport(false, emptyList(), emptyList(), "waktu uji habis"), plan())
        assertEquals(1, f.size)
        assertEquals(FailureKind.HARNESS, f[0].kind)
        assertTrue(Triage.forDebugger(f).isEmpty())
    }

    @Test fun passedOnlyWhenEverythingIsClean() {
        assertTrue(SandboxReport(true, emptyList(), listOf(CheckResult("a", true, ""))).passed)
        assertFalse(SandboxReport(true, listOf("err"), emptyList()).passed)
        assertFalse(SandboxReport(true, emptyList(), listOf(CheckResult("a", false, "x"))).passed)
        assertFalse(SandboxReport(false, emptyList(), emptyList()).passed)
    }
}
