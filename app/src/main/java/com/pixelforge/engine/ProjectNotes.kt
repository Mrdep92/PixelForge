package com.pixelforge.engine

/** Memori proyek di repo: ditulis kode dari spec (tanpa LLM), supaya sesi berikutnya tahu isi proyek tanpa membaca semua file. */
object ProjectNotes {
    fun render(spec: AppSpec): String = buildString {
        append("# ").append(spec.name).append("\n\n")
        append("Jenis: ").append(spec.kind).append(" | Bahasa: ").append(spec.lang).append("\n\n")
        append("## Fitur\n")
        spec.features.forEach { f ->
            append("- ").append(f.id).append(" ").append(f.title).append('\n')
            f.accept.forEach { append("  - lulus bila: ").append(it).append('\n') }
        }
        append("\n## File\n")
        spec.files.forEach { f ->
            append("- ").append(f.path)
            if (f.exports.isNotEmpty()) append(" — ").append(f.exports.joinToString(", "))
            append('\n')
        }
        if (spec.decisions.isNotEmpty()) {
            append("\n## Keputusan\n")
            spec.decisions.forEach { append("- ").append(it).append('\n') }
        }
    }
}
