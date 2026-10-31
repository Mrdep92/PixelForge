package com.pixelforge.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private class Seq(private val replies: List<String>) : Llm {
    val users = mutableListOf<String>()
    private var i = 0
    override suspend fun call(system: String, user: String): String {
        users += user
        return replies[minOf(i++, replies.size - 1)]
    }
}

class InterviewBlueprintTest {

    @Test fun parsesAskAndReady() {
        val ask = Interview.parse("STATUS: ASK\nQ1: Pakai lokasi otomatis? || REC: Ya, dengan tombol manual\nQ2: Bahasa? || REC: Indonesia")
        assertTrue(ask is InterviewStep.Ask)
        assertEquals(2, (ask as InterviewStep.Ask).questions.size)
        assertEquals("Ya, dengan tombol manual", ask.questions[0].recommended)
        val ready = Interview.parse("STATUS: READY\nSUMMARY: Web sholat.\nASSUMPTION: Metode MWL\nASSUMPTION: Tanpa akun")
        assertTrue(ready is InterviewStep.Ready)
        assertEquals(2, (ready as InterviewStep.Ready).assumptions.size)
        assertNull(Interview.parse("halo"))
        assertNull(Interview.parse("STATUS: ASK\ntanpa pertanyaan"))
    }

    @Test fun interviewStopsAtReadyAndBuildsBriefInCode() = runBlocking {
        val llm = Seq(listOf(
            "STATUS: ASK\nQ1: Lokasi? || REC: GPS",
            "STATUS: READY\nSUMMARY: Waktu sholat dan kiblat.\nASSUMPTION: Metode MWL"
        ))
        val iv = Interview("web info islam, waktu sholat dan kiblat", llm)
        val s1 = iv.next()
        assertTrue(s1 is InterviewStep.Ask)
        iv.answer(listOf(""))                         // kosong = terima rekomendasi
        val s2 = iv.next() as InterviewStep.Ready
        assertEquals(2, iv.calls)
        assertTrue(llm.users[1].contains("Lokasi? => GPS"))
        val brief = iv.brief(s2)
        assertTrue(brief.contains("DECISIONS:") && brief.contains("Lokasi? => GPS") && brief.contains("Metode MWL"))
    }

    @Test fun lastRoundForcesReadyEvenIfModelKeepsAsking() = runBlocking {
        val llm = Seq(listOf("STATUS: ASK\nQ1: a? || REC: b"))
        val iv = Interview("ide", llm)
        var step: InterviewStep = iv.next()
        var guard = 0
        while (step is InterviewStep.Ask && guard++ < 10) { iv.answer(emptyList()); step = iv.next() }
        assertTrue(step is InterviewStep.Ready)
        assertEquals(Interview.MAX_ROUNDS, iv.calls)
        assertTrue(llm.users.last().contains("last round"))
    }

    @Test fun unreadableReplyFallsBackToIdea() = runBlocking {
        val step = Interview("ide saya", Seq(listOf("blabla"))).next()
        assertTrue(step is InterviewStep.Ready)
        assertEquals("ide saya", (step as InterviewStep.Ready).summary)
    }

    private val goodSpec = """
        {"app":{"name":"About Muslim","kind":"pwa","lang":"id"},
         "features":[{"id":"F1","title":"Waktu sholat","accept":["lima waktu tampil"]}],
         "files":[{"path":"index.html","features":["F1"],"uses":["js/p.js"]},
                  {"path":"js/p.js","features":["F1"],"exports":["computeTimes(lat, lon)"],"cards":["prayer-times"]}]}
    """.trimIndent()

    @Test fun blueprintOkInOneCall() = runBlocking {
        val llm = Seq(listOf(goodSpec))
        val r = Blueprint.run(llm, "IDEA: x") as BlueprintResult.Ok
        assertEquals(1, r.calls)
        assertTrue(llm.users[0].contains("prayer-times"))            // katalog kartu ikut di prompt
        val t = TicketPlanner.plan(r.spec).first { it.file == "js/p.js" }
        assertTrue(t.facts.joinToString().contains("Dhuhr = 12 + timeZone - Lng/15 - EqT"))
        assertTrue(TicketPlanner.plan(r.spec).first { it.file == "index.html" }.facts.isEmpty())   // kartu hanya untuk file yang butuh
    }

    @Test fun blueprintRepairsTwiceThenFails() = runBlocking {
        val bad = goodSpec.replace("prayer-times", "kartu-ngarang")
        val fixedLlm = Seq(listOf(bad, goodSpec))
        assertTrue(Blueprint.run(fixedLlm, "b") is BlueprintResult.Ok)
        assertTrue(fixedLlm.users[1].contains("PROBLEMS") && fixedLlm.users[1].contains("kartu-ngarang"))
        val stuck = Seq(listOf(bad))
        val f = Blueprint.run(stuck, "b") as BlueprintResult.Failed
        assertEquals(3, f.calls)
        assertTrue(f.problems.any { "tidak dikenal" in it })
    }

    @Test fun verifiedCardsAreNotFlaggedAndCarryRequiredFunctions() {
        assertFalse(Cards.factsFor(listOf("qibla"))[0].contains("BELUM TERVERIFIKASI"))
        assertFalse(Cards.factsFor(listOf("prayer-times"))[0].contains("BELUM TERVERIFIKASI"))
        assertTrue(Cards.factsFor(listOf("prayer-times"))[0].contains("REQUIRED FUNCTIONS"))
        assertTrue(Cards.known("kartu:prayer-times"))
        assertFalse(Cards.known("x"))
    }
}
