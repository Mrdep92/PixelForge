package com.pixelforge.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private class SandboxScriptedLlm(private val replies: List<String>) : Llm {
    val users = mutableListOf<String>()
    private var i = 0
    override suspend fun call(system: String, user: String): String {
        users += user
        return replies[minOf(i++, replies.size - 1)]
    }
}

private class FakeSandbox(private val reports: List<SandboxReport>) : Sandbox {
    var runs = 0
    override suspend fun run(plan: TestPlan, timeoutMs: Long): SandboxReport = reports[minOf(runs++, reports.size - 1)]
}

class EngineSandboxTest {
    private val ready = "STATUS: READY\nSUMMARY: Waktu sholat harian.\nASSUMPTION: Tanpa akun"
    private val spec = """
        {"app":{"name":"Sholat","kind":"pwa","lang":"id"},
         "features":[{"id":"F1","title":"Waktu sholat","accept":["tampil"]}],
         "files":[{"path":"index.html","features":["F1"],"uses":["js/a.js"]},
                  {"path":"js/a.js","features":["F1"],"exports":["hitung(x)"]}],
         "tests":[{"kind":"exists","label":"judul","selector":"h1"},
                  {"kind":"call","label":"hitung dua","fn":"hitung","args":[2],"expect":2,"verified":true}]}
    """.trimIndent()
    private val js = "=== FILE: js/a.js ===\nfunction hitung(x) { return x; }\n=== END FILE ==="
    private val jsFixed = "=== FILE: js/a.js ===\nfunction hitung(x) { return x + 0; }\n=== END FILE ==="
    private val html = "=== FILE: index.html ===\n<!doctype html><html><body><h1>Sholat</h1><script src=\"js/a.js\"></script></body></html>\n=== END FILE ==="

    private val ok = SandboxReport(true, emptyList(), listOf(CheckResult("judul", true, "")))
    private val codeBug = SandboxReport(
        true, listOf("ReferenceError: foo is not defined (a.js:1)"),
        listOf(CheckResult("hitung dua", false, "hasil 3, harapan 2"))
    )
    private val testBug = SandboxReport(
        true, emptyList(),
        listOf(CheckResult("hitung dua", false, "hasil 3, harapan 2"))
    )

    private fun flow(llm: Llm, sb: Sandbox?, store: MemoryStore = MemoryStore()) =
        Triple(EngineFlow(llm, store, InMemoryEventLog(), sandbox = sb), store, llm)

    private suspend fun toRunning(f: EngineFlow) {
        f.handle("buat web sholat harian")      // wawancara → langsung siap (ready) → blueprint
        assertEquals(Stage.REVIEW, f.state.stage)
    }

    @Test fun blueprintCarriesTestsIntoState() = runBlocking {
        val (f, _, _) = flow(SandboxScriptedLlm(listOf(ready, spec)), null)
        val says = f.handle("buat web sholat harian")
        assertEquals(Stage.REVIEW, f.state.stage)
        assertTrue(f.state.planJson.contains("hitung"))
        assertTrue(says[0].text.contains("Tes otomatis: 2 (1 dengan nilai acuan terverifikasi)"))
    }

    @Test fun passesWhenSandboxPasses() = runBlocking {
        val sb = FakeSandbox(listOf(ok))
        val llm = SandboxScriptedLlm(listOf(ready, spec, js, html))
        val (f, store, _) = flow(llm, sb)
        toRunning(f)
        val m = f.handle("setuju")
        assertEquals(Stage.DONE, f.state.stage)
        assertEquals(1, sb.runs)
        assertTrue(m.any { it.text.contains("Lolos uji otomatis") })
        assertTrue(store.files.containsKey("PROJECT.md"))
        assertEquals(4, llm.users.size)                       // wawancara(ready) + blueprint + 2 tiket
        assertTrue(m.last().text.contains("4 panggilan"))
    }

    @Test fun debuggerFixesCodeBugThenPasses() = runBlocking {
        val sb = FakeSandbox(listOf(codeBug, ok))
        val llm = SandboxScriptedLlm(listOf(ready, spec, js, html, jsFixed))
        val (f, store, _) = flow(llm, sb)
        toRunning(f)
        val m = f.handle("setuju")
        assertEquals(Stage.DONE, f.state.stage)
        assertEquals(2, sb.runs)
        assertEquals(5, llm.users.size)                       // + 1 perbaikan
        assertTrue(llm.users.last().contains("PROBLEMS FOUND"))
        assertTrue(llm.users.last().contains("foo is not defined"))
        assertTrue(store.files["js/a.js"]!!.contains("x + 0"))
        assertTrue(m.any { it.text.contains("Lolos uji") })
    }

    @Test fun stopsAfterTwoRoundsAndKeepsRunning() = runBlocking {
        val sb = FakeSandbox(listOf(codeBug))                 // selalu gagal
        val llm = SandboxScriptedLlm(listOf(ready, spec, js, html, jsFixed))
        val (f, _, _) = flow(llm, sb)
        toRunning(f)
        val m = f.handle("setuju")
        assertEquals(Stage.RUNNING, f.state.stage)
        assertEquals(3, sb.runs)                              // uji awal + 2 putaran perbaikan
        assertEquals(6, llm.users.size)                       // 4 + 2 perbaikan, lalu berhenti
        assertTrue(m.last().text.contains("lanjut"))
    }

    @Test fun unverifiedExpectationDoesNotTriggerDebugger() = runBlocking {
        val unverifiedSpec = spec.replace("\"verified\":true", "\"verified\":false")
        val sb = FakeSandbox(listOf(testBug))
        val llm = SandboxScriptedLlm(listOf(ready, unverifiedSpec, js, html))
        val (f, _, _) = flow(llm, sb)
        toRunning(f)
        val m = f.handle("setuju")
        assertEquals(Stage.DONE, f.state.stage)
        assertEquals(1, sb.runs)
        assertEquals(4, llm.users.size)                       // tanpa perbaikan
        assertTrue(m.last().text.contains("TEST_BUG") || m.any { it.text.contains("TEST_BUG") })
    }

    @Test fun invalidTestsTriggerBlueprintRepair() = runBlocking {
        val bad = spec.replace("\"fn\":\"hitung\"", "\"fn\":\"tidakAda\"")
        val llm = SandboxScriptedLlm(listOf(ready, bad, spec))
        val (f, _, _) = flow(llm, null)
        f.handle("buat web sholat harian")
        assertEquals(Stage.REVIEW, f.state.stage)
        assertTrue(llm.users[2].contains("tests:") && llm.users[2].contains("tidak dijanjikan"))
    }

    @Test fun cutOffBlueprintIsRetriedShorterWithoutEchoingTheFragment() = runBlocking {
        val cut = spec.take(120)                                    // JSON terpotong di tengah
        val llm = SandboxScriptedLlm(listOf(ready, cut, spec))
        val (f, _, _) = flow(llm, null)
        f.handle("buat web sholat harian")
        assertEquals(Stage.REVIEW, f.state.stage)
        assertTrue(llm.users[2].contains("CUT OFF"))
        assertFalse(llm.users[2].contains("PREVIOUS REPLY:"))
    }

    @Test fun failedBlueprintCanBeRetriedWithUlang() = runBlocking {
        val cut = spec.take(120)
        val llm = SandboxScriptedLlm(listOf(ready, cut, cut, cut, spec))   // 1 + 2 perbaikan gagal, lalu "ulang" berhasil
        val (f, _, _) = flow(llm, null)
        val m = f.handle("buat web sholat harian")
        assertEquals(Stage.IDLE, f.state.stage)
        assertTrue(m[0].text.contains("ulang"))
        f.handle("ulang")
        assertEquals(Stage.REVIEW, f.state.stage)
    }

    @Test fun implicatedFilesFollowFailures() {
        val s = (SpecParser.parse(spec) as SpecResult.Ok).spec
        val plan = (PlanParser.parse(spec) as PlanResult.Ok).plan
        val files = Implicated.files(
            listOf(Failure(FailureKind.CODE_BUG, "Galat konsol: x (a.js:4)"), Failure(FailureKind.CODE_BUG, "judul: tidak ada elemen h1")),
            plan, s
        )
        assertEquals(listOf("js/a.js", "index.html"), files)
    }
}
