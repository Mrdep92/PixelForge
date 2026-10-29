package com.pixelforge.engine

import org.junit.Assert.*
import org.junit.Test

class TicketsTest {
    private val spec = AppSpec(
        "App", "pwa", "id",
        listOf(Feature("F1", "Waktu", listOf("tampil 5 waktu"))),
        listOf(
            FileSpec("index.html", listOf("F1"), emptyList(), listOf("js/a.js")),
            FileSpec("js/a.js", listOf("F1"), listOf("hitung(x)"), emptyList())
        ),
        listOf("kartu:x"), emptyList()
    )

    @Test fun dependenciesComeFirst() {
        assertEquals(listOf("js/a.js", "index.html"), TicketPlanner.plan(spec).map { it.file })
        assertEquals(listOf("T1", "T2"), TicketPlanner.plan(spec).map { it.id })
    }

    @Test fun ticketCarriesInterfacesAndFacts() {
        val t = TicketPlanner.plan(spec)[1]
        assertEquals(listOf("js/a.js: hitung(x)"), t.interfaces)
        assertEquals(listOf("kartu:x"), t.facts)
        assertTrue(t.goal.contains("F1"))
    }

    @Test fun packetIsStatelessAndBounded() {
        val t = TicketPlanner.plan(spec)[1]
        val u = Packet.user(t, "id", null, mapOf("js/a.js" to "L1 function hitung(x)"), listOf("tag <div> tidak ditutup"))
        assertTrue(u.contains("TICKET T2") && u.contains("OUTLINE js/a.js") && u.contains("PROBLEMS FOUND"))
        assertTrue(Packet.user(t, "id", "x".repeat(100_000), emptyMap(), null).length <= Packet.MAX_USER)
    }

    @Test fun outlineOfJsAndHtml() {
        val js = "function a(x) {}\nconst b = 1\nclass C {}\n"
        assertEquals("L1 function a(x)\nL2 const b\nL3 class C", Outline.of("a.js", js))
        assertEquals("L1 #main\nL2 #btn", Outline.of("i.html", "<div id=\"main\">\n<button id='btn'>"))
    }
}
