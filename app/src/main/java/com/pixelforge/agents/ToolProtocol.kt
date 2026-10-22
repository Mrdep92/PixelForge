package com.pixelforge.agents

/**
 * Protokol tool berbasis teks (bukan function-calling API) supaya jalan di model gratis apa pun.
 * Model menulis blok seperti `=== READ: a.kt ===`; aplikasi mengeksekusinya dan membalas dengan hasilnya.
 * Kode di file ini murni Kotlin (tanpa Android) sehingga bisa diuji unit.
 */
object ToolProtocol {

    sealed class Call {
        data class Read(val path: String, val start: Int?, val end: Int?) : Call()
        data class ListDir(val path: String) : Call()
        data class Grep(val pattern: String) : Call()
        data class Write(val path: String, val content: String, val complete: Boolean) : Call()
        data class Edit(val path: String, val search: String, val replace: String, val complete: Boolean) : Call()
        data class Done(val summary: String) : Call()
    }

    sealed class EditOutcome {
        data class Ok(val text: String) : EditOutcome()
        data class Fail(val reason: String) : EditOutcome()
    }

    const val PROMPT =
        "You work inside a project workspace using text tools. To act, write tool blocks exactly as below " +
            "(several per reply are fine; they run in order, then you receive the results and continue):\n\n" +
            "=== LIST: . ===                      list files (give a folder path to filter)\n" +
            "=== READ: path/File.kt ===           read a file (optional line range: === READ: path/File.kt 10-80 ===)\n" +
            "=== GREP: pattern ===                search text in all files\n" +
            "=== FILE: path/New.kt ===            create a NEW file (or fully rewrite a small one)\n" +
            "<entire file content>\n" +
            "=== END FILE ===\n" +
            "=== EDIT: path/File.kt ===           change part of an existing file\n" +
            "<<<<<<< SEARCH\n" +
            "exact existing lines (copy them exactly, with enough context to be unique)\n" +
            "=======\n" +
            "replacement lines\n" +
            ">>>>>>> REPLACE\n" +
            "=== END EDIT ===\n" +
            "=== DONE ===                         when finished: a short summary for the user\n" +
            "summary text\n" +
            "=== END DONE ===\n\n" +
            "Rules:\n" +
            "- READ a file before you EDIT it. Prefer EDIT over rewriting whole files.\n" +
            "- Keep every reply small (a few files or edits at a time). Long replies get cut off, and incomplete blocks are rejected.\n" +
            "- No markdown code fences around tool blocks. Never invent contents of files you have not read.\n" +
            "- If a tool result says ERROR, fix the problem and try again.\n" +
            "- Never create or edit gradlew, gradlew.bat or gradle/wrapper/*: the app provides them. Existing CI workflows are read-only.\n" +
            "- If the project can be built or tested and the workspace has no file under .github/workflows/, " +
            "add a GitHub Actions workflow that runs on push (all branches) and workflow_dispatch.\n" +
            "- Finish with a DONE block.\n"

    private val SINGLE = Regex("""^\s*={2,}\s*(READ|LIST|GREP)\s*:\s*(.+?)\s*=*\s*$""", RegexOption.IGNORE_CASE)
    private val BLOCK = Regex("""^\s*={2,}\s*(FILE|EDIT)\s*:\s*(.+?)\s*=*\s*$""", RegexOption.IGNORE_CASE)
    private val DONE_START = Regex("""^\s*={2,}\s*DONE\s*={2,}\s*$""", RegexOption.IGNORE_CASE)
    private val END = Regex("""^\s*={2,}\s*END(?:\s+(?:FILE|EDIT|DONE))?\s*={2,}\s*$""", RegexOption.IGNORE_CASE)
    private val SEARCH_RE = Regex("""^\s*<{5,}\s*SEARCH\s*$""")
    private val REPLACE_RE = Regex("""^\s*>{5,}\s*REPLACE\s*$""")
    private val RANGE_RE = Regex("""^(.*?)\s+(\d+)\s*-\s*(\d+)$""")

    /** Path relatif yang aman; null kalau tidak valid (.., kosong, terlalu panjang). */
    fun safePath(raw: String): String? {
        val p = raw.trim().trim('`', '"', '\'').replace('\\', '/').removePrefix("./").trimStart('/')
        if (p.isBlank() || p.length > 200) return null
        if (p.split('/').any { it == ".." || it.isEmpty() }) return null
        return p
    }

    fun parse(text: String): List<Call> {
        val out = mutableListOf<Call>()
        var mode: String? = null
        var path = ""
        val buf = StringBuilder()

        fun finish(complete: Boolean) {
            when (mode) {
                "FILE" -> out += Call.Write(path, stripFence(buf.toString()), complete)
                "EDIT" -> out += parseEdit(path, buf.toString(), complete)
                "DONE" -> out += Call.Done(buf.toString().trim())
            }
            buf.clear()
            mode = null
        }

        for (line in text.lines()) {
            if (mode != null && END.matches(line)) {
                finish(true)
                continue
            }
            val single = SINGLE.matchEntire(line)
            val block = BLOCK.matchEntire(line)
            val isDone = DONE_START.matches(line)
            val isHeader = single != null || block != null || isDone
            if (mode != null && !isHeader) {
                buf.append(line).append('\n')
                continue
            }
            if (mode != null) finish(false) // blok sebelumnya terpotong (header baru muncul sebelum END)

            if (single != null) {
                val arg = single.groupValues[2].trim()
                when (single.groupValues[1].uppercase()) {
                    "READ" -> {
                        val r = RANGE_RE.matchEntire(arg)
                        if (r != null) {
                            out += Call.Read(safePath(r.groupValues[1]) ?: "", r.groupValues[2].toIntOrNull(), r.groupValues[3].toIntOrNull())
                        } else {
                            out += Call.Read(safePath(arg) ?: "", null, null)
                        }
                    }
                    "LIST" -> out += Call.ListDir(arg)
                    else -> out += Call.Grep(arg)
                }
            } else if (block != null) {
                mode = block.groupValues[1].uppercase()
                path = safePath(block.groupValues[2]) ?: ""
            } else if (isDone) {
                mode = "DONE"
            }
        }
        if (mode != null) finish(false)
        return out
    }

    private fun parseEdit(path: String, body: String, complete: Boolean): Call.Edit {
        val lines = body.lines()
        val s = lines.indexOfFirst { SEARCH_RE.matches(it) }
        if (s < 0) return Call.Edit(path, "", "", false)
        val d = lines.withIndex().firstOrNull { it.index > s && isDivider(it.value) }?.index ?: -1
        if (d < 0) return Call.Edit(path, "", "", false)
        val e = lines.withIndex().firstOrNull { it.index > d && REPLACE_RE.matches(it.value) }?.index ?: -1
        val search = lines.subList(s + 1, d).joinToString("\n")
        if (e < 0) return Call.Edit(path, search, "", false)
        val replace = lines.subList(d + 1, e).joinToString("\n")
        return Call.Edit(path, search, replace, complete)
    }

    private fun isDivider(line: String): Boolean {
        val t = line.trim()
        return t.length >= 5 && t.all { it == '=' }
    }

    private fun stripFence(s: String): String {
        val t = s.trim('\n', '\r')
        val lines = t.lines()
        if (lines.size >= 2 && lines.first().trimStart().startsWith("```") && lines.last().trim() == "```") {
            return lines.drop(1).dropLast(1).joinToString("\n")
        }
        return t
    }

    /** Terapkan cari-ganti. Teks SEARCH harus unik; toleran terhadap beda spasi di akhir baris. */
    fun applyEdit(original: String, search: String, replace: String): EditOutcome {
        if (search.isBlank()) return EditOutcome.Fail("bagian SEARCH kosong")
        val idx = original.indexOf(search)
        if (idx >= 0) {
            if (original.indexOf(search, idx + 1) >= 0) {
                return EditOutcome.Fail("teks SEARCH muncul lebih dari sekali; tambahkan baris di sekitarnya agar unik")
            }
            return EditOutcome.Ok(original.substring(0, idx) + replace + original.substring(idx + search.length))
        }
        val oLines = original.split("\n")
        val sLines = search.trim('\n').split("\n")
        val n = sLines.size
        val hits = mutableListOf<Int>()
        for (i in 0..(oLines.size - n)) {
            var ok = true
            for (k in 0 until n) {
                if (oLines[i + k].trimEnd() != sLines[k].trimEnd()) {
                    ok = false
                    break
                }
            }
            if (ok) hits += i
        }
        if (hits.isEmpty()) {
            return EditOutcome.Fail("teks SEARCH tidak ditemukan persis di file; READ file itu lagi dan salin barisnya persis")
        }
        if (hits.size > 1) return EditOutcome.Fail("teks SEARCH cocok di beberapa tempat; tambahkan baris di sekitarnya agar unik")
        val i = hits[0]
        val repl = if (replace.isEmpty()) emptyList() else replace.split("\n")
        return EditOutcome.Ok((oLines.subList(0, i) + repl + oLines.subList(i + n, oLines.size)).joinToString("\n"))
    }
}
