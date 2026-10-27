package com.pixelforge.ui

/** Ringkasan project otomatis dari chat (pengganti Project Bible manual). Nol panggilan LLM. */
object Brief {
    const val HEADER = "# Ringkasan project (otomatis dari chat)"
    private const val KEEP_LAST = 5
    private const val ITEM_MAX = 300

    /** Template Bible lama yang tidak pernah diisi dianggap kosong. */
    fun isOldTemplate(text: String) =
        text.contains("(Tulis tujuan proyekmu di sini)") || text.contains("(tulis tujuan di sini)")

    /** Permintaan pertama + KEEP_LAST terakhir, masing-masing dipangkas. */
    fun add(brief: String, request: String): String {
        val one = request.trim().replace(Regex("\\s+"), " ").take(ITEM_MAX)
        if (one.isEmpty()) return brief
        val items = brief.lines().filter { it.startsWith("- ") }.toMutableList()
        items += "- $one"
        val kept = if (items.size > KEEP_LAST + 1) listOf(items.first()) + items.takeLast(KEEP_LAST) else items
        return HEADER + "\n" + kept.joinToString("\n")
    }
}
