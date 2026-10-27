package com.pixelforge.ui

import org.junit.Assert.*
import org.junit.Test

class BriefTest {
    @Test fun addsRequestUnderHeader() {
        assertEquals(Brief.HEADER + "\n- buat web tentang sholat", Brief.add(Brief.HEADER, "buat web   tentang\nsholat"))
    }

    @Test fun keepsFirstAndLastFive() {
        var b = Brief.HEADER
        for (i in 1..10) b = Brief.add(b, "req$i")
        val items = b.lines().filter { it.startsWith("- ") }
        assertEquals(listOf("- req1", "- req6", "- req7", "- req8", "- req9", "- req10"), items)
    }

    @Test fun ignoresBlankAndTrimsLong() {
        assertEquals(Brief.HEADER, Brief.add(Brief.HEADER, "   "))
        assertEquals(302, Brief.add(Brief.HEADER, "x".repeat(1000)).lines().last().length)
    }

    @Test fun oldTemplateDetected() {
        assertTrue(Brief.isOldTemplate("# Project Bible\n(Tulis tujuan proyekmu di sini)"))
        assertFalse(Brief.isOldTemplate(Brief.HEADER))
    }
}
