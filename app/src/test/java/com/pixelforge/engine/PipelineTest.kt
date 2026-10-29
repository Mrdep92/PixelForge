package com.pixelforge.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private class Script(private val replies: List<String>) : Llm {
    val prompts = mutableListOf<String>()
    private var i = 0
    override suspend fun call(system: String, user: String): String {
        prompts += user
        return replies[minOf(i++, replies.size - 1)]
    }
}

private fun file(path: String, body: String) = "=== FILE: $path ===\n$body\n=== END FILE ==="

class PipelineTest {
    private val spec = AppSpec(
        "App", "pwa", "id",
        listOf(Feature("F1", "Waktu", listOf("tampil"))),
        listOf(
            FileSpec("index.html", listOf("F1"), emptyList(), listOf("js/a.js")),
            FileSpec("js/a.js", listOf("F1"), listOf("hitung(x)"), emptyList())
        ),
        emptyList(), emptyList()
    )
    private val goodJs = file("js/a.js", "function hitung(x) { return x + 1; }")
    private val goodHtml = file("index.html", "<!doctype html><html><body><div id=\"o\"></div><script src=\"js/a.js\"></script></body></html>")

    @Test fun happyPathUsesOneCallPerTicket() = runBlocking {
        val llm = Script(listOf(goodJs, goodHtml))
        val store = MemoryStore()
        val log = InMemoryEventLog()
        val r = TicketRunner(llm, store, log).run(spec)
        assertEquals(listOf("T1", "T2"), r.passed)
        assertEquals(2, r.calls)
        assertTrue(r.failed.isEmpty())
        assertTrue(store.files.containsKey("js/a.js") && store.files.containsKey("index.html"))
        assertFalse(llm.prompts[1].contains("function hitung(x) { return"))   // hanya outline, bukan isi file lain
        assertTrue(llm.prompts[1].contains("L1 function hitung(x)"))
    }

    @Test fun badOutputIsRepairedWithTargetedProblems() = runBlocking {
        val llm = Script(listOf(file("js/a.js", "function hitung(x) { return x + 1;"), goodJs, goodHtml))
        val r = TicketRunner(llm, MemoryStore(), InMemoryEventLog()).run(spec)
        assertEquals(listOf("T1", "T2"), r.passed)
        assertEquals(3, r.calls)
        assertTrue(llm.prompts[1].contains("PROBLEMS FOUND"))
        assertTrue(llm.prompts[1].contains("tidak ditutup"))
    }

    @Test fun writingOtherFilesIsRejected() = runBlocking {
        val sneaky = goodJs + "\n" + file("secret.js", "x")
        val llm = Script(listOf(sneaky, goodJs, goodHtml))
        val store = MemoryStore()
        val r = TicketRunner(llm, store, InMemoryEventLog()).run(spec)
        assertFalse(store.files.containsKey("secret.js"))
        assertTrue(llm.prompts[1].contains("Hanya boleh menulis"))
        assertEquals(2, r.passed.size)
    }

    @Test fun failsAfterMaxRepairsAndSkipsDependents() = runBlocking {
        val llm = Script(listOf("saya akan menulis filenya nanti"))
        val log = InMemoryEventLog()
        val r = TicketRunner(llm, MemoryStore(), log, maxRepairs = 2).run(spec)
        assertTrue(r.passed.isEmpty())
        assertEquals(3, r.calls)                       // 1 tulis + 2 perbaikan, lalu berhenti
        assertTrue(r.failed.getValue("T2").contains("dilewati"))
        assertTrue(log.all().any { it.type == Event.TICKET_FAILED })
    }

    @Test fun resumeSkipsPassedTickets() = runBlocking {
        val store = MemoryStore()
        val log = InMemoryEventLog()
        TicketRunner(Script(listOf(goodJs, "tidak ada blok")), store, log, maxRepairs = 0).run(spec)
        assertEquals(setOf("T1"), RunState.from(log.all()).passed)
        val llm2 = Script(listOf(goodHtml))
        val r = TicketRunner(llm2, store, InMemoryEventLog.fromLines((log as InMemoryEventLog).toLines())).run(spec)
        assertEquals(1, llm2.prompts.size)             // hanya T2 yang dikerjakan ulang
        assertEquals(listOf("T1", "T2"), r.passed)
    }

    @Test fun budgetStopsTheRunCleanly() = runBlocking {
        val llm = Script(listOf(goodJs, goodHtml))
        val r = TicketRunner(llm, MemoryStore(), InMemoryEventLog(), maxCalls = 1).run(spec)
        assertEquals(listOf("T1"), r.passed)
        assertNotNull(r.stopped)
        assertEquals(1, llm.prompts.size)
    }

    @Test fun llmFailurePausesAndRethrows() = runBlocking {
        val boom = object : Llm {
            override suspend fun call(system: String, user: String): String = throw IllegalStateException("limit")
        }
        val log = InMemoryEventLog()
        try {
            TicketRunner(boom, MemoryStore(), log).run(spec)
            fail("harus melempar")
        } catch (e: IllegalStateException) {
            assertTrue(log.all().any { it.type == Event.PAUSED })
        }
    }

    @Test fun editOnExistingFileIsApplied() = runBlocking {
        val store = MemoryStore(mapOf("js/a.js" to "function hitung(x) { return x; }"))
        val edit = "=== EDIT: js/a.js ===\n<<<<<<< SEARCH\nreturn x;\n=======\nreturn x + 2;\n>>>>>>> REPLACE\n=== END EDIT ==="
        val one = spec.copy(files = listOf(spec.files[1]), features = spec.features)
        TicketRunner(Script(listOf(edit)), store, InMemoryEventLog()).run(one)
        assertTrue(store.files.getValue("js/a.js").contains("x + 2"))
    }
}
