package com.pixelforge.engine

/** Paket konteks satu panggilan: dirakit kode, tanpa riwayat percakapan, ukuran dibatasi. */
object Packet {
    const val MAX_CURRENT = 12_000
    const val MAX_USER = 24_000

    /** Awalan tetap: identik di setiap panggilan supaya penyedia dengan cache awalan bisa memakainya ulang. */
    const val SYSTEM =
        "You are a code writer inside a fixed pipeline. You receive ONE ticket and must produce exactly ONE file.\n" +
            "Output format, nothing else (no explanations, no markdown fences):\n" +
            "=== FILE: <path> ===\n" +
            "<entire file>\n" +
            "=== END FILE ===\n" +
            "For a small change to an existing file you may instead use:\n" +
            "=== EDIT: <path> ===\n" +
            "<<<<<<< SEARCH\n" +
            "exact existing lines\n" +
            "=======\n" +
            "replacement lines\n" +
            ">>>>>>> REPLACE\n" +
            "=== END EDIT ===\n" +
            "Rules:\n" +
            "- Write only the file named in the ticket.\n" +
            "- Other files are known only through INTERFACES. Never invent functions, files or element ids that are not listed there.\n" +
            "- Plain HTML, CSS and JavaScript. No external libraries or CDNs unless the ticket lists them.\n" +
            "- Use RELATIVE paths for every file reference (js/app.js, css/style.css), never a leading slash: the app is served from a sub-folder.\n" +
            "- If a fact, formula or value is not in FACTS, do not invent it: write a short TODO comment instead.\n" +
            "- The file must be complete and well-formed (all brackets and tags closed). Keep it small.\n"

    /**
     * @param current isi file saat ini atau kandidat sebelumnya (null = file baru)
     * @param depOutlines outline file yang dipakai tiket, per path
     * @param problems masalah dari pengecekan otomatis pada percobaan sebelumnya (null/kosong = percobaan pertama)
     */
    fun user(ticket: Ticket, lang: String, current: String?, depOutlines: Map<String, String>, problems: List<String>?): String {
        val sb = StringBuilder()
        sb.append("TICKET ").append(ticket.id).append(" - file: ").append(ticket.file).append('\n')
        sb.append("Language of visible text: ").append(lang).append('\n')
        sb.append("GOAL:\n").append(ticket.goal).append('\n')
        if (ticket.accept.isNotEmpty()) {
            sb.append("ACCEPT:\n"); ticket.accept.forEach { sb.append("- ").append(it).append('\n') }
        }
        if (ticket.exports.isNotEmpty()) {
            sb.append("THIS FILE MUST DEFINE:\n"); ticket.exports.forEach { sb.append("- ").append(it).append('\n') }
        }
        if (ticket.interfaces.isNotEmpty()) {
            sb.append("INTERFACES (other files you may use):\n")
            ticket.interfaces.forEach { sb.append("- ").append(it).append('\n') }
            depOutlines.forEach { (p, o) -> if (o.isNotBlank()) sb.append("OUTLINE ").append(p).append(":\n").append(o).append('\n') }
        }
        if (ticket.facts.isNotEmpty()) {
            sb.append("FACTS (the only facts you may rely on):\n"); ticket.facts.forEach { sb.append("- ").append(it).append('\n') }
        }
        if (current == null) sb.append("CURRENT FILE: (new file)\n")
        else {
            val shown = if (current.length > MAX_CURRENT) current.take(MAX_CURRENT) + "\n[... dipotong]" else current
            sb.append("CURRENT FILE (").append(ticket.file).append("):\n").append(shown).append('\n')
        }
        if (!problems.isNullOrEmpty()) {
            sb.append("PROBLEMS FOUND BY AUTOMATIC CHECKS (fix all of them, resend the whole file):\n")
            problems.take(8).forEach { sb.append("- ").append(it).append('\n') }
        }
        return if (sb.length > MAX_USER) sb.substring(0, MAX_USER) else sb.toString()
    }
}
