package com.pixelforge.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProtocolTest {

    @Test
    fun parsesSingleLineTools() {
        val calls = ToolProtocol.parse(
            "Mari lihat.\n=== LIST: . ===\n=== READ: src/A.kt 10-20 ===\n=== READ: B.kt ===\n=== GREP: foo.*bar ===\n"
        )
        assertEquals(4, calls.size)
        assertEquals(ToolProtocol.Call.ListDir("."), calls[0])
        assertEquals(ToolProtocol.Call.Read("src/A.kt", 10, 20), calls[1])
        assertEquals(ToolProtocol.Call.Read("B.kt", null, null), calls[2])
        assertEquals(ToolProtocol.Call.Grep("foo.*bar"), calls[3])
    }

    @Test
    fun parsesCompleteFileBlock() {
        val calls = ToolProtocol.parse("=== FILE: a/b.txt ===\nline1\nline2\n=== END FILE ===\n")
        val w = calls.single() as ToolProtocol.Call.Write
        assertEquals("a/b.txt", w.path)
        assertEquals("line1\nline2", w.content)
        assertTrue(w.complete)
    }

    @Test
    fun truncatedFileBlockIsIncomplete() {
        val calls = ToolProtocol.parse("=== FILE: a.txt ===\nline1\nline2")
        val w = calls.single() as ToolProtocol.Call.Write
        assertFalse(w.complete)
    }

    @Test
    fun newHeaderBeforeEndMarksPreviousIncomplete() {
        val calls = ToolProtocol.parse("=== FILE: a.txt ===\nabc\n=== READ: b.txt ===\n")
        assertEquals(2, calls.size)
        assertFalse((calls[0] as ToolProtocol.Call.Write).complete)
        assertTrue(calls[1] is ToolProtocol.Call.Read)
    }

    @Test
    fun parsesEditBlock() {
        val text = """
            === EDIT: x.kt ===
            <<<<<<< SEARCH
            val a = 1
            =======
            val a = 2
            >>>>>>> REPLACE
            === END EDIT ===
        """.trimIndent()
        val e = ToolProtocol.parse(text).single() as ToolProtocol.Call.Edit
        assertEquals("x.kt", e.path)
        assertEquals("val a = 1", e.search)
        assertEquals("val a = 2", e.replace)
        assertTrue(e.complete)
    }

    @Test
    fun editWithoutReplaceMarkerIsIncomplete() {
        val text = "=== EDIT: x.kt ===\n<<<<<<< SEARCH\nold\n=======\nnew\n"
        val e = ToolProtocol.parse(text).single() as ToolProtocol.Call.Edit
        assertFalse(e.complete)
    }

    @Test
    fun parsesDone() {
        val d = ToolProtocol.parse("=== DONE ===\nSemua beres.\n=== END DONE ===").single() as ToolProtocol.Call.Done
        assertEquals("Semua beres.", d.summary)
    }

    @Test
    fun plainTextHasNoCalls() {
        assertTrue(ToolProtocol.parse("Halo, ini jawaban biasa tanpa tool.").isEmpty())
    }

    @Test
    fun safePathRejectsTraversal() {
        assertNull(ToolProtocol.safePath("../etc/passwd"))
        assertNull(ToolProtocol.safePath("a/../b"))
        assertNull(ToolProtocol.safePath(""))
        assertEquals("a/b.kt", ToolProtocol.safePath("./a/b.kt"))
        assertEquals("a/b.kt", ToolProtocol.safePath("/a/b.kt"))
    }

    @Test
    fun applyEditExactUnique() {
        val r = ToolProtocol.applyEdit("a\nb\nc", "b", "B")
        assertEquals("a\nB\nc", (r as ToolProtocol.EditOutcome.Ok).text)
    }

    @Test
    fun applyEditNotFound() {
        val r = ToolProtocol.applyEdit("a\nb", "zzz", "x")
        assertTrue(r is ToolProtocol.EditOutcome.Fail)
    }

    @Test
    fun applyEditAmbiguous() {
        val r = ToolProtocol.applyEdit("x\nx", "x", "y")
        assertTrue(r is ToolProtocol.EditOutcome.Fail)
    }

    @Test
    fun applyEditIgnoresTrailingWhitespaceDifferences() {
        val r = ToolProtocol.applyEdit("foo()   \nbar()", "foo()\nbar()", "baz()")
        assertEquals("baz()", (r as ToolProtocol.EditOutcome.Ok).text)
    }
}
