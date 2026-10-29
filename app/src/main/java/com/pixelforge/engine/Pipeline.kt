package com.pixelforge.engine

import com.pixelforge.agents.ToolProtocol

/** Pemanggil model. Implementasi di app membungkus LlmRouter; di tes berupa naskah tetap. */
interface Llm {
    suspend fun call(system: String, user: String): String
}

/** Penyimpan file proyek (workspace). */
interface FileStore {
    fun read(path: String): String?
    fun write(path: String, content: String)
    fun all(): Map<String, String>
    /** Hapus file; bawaan: tidak melakukan apa-apa. */
    fun delete(path: String) {}
}

class MemoryStore(initial: Map<String, String> = emptyMap()) : FileStore {
    val files = initial.toMutableMap()
    override fun read(path: String): String? = files[path]
    override fun write(path: String, content: String) { files[path] = content }
    override fun all(): Map<String, String> = files.toMap()
    override fun delete(path: String) { files.remove(path) }
}

data class RunReport(
    val passed: List<String>,
    val failed: Map<String, String>,
    val calls: Int,
    /** Alasan berhenti dini (anggaran), atau null bila semua tiket sudah dicoba. */
    val stopped: String?
)

/**
 * Menjalankan tiket satu per satu: tulis (1 panggilan) → cek (kode) → perbaiki (maks [maxRepairs]) → simpan.
 * Tanpa riwayat percakapan; tiket yang sudah lulus menurut log dilewati, jadi run bisa dilanjutkan.
 */
class TicketRunner(
    private val llm: Llm,
    private val store: FileStore,
    private val log: EventLog,
    private val maxCalls: Int = 30,
    private val maxRepairs: Int = 2,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun run(spec: AppSpec, onProgress: suspend (String) -> Unit = {}): RunReport {
        val tickets = TicketPlanner.plan(spec)
        val specPaths = spec.files.map { it.path }
        val state = RunState.from(log.all())
        var calls = state.calls
        val failedFiles = mutableSetOf<String>()
        val failed = linkedMapOf<String, String>()
        val passed = state.passed.toMutableList()

        for (t in tickets) {
            if (t.id in state.passed) { onProgress("${t.id} ${t.file}: sudah lulus, dilewati"); continue }
            val blocker = t.uses.firstOrNull { it in failedFiles }
            if (blocker != null) {
                val why = "dilewati karena $blocker gagal"
                failed[t.id] = why; failedFiles += t.file
                log.append(Event(Event.TICKET_SKIPPED, t.id, why, calls, clock()))
                onProgress("${t.id} ${t.file}: $why")
                continue
            }
            if (calls >= maxCalls) {
                log.append(Event(Event.BUDGET_STOPPED, t.id, "anggaran $maxCalls panggilan habis", calls, clock()))
                return RunReport(passed, failed, calls, "Anggaran $maxCalls panggilan habis sebelum ${t.id}.")
            }
            log.append(Event(Event.TICKET_STARTED, t.id, t.file, calls, clock()))
            onProgress("${t.id} ${t.file}: menulis")

            var problems: List<String>? = null
            var candidate: String? = null
            var ok = false
            var lastReason = ""
            var attempt = 0
            while (attempt <= maxRepairs) {
                if (calls >= maxCalls) { lastReason = "anggaran habis saat memperbaiki"; break }
                val outlines = t.uses.mapNotNull { u -> store.read(u)?.let { u to Outline.of(u, it) } }.toMap()
                val user = Packet.user(t, spec.lang, candidate ?: store.read(t.file), outlines, problems)
                val reply = try {
                    llm.call(Packet.SYSTEM, user)
                } catch (e: Exception) {
                    log.append(Event(Event.PAUSED, t.id, e.message?.take(200), calls, clock()))
                    throw e
                }
                calls++
                val outcome = interpret(t, reply, candidate ?: store.read(t.file))
                if (outcome.content == null) {
                    problems = outcome.problems
                } else {
                    candidate = outcome.content
                    val merged = store.all() + (t.file to outcome.content)
                    val found = outcome.problems + Checks.all(t, outcome.content, merged, specPaths)
                    if (found.isEmpty()) {
                        store.write(t.file, outcome.content)
                        ok = true
                        break
                    }
                    problems = found
                }
                lastReason = problems?.firstOrNull() ?: "gagal"
                attempt++
                if (attempt <= maxRepairs) onProgress("${t.id} ${t.file}: perbaikan $attempt/$maxRepairs")
            }
            if (ok) {
                passed += t.id
                log.append(Event(Event.TICKET_PASSED, t.id, t.file, calls, clock()))
                onProgress("${t.id} ${t.file}: lulus")
            } else {
                failed[t.id] = lastReason; failedFiles += t.file
                log.append(Event(Event.TICKET_FAILED, t.id, lastReason, calls, clock()))
                onProgress("${t.id} ${t.file}: gagal ($lastReason)")
            }
        }
        return RunReport(passed, failed, calls, null)
    }

    private class Outcome(val content: String?, val problems: List<String>)

    /** Ubah balasan model menjadi isi file kandidat, atau daftar masalah bila tidak bisa. */
    private fun interpret(t: Ticket, reply: String, existing: String?): Outcome {
        val calls = ToolProtocol.parse(reply)
        val problems = mutableListOf<String>()
        var content: String? = null
        val written = mutableListOf<String>()
        for (c in calls) {
            when (c) {
                is ToolProtocol.Call.Write -> {
                    written += c.path
                    if (c.path == t.file) {
                        if (!c.complete) problems += "Blok FILE terpotong (tidak ada END FILE); kirim ulang lebih pendek."
                        else content = c.content
                    }
                }
                is ToolProtocol.Call.Edit -> {
                    written += c.path
                    if (c.path == t.file) {
                        val base = content ?: existing
                        if (!c.complete) problems += "Blok EDIT tidak lengkap."
                        else if (base == null) problems += "EDIT untuk file yang belum ada; gunakan blok FILE."
                        else when (val r = ToolProtocol.applyEdit(base, c.search, c.replace)) {
                            is ToolProtocol.EditOutcome.Ok -> content = r.text
                            is ToolProtocol.EditOutcome.Fail -> problems += "EDIT gagal: ${r.reason}."
                        }
                    }
                }
                else -> {}
            }
        }
        problems += Checks.fence(t, written)
        if (content == null && problems.isEmpty()) problems += "Balasan tidak berisi blok FILE atau EDIT untuk ${t.file}."
        return Outcome(content, problems)
    }
}
