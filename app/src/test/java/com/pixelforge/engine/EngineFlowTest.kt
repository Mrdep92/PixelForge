package com.pixelforge.engine

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private class Replies(private val replies: List<String>) : Llm {
    val users = mutableListOf<String>()
    private var i = 0
    override suspend fun call(system: String, user: String): String {
        users += user
        return replies[minOf(i++, replies.size - 1)]
    }
}

class EngineFlowTest {
    private val ask = "STATUS: ASK\nQ1: Lokasi? || REC: GPS\nQ2: Metode? || REC: MWL"
    private val ready = "STATUS: READY\nSUMMARY: Waktu sholat.\nASSUMPTION: Tanpa akun"
    private val spec = """
        {"app":{"name":"Sholat","kind":"pwa","lang":"id"},
         "features":[{"id":"F1","title":"Waktu","accept":["tampil"]}],
         "files":[{"path":"index.html","features":["F1"],"uses":["js/a.js"]},
                  {"path":"js/a.js","features":["F1"],"exports":["hitung(x)"]}]}
    """.trimIndent()
    private val js = "=== FILE: js/a.js ===\nfunction hitung(x) { return x; }\n=== END FILE ==="
    private val html = "=== FILE: index.html ===\n<!doctype html><html><body><script src=\"js/a.js\"></script></body></html>\n=== END FILE ==="

    @Test fun fullFlowFromIdeaToDone() = runBlocking {
        val llm = Replies(listOf(ask, ready, spec, js, html))
        val store = MemoryStore()
        val flow = EngineFlow(llm, store, InMemoryEventLog())

        val m1 = flow.handle("buat web sholat harian")
        assertEquals(Stage.INTERVIEW, flow.state.stage)
        assertTrue(m1[0].text.contains("saran: GPS"))

        val m2 = flow.handle("ya")                       // terima semua saran → Ready → blueprint
        assertEquals(Stage.REVIEW, flow.state.stage)
        assertTrue(m2[0].who == "BLUEPRINT" && m2[0].text.contains("setuju"))
        assertTrue(llm.users[1].contains("Lokasi? => GPS") && llm.users[1].contains("Metode? => MWL"))

        val m3 = flow.handle("setuju")
        assertEquals(Stage.DONE, flow.state.stage)
        assertTrue(m3.any { it.text.contains("2 file") })
        assertEquals(setOf("js/a.js", "index.html", "PROJECT.md"), store.files.keys)
        assertEquals(5, llm.users.size)                  // 2 wawancara + 1 blueprint + 2 tiket
    }

    @Test fun stateSurvivesRestartMidInterview() = runBlocking {
        var saved: String? = null
        val llm1 = Replies(listOf(ask))
        val f1 = EngineFlow(llm1, MemoryStore(), InMemoryEventLog(), onState = { saved = Gson().toJson(it) })
        f1.handle("buat aplikasi sederhana ide")
        val restored = Gson().fromJson(saved, FlowState::class.java)
        val llm2 = Replies(listOf(ready, spec))
        val f2 = EngineFlow(llm2, MemoryStore(), InMemoryEventLog(), restored)
        f2.handle("GPS\nMWL")
        assertEquals(Stage.REVIEW, f2.state.stage)
        assertTrue(llm2.users[0].contains("Lokasi? => GPS") && llm2.users[0].contains("Metode? => MWL"))
    }

    @Test fun revisionRerunsBlueprintOnly() = runBlocking {
        val llm = Replies(listOf(ready, spec, spec))
        val flow = EngineFlow(llm, MemoryStore(), InMemoryEventLog())
        flow.handle("buat aplikasi sederhana ide")                               // langsung Ready → blueprint
        assertEquals(Stage.REVIEW, flow.state.stage)
        flow.handle("tambahkan mode gelap")
        assertEquals(Stage.REVIEW, flow.state.stage)
        assertTrue(llm.users.last().contains("REVISION: tambahkan mode gelap"))
    }

    @Test fun resumeAfterLimitContinuesRemainingTickets() = runBlocking {
        val store = MemoryStore()
        val log = InMemoryEventLog()
        var calls = 0
        val flaky = object : Llm {
            override suspend fun call(system: String, user: String): String {
                calls++
                return when {
                    "STATUS" in Interview.SYSTEM && user.startsWith("IDEA") -> ready
                    user.startsWith("BRIEF") -> spec
                    user.contains("TICKET T1") -> js
                    else -> throw IllegalStateException("limit")          // T2 kena limit
                }
            }
        }
        val flow = EngineFlow(flaky, store, log)
        flow.handle("buat aplikasi sederhana ide")
        try { flow.handle("setuju"); fail("harus melempar") } catch (e: IllegalStateException) {}
        assertEquals(Stage.RUNNING, flow.state.stage)
        assertTrue(store.files.containsKey("js/a.js"))

        val llm2 = Replies(listOf(html))
        val flow2 = EngineFlow(llm2, store, log, flow.state)
        val msgs = flow2.handle("lanjut")
        assertEquals(1, llm2.users.size)                 // hanya T2
        assertEquals(Stage.DONE, flow2.state.stage)
        assertTrue(msgs.any { it.text.contains("Selesai") })
    }

    @Test fun newRunDoesNotInheritOldPassedTickets() {
        val old = listOf(Event(Event.TICKET_PASSED, "T1", "a", 1, 1), Event(Event.RUN_STARTED), Event(Event.TICKET_PASSED, "T2", "b", 2, 2))
        assertEquals(setOf("T2"), RunState.from(old).passed)
    }

    @Test fun specJsonRoundTrips() {
        val s = (SpecParser.parse(spec) as SpecResult.Ok).spec
        val back = (SpecParser.parse(SpecJson.write(s)) as SpecResult.Ok).spec
        assertEquals(s, back)
    }

    @Test fun approvalWords() {
        assertTrue(EngineFlow.isApproval(" Setuju! "))
        assertFalse(EngineFlow.isApproval("tambahkan fitur"))
    }

    @Test fun greetingDoesNotCallLlm() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val llm = object : Llm { override suspend fun call(system: String, user: String): String { calls++; return "" } }
        val flow = EngineFlow(llm, MemoryStore(), InMemoryEventLog())
        val m = flow.handle("halo")
        assertTrue(calls == 0 && m[0].who == "SYSTEM")
    }
}
