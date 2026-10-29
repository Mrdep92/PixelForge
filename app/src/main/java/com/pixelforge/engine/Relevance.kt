package com.pixelforge.engine

/** Pemeriksaan murah tanpa LLM: blueprint harus menyinggung kata kunci dari ide pengguna. */
object Relevance {
    private val STOP = setOf(
        "yang", "dengan", "untuk", "dari", "buat", "buatkan", "bikin", "aplikasi", "web", "halaman", "sederhana",
        "saya", "kamu", "dan", "atau", "pada", "this", "that", "with", "make", "build", "create", "app", "page", "simple"
    )

    fun keywords(idea: String): Set<String> =
        idea.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in STOP }.toSet()

    /** True bila tidak ada kata kunci (ide terlalu pendek untuk dinilai) atau minimal satu muncul di blueprint. */
    fun matches(idea: String, spec: AppSpec): Boolean {
        val keys = keywords(idea)
        if (keys.isEmpty()) return true
        val text = buildString {
            append(spec.name).append(' ')
            spec.features.forEach { append(it.title).append(' ') }
            spec.decisions.forEach { append(it).append(' ') }
        }.lowercase()
        return keys.any { text.contains(it) }
    }
}
