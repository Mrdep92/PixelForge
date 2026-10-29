package com.pixelforge.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelevanceTest {
    private fun spec(name: String, vararg titles: String) = AppSpec(
        name, "pwa", "id", titles.mapIndexed { i, t -> Feature("F$i", t, emptyList()) },
        emptyList(), emptyList(), emptyList()
    )

    @Test fun matchingBlueprintPasses() {
        assertTrue(Relevance.matches("buat aplikasi waktu sholat dan kiblat", spec("About Muslim", "Waktu sholat harian")))
    }

    @Test fun unrelatedBlueprintFails() {
        assertFalse(Relevance.matches("buat aplikasi waktu sholat dan kiblat", spec("Repository Health Checklist", "Daftar centang")))
    }

    @Test fun shortIdeaIsNotJudged() {
        assertTrue(Relevance.matches("buat web", spec("Apa saja")))
    }
}
