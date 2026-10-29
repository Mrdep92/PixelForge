package com.pixelforge.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectNotesTest {
    @Test fun rendersFeaturesFilesAndDecisions() {
        val spec = AppSpec(
            "Sholat", "pwa", "id",
            listOf(Feature("F1", "Waktu sholat", listOf("jam tampil"))),
            listOf(FileSpec("index.html", listOf("F1"), listOf("hitung(lat, lng)"), emptyList(), emptyList())),
            emptyList(), listOf("tanpa library")
        )
        val md = ProjectNotes.render(spec)
        assertTrue(md.contains("# Sholat") && md.contains("F1 Waktu sholat") && md.contains("hitung(lat, lng)") && md.contains("tanpa library"))
    }
}
