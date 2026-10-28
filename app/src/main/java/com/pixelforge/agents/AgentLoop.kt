package com.pixelforge.agents

/** Akses file untuk agent. Implementasi di app = Workspace; di tes = peta di memori. */
interface Executor {
    fun read(path: String): String?
    fun write(path: String, content: String)
    fun list(): List<String>
}

/**
 * Loop agent: model → blok tool → hasil → model … sampai DONE / jawaban akhir / batas putaran.
 * Error tool dikembalikan ke model sebagai teks supaya ia memperbaiki sendiri.
 */
class AgentLoop(
    private val chat: suspend (List<Msg>) -> String,
    private val exec: Executor,
    private val maxTurns: Int = 12
) {
    data class Msg(val role: String, val content: String)
    data class Result(val summary: String, val changed: List<String>, val turns: Int, val finished: Boolean)

    suspend fun run(system: String, task: String, onStep: suspend (String) -> Unit = {}): Result {
        val msgs = mutableListOf(Msg("system", system), Msg("user", task))
        val changed = linkedSetOf<String>()
        var nudged = false
        for (turn in 1..maxTurns) {
            val reply = chat(compact(msgs))
            msgs += Msg("assistant", reply)
            val calls = ToolProtocol.parse(reply)
            if (calls.isEmpty()) {
                // Model membalas tanpa perintah tool dan tanpa DONE: dorong satu kali, jangan anggap selesai.
                if (!nudged && turn < maxTurns) {
                    nudged = true
                    onStep("putaran $turn/$maxTurns — ⏭ dorongan lanjut")
                    msgs += Msg("user", NUDGE)
                    continue
                }
                return Result(reply.trim(), changed.toList(), turn, true)
            }

            val results = StringBuilder()
            val steps = mutableListOf<String>()
            var done: String? = null
            for (c in calls) {
                if (c is ToolProtocol.Call.Done) {
                    done = c.summary
                    steps += "✅ selesai"
                } else {
                    val r = execute(c, changed)
                    steps += r.first
                    results.append(r.second).append("\n\n")
                }
            }
            onStep("putaran $turn/$maxTurns — " + steps.joinToString(", "))
            // Model sering menulis READ/LIST/GREP dan DONE dalam satu balasan, padahal hasil bacaan belum ia lihat.
            // DONE seperti itu tidak boleh menutup tugas: kirim hasilnya dulu (kecuali sudah putaran terakhir).
            val inspecting = calls.any { it is ToolProtocol.Call.Read || it is ToolProtocol.Call.ListDir || it is ToolProtocol.Call.Grep }
            if (done != null && inspecting && changed.isEmpty() && turn < maxTurns) {
                msgs += Msg("user", "Hasil tool:\n\n" + results.toString().trim() +
                    "\n\nDONE kamu diabaikan karena dikirim bersama perintah baca, sebelum kamu melihat hasilnya. " +
                    "Lanjutkan dengan FILE/EDIT berdasarkan hasil di atas, lalu kirim DONE sendirian.")
                continue
            }
            if (done != null) {
                return Result(done.ifBlank { "Selesai." }, changed.toList(), turn, true)
            }
            var next = "Hasil tool:\n\n" + results.toString().trim()
            if (turn == maxTurns - 1) next += "\n\nPutaran berikutnya adalah yang terakhir: selesaikan dan kirim blok DONE."
            msgs += Msg("user", next)
        }
        return Result("Berhenti: batas $maxTurns putaran tercapai.", changed.toList(), maxTurns, false)
    }

    /** @return pasangan (label singkat untuk UI, hasil untuk model) */
    private fun execute(c: ToolProtocol.Call, changed: MutableSet<String>): Pair<String, String> = when (c) {
        is ToolProtocol.Call.Read -> Pair("📖 ${c.path}", doRead(c))
        is ToolProtocol.Call.ListDir -> Pair("📂 ${c.path}", doList(c.path))
        is ToolProtocol.Call.Grep -> Pair("🔎 ${c.pattern.take(30)}", doGrep(c.pattern))
        is ToolProtocol.Call.Write -> Pair("📝 ${c.path}", doWrite(c, changed))
        is ToolProtocol.Call.Edit -> Pair("✏️ ${c.path}", doEdit(c, changed))
        is ToolProtocol.Call.Done -> Pair("", "")
    }

    private fun doRead(c: ToolProtocol.Call.Read): String {
        if (c.path.isBlank()) return "ERROR: path tidak valid."
        val text = exec.read(c.path) ?: return "ERROR: file '${c.path}' tidak ada. Gunakan LIST untuk melihat file."
        val lines = text.split("\n")
        val s = (c.start ?: 1).coerceIn(1, maxOf(1, lines.size))
        val e = (c.end ?: lines.size).coerceIn(s, maxOf(s, lines.size))
        var body = lines.subList(s - 1, minOf(e, lines.size)).joinToString("\n")
        if (body.length > MAX_READ) {
            body = body.take(MAX_READ) + "\n[...dipotong; baca dengan rentang baris untuk sisanya]"
        }
        return "=== READ ${c.path} (baris $s-$e dari ${lines.size}) ===\n$body"
    }

    private fun doList(path: String): String {
        val p = path.trim().trim('/')
        val all = exec.list()
        val files = if (p.isEmpty() || p == ".") all else all.filter { it.startsWith("$p/") }
        if (files.isEmpty()) return "=== LIST $path ===\n(kosong)"
        val shown = files.take(200).joinToString("\n")
        return "=== LIST $path (${files.size} file) ===\n$shown" + if (files.size > 200) "\n[...dipotong]" else ""
    }

    private fun doGrep(pattern: String): String {
        val re = try {
            Regex(pattern)
        } catch (e: Exception) {
            Regex(Regex.escape(pattern))
        }
        val hits = mutableListOf<String>()
        outer@ for (f in exec.list()) {
            val text = exec.read(f) ?: continue
            if (text.length > 400_000) continue
            val lines = text.split("\n")
            for (i in lines.indices) {
                if (re.containsMatchIn(lines[i])) {
                    hits += "$f:${i + 1}: ${lines[i].trim().take(160)}"
                    if (hits.size >= 60) break@outer
                }
            }
        }
        return if (hits.isEmpty()) "=== GREP $pattern ===\n(tidak ada hasil)"
        else "=== GREP $pattern (${hits.size} hasil) ===\n" + hits.joinToString("\n")
    }

    private fun doWrite(c: ToolProtocol.Call.Write, changed: MutableSet<String>): String {
        if (c.path.isBlank()) return "ERROR: path tidak valid; file tidak ditulis."
        ProtectedFiles.violation(c.path, exec.read(c.path) != null)?.let { return "ERROR: $it" }
        if (!c.complete) {
            return "ERROR: blok FILE '${c.path}' terpotong (tidak ada END FILE); file TIDAK ditulis. " +
                "Kirim ulang lebih kecil, atau buat kerangka dulu lalu tambahkan bagiannya dengan EDIT."
        }
        exec.write(c.path, c.content)
        changed += c.path
        return "OK: ${c.path} ditulis (${c.content.split("\n").size} baris)."
    }

    private fun doEdit(c: ToolProtocol.Call.Edit, changed: MutableSet<String>): String {
        if (c.path.isBlank()) return "ERROR: path tidak valid; tidak ada yang diubah."
        ProtectedFiles.violation(c.path, exec.read(c.path) != null)?.let { return "ERROR: $it" }
        if (!c.complete) {
            return "ERROR: blok EDIT '${c.path}' tidak lengkap/terpotong (butuh SEARCH, =======, REPLACE, dan END EDIT); tidak ada yang diubah."
        }
        val original = exec.read(c.path)
            ?: return "ERROR: file '${c.path}' belum ada. Gunakan blok FILE untuk membuat file baru."
        return when (val r = ToolProtocol.applyEdit(original, c.search, c.replace)) {
            is ToolProtocol.EditOutcome.Ok -> {
                exec.write(c.path, r.text)
                changed += c.path
                "OK: ${c.path} diedit."
            }
            is ToolProtocol.EditOutcome.Fail -> "ERROR: edit '${c.path}' gagal: ${r.reason}."
        }
    }

    /** Hemat konteks: pesan lama yang panjang dipangkas (dua pesan awal dan empat terakhir dipertahankan). */
    private fun compact(msgs: List<Msg>): List<Msg> {
        val total = msgs.sumOf { it.content.length }
        if (total <= MAX_CTX) return msgs
        val keepFrom = msgs.size - 4
        return msgs.mapIndexed { i, m ->
            if (i >= 2 && i < keepFrom && m.content.length > 600) {
                m.copy(content = m.content.take(300) + "\n[...dipangkas untuk hemat konteks...]")
            } else m
        }
    }

    private companion object {
        const val NUDGE =
            "Your last reply had no tool block and no DONE block, so the task is not finished yet. " +
                "Continue with tool blocks (LIST, READ, GREP, FILE, EDIT), or send a DONE block with a short summary " +
                "if the work is truly complete."
        const val MAX_READ = 8000
        const val MAX_CTX = 48_000
    }
}
