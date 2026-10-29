package com.pixelforge.engine

import org.junit.Assert.*
import org.junit.Test

class SpecTest {
    private val good = """
        Ini spec-nya:
        ```json
        {
          "app": {"name": "About Muslim", "kind": "pwa", "lang": "id"},
          "features": [{"id": "F1", "title": "Waktu sholat", "accept": ["halaman memuat tanpa error"]}],
          "files": [
            {"path": "index.html", "features": ["F1"], "uses": ["js/prayer.js"]},
            {"path": "js/prayer.js", "features": ["F1"], "exports": ["computeTimes(lat, lon, date)"]}
          ],
          "facts": ["kartu:prayer-times"]
        }
        ```
    """.trimIndent()

    private fun ok(text: String): AppSpec = (SpecParser.parse(text) as SpecResult.Ok).spec

    @Test fun parsesFencedJson() {
        val s = ok(good)
        assertEquals("About Muslim", s.name)
        assertEquals(2, s.files.size)
        assertEquals(listOf("computeTimes(lat, lon, date)"), s.files[1].exports)
        assertTrue(SpecValidator.validate(s).isEmpty())
    }

    @Test fun badJsonIsAnError() {
        assertTrue(SpecParser.parse("tidak ada json") is SpecResult.Err)
        assertTrue(SpecParser.parse("{ \"app\": ") is SpecResult.Err)
    }

    @Test fun validatorCatchesProblems() {
        val base = ok(good)
        fun errs(s: AppSpec) = SpecValidator.validate(s).joinToString("|")
        assertTrue("index.html" in errs(base.copy(files = base.files.filter { it.path != "index.html" })))
        assertTrue("tidak aman" in errs(base.copy(files = base.files + FileSpec("../x.js", listOf("F1"), emptyList(), emptyList()))))
        assertTrue("dilarang" in errs(base.copy(files = base.files + FileSpec(".github/workflows/a.yml", listOf("F1"), emptyList(), emptyList()))))
        assertTrue("tidak ada: F9" in errs(base.copy(files = listOf(base.files[0].copy(features = listOf("F9")), base.files[1]))))
        assertTrue("siklus" in errs(base.copy(files = listOf(base.files[0].copy(uses = listOf("js/prayer.js")), base.files[1].copy(uses = listOf("index.html"))))))
        assertTrue("kriteria" in errs(base.copy(features = listOf(Feature("F1", "x", emptyList())))))
        assertTrue("Jenis aplikasi" in errs(base.copy(kind = "android")))
    }
}
