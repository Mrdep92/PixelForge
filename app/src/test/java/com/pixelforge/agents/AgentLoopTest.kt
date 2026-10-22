package com.pixelforge.agents

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeExec(initial: Map<String, String> = emptyMap()) : Executor {
    val files = initial.toMutableMap()
    override fun read(path: String): String? = files[path]
    override fun write(path: String, content: String) { files[path] = content }
    override fun list(): List<String> = files.keys.sorted()
}

class AgentLoopTest {

    private fun scripted(replies: List<String>, seen: MutableList<List<AgentLoop.Msg>> = mutableListOf()): suspend (List<AgentLoop.Msg>) -> String {
        var i = 0
        return { msgs ->
            seen += msgs
            replies[minOf(i++, replies.size - 1)]
        }
    }

    @Test
    fun readEditDone() = runBlocking {
        val exec = FakeExec(mapOf("a.txt" to "hello\nworld"))
        val loop = AgentLoop(
            scripted(
                listOf(
                    "=== READ: a.txt ===",
                    "=== EDIT: a.txt ===\n<<<<<<< SEARCH\nworld\n=======\nandroid\n>>>>>>> REPLACE\n=== END EDIT ===",
                    "=== DONE ===\nSudah diganti.\n=== END DONE ==="
                )
            ),
            exec
        )
        val r = loop.run("sys", "ganti world")
        assertTrue(r.finished)
        assertEquals(3, r.turns)
        assertEquals(listOf("a.txt"), r.changed)
        assertEquals("hello\nandroid", exec.files["a.txt"])
        assertEquals("Sudah diganti.", r.summary)
    }

    @Test
    fun failedEditIsReportedBackToModel() = runBlocking {
        val exec = FakeExec(mapOf("a.txt" to "hello"))
        val seen = mutableListOf<List<AgentLoop.Msg>>()
        val loop = AgentLoop(
            scripted(
                listOf(
                    "=== EDIT: a.txt ===\n<<<<<<< SEARCH\nTIDAK ADA\n=======\nx\n>>>>>>> REPLACE\n=== END EDIT ===",
                    "=== DONE ===\nmenyerah\n=== END DONE ==="
                ),
                seen
            ),
            exec
        )
        val r = loop.run("sys", "task")
        assertEquals("hello", exec.files["a.txt"])
        assertTrue(r.changed.isEmpty())
        val lastUser = seen.last().last { it.role == "user" }.content
        assertTrue(lastUser.contains("ERROR"))
    }

    @Test
    fun truncatedFileIsNotWritten() = runBlocking {
        val exec = FakeExec()
        val seen = mutableListOf<List<AgentLoop.Msg>>()
        val loop = AgentLoop(
            scripted(
                listOf(
                    "=== FILE: big.kt ===\nfun a() {",
                    "=== DONE ===\nok\n=== END DONE ==="
                ),
                seen
            ),
            exec
        )
        loop.run("sys", "task")
        assertFalse(exec.files.containsKey("big.kt"))
        assertTrue(seen.last().last { it.role == "user" }.content.contains("terpotong"))
    }

    @Test
    fun writeNewFile() = runBlocking {
        val exec = FakeExec()
        val loop = AgentLoop(
            scripted(listOf("=== FILE: n/new.txt ===\nisi\n=== END FILE ===", "=== DONE ===\nselesai\n=== END DONE ===")),
            exec
        )
        val r = loop.run("sys", "task")
        assertEquals("isi", exec.files["n/new.txt"]?.trim())
        assertEquals(listOf("n/new.txt"), r.changed)
    }

    @Test
    fun plainAnswerGetsOneNudgeThenEnds() = runBlocking {
        val seen = mutableListOf<List<AgentLoop.Msg>>()
        val loop = AgentLoop(scripted(listOf("Ini jawaban akhir."), seen), FakeExec())
        val r = loop.run("sys", "task")
        assertTrue(r.finished)
        assertEquals(2, r.turns)
        assertEquals("Ini jawaban akhir.", r.summary)
        assertTrue(seen.last().last { it.role == "user" }.content.contains("not finished"))
    }

    @Test
    fun nudgeLetsModelContinueWithTools() = runBlocking {
        val exec = FakeExec()
        val loop = AgentLoop(
            scripted(
                listOf(
                    "Saya akan membuat file itu sekarang.",
                    "=== FILE: a.txt ===\nisi\n=== END FILE ===",
                    "=== DONE ===\nselesai\n=== END DONE ==="
                )
            ),
            exec
        )
        val r = loop.run("sys", "task")
        assertTrue(r.finished)
        assertEquals(3, r.turns)
        assertEquals("isi", exec.files["a.txt"]?.trim())
    }

    @Test
    fun doneBlockIsNotNudged() = runBlocking {
        val loop = AgentLoop(scripted(listOf("=== DONE ===\nok\n=== END DONE ===")), FakeExec())
        val r = loop.run("sys", "task")
        assertEquals(1, r.turns)
    }

    @Test
    fun wrapperFilesAreRejected() = runBlocking {
        val exec = FakeExec()
        val seen = mutableListOf<List<AgentLoop.Msg>>()
        val loop = AgentLoop(
            scripted(
                listOf(
                    "=== FILE: gradlew ===\n#!/bin/sh\n=== END FILE ===",
                    "=== DONE ===\nok\n=== END DONE ==="
                ),
                seen
            ),
            exec
        )
        val r = loop.run("sys", "task")
        assertFalse(exec.files.containsKey("gradlew"))
        assertTrue(r.changed.isEmpty())
        assertTrue(seen.last().last { it.role == "user" }.content.contains("dilindungi"))
    }

    @Test
    fun existingWorkflowCannotBeEdited() = runBlocking {
        val path = ".github/workflows/build.yml"
        val exec = FakeExec(mapOf(path to "name: ci"))
        val loop = AgentLoop(
            scripted(
                listOf(
                    "=== EDIT: $path ===\n<<<<<<< SEARCH\nname: ci\n=======\nname: x\n>>>>>>> REPLACE\n=== END EDIT ===",
                    "=== DONE ===\nok\n=== END DONE ==="
                )
            ),
            exec
        )
        loop.run("sys", "task")
        assertEquals("name: ci", exec.files[path])
    }

    @Test
    fun newWorkflowCanBeCreated() = runBlocking {
        val exec = FakeExec()
        val loop = AgentLoop(
            scripted(
                listOf(
                    "=== FILE: .github/workflows/ci.yml ===\nname: ci\n=== END FILE ===",
                    "=== DONE ===\nok\n=== END DONE ==="
                )
            ),
            exec
        )
        loop.run("sys", "task")
        assertTrue(exec.files.containsKey(".github/workflows/ci.yml"))
    }

    @Test
    fun stopsAtMaxTurns() = runBlocking {
        val loop = AgentLoop(scripted(listOf("=== LIST: . ===")), FakeExec(mapOf("a" to "x")), maxTurns = 3)
        val r = loop.run("sys", "task")
        assertFalse(r.finished)
        assertEquals(3, r.turns)
    }

    @Test
    fun grepAndListWork() = runBlocking {
        val exec = FakeExec(mapOf("src/a.kt" to "fun main() {}", "src/b.kt" to "val x = 1"))
        val seen = mutableListOf<List<AgentLoop.Msg>>()
        val loop = AgentLoop(
            scripted(listOf("=== LIST: src ===\n=== GREP: fun main ===", "=== DONE ===\nok\n=== END DONE ==="), seen),
            exec
        )
        loop.run("sys", "task")
        val results = seen.last().last { it.role == "user" }.content
        assertTrue(results.contains("src/a.kt:1"))
        assertTrue(results.contains("src/b.kt"))
    }
}
