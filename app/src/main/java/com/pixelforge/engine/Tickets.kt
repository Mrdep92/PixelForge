package com.pixelforge.engine

/** Satu pekerjaan kecil untuk model: satu file. Dibuat kode dari spec, bukan oleh model. */
data class Ticket(
    val id: String,
    val file: String,
    val uses: List<String>,
    val goal: String,
    val accept: List<String>,
    /** Tanda tangan file lain yang boleh dipakai (bukan isinya). */
    val interfaces: List<String>,
    val facts: List<String>,
    val exports: List<String>
)

object TicketPlanner {

    /** File dalam urutan pembuatan (yang dipakai lebih dulu); null bila ada siklus. */
    fun order(spec: AppSpec): List<FileSpec>? {
        val remaining = spec.files.toMutableList()
        val done = mutableSetOf<String>()
        val out = mutableListOf<FileSpec>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { f -> f.uses.all { it in done || spec.files.none { s -> s.path == it } } }
                ?: return null
            remaining.remove(next)
            done += next.path
            out += next
        }
        return out
    }

    fun plan(spec: AppSpec): List<Ticket> {
        val ordered = order(spec) ?: spec.files
        return ordered.mapIndexed { i, f ->
            val feats = spec.features.filter { it.id in f.features }
            val goal = feats.joinToString(" ") { "Fitur ${it.id} (${it.title})." }.take(600)
            val accept = feats.flatMap { it.accept }.take(12)
            val interfaces = f.uses.map { u ->
                val ex = spec.files.firstOrNull { it.path == u }?.exports.orEmpty()
                if (ex.isEmpty()) u else "$u: " + ex.joinToString("; ")
            }
            Ticket("T${i + 1}", f.path, f.uses, goal, accept, interfaces, spec.facts, f.exports)
        }
    }
}
