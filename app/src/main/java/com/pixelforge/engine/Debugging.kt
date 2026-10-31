package com.pixelforge.engine

/** Menghitung panggilan dan perkiraan token (4 karakter ≈ 1 token) untuk mengukur biaya nyata. */
class CountingLlm(private val inner: Llm) : Llm {
    var calls = 0; private set
    var tokens = 0; private set

    override suspend fun call(system: String, user: String): String {
        val reply = inner.call(system, user)
        calls++
        tokens += (system.length + user.length + reply.length) / 4
        return reply
    }
}

/** Menentukan file mana yang harus diperbaiki dari laporan uji, tanpa LLM. */
object Implicated {
    private val SRC = Regex("""\(([^():]+):\d+\)\s*$""")
    private const val MAX_FILES = 3

    fun files(failures: List<Failure>, plan: TestPlan, spec: AppSpec): List<String> {
        val paths = spec.files.map { it.path }
        val out = linkedSetOf<String>()
        fun byName(name: String) = paths.firstOrNull { it == name || it.substringAfterLast('/') == name }
        for (f in failures) {
            val m = f.message
            SRC.find(m)?.let { match -> byName(match.groupValues[1])?.let { out += it } }
            if (m.contains("404:") || m.contains("diblokir")) out += "index.html"
            val check = plan.checks.filter { m.startsWith(it.label + ":") }.maxByOrNull { it.label.length }
            if (check != null) {
                if (check.kind == Check.NONEMPTY) {
                    spec.dom.firstOrNull { "#${it.id}" == check.selector }?.by?.takeIf { it.isNotBlank() }?.let { out += it }
                } else if (check.kind == Check.CALL) {
                    spec.files.firstOrNull { sf -> sf.exports.any { it.substringBefore('(').trim() == check.fn } }?.let { out += it.path }
                } else out += "index.html"
            }
        }
        if (out.isEmpty() && "index.html" in paths) out += "index.html"
        return out.filter { it in paths }.take(MAX_FILES)
    }
}
