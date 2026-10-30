package com.pixelforge.engine

/** Satu pesan untuk ditampilkan di chat. [who] = label tahap (WAWANCARA, BLUEPRINT, TIKET, SYSTEM). */
data class Say(val who: String, val text: String)

data class QA(val q: String = "", val a: String = "")

object Stage {
    const val IDLE = "IDLE"
    const val INTERVIEW = "INTERVIEW"
    const val REVIEW = "REVIEW"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
}

/** Keadaan alur yang bisa disimpan (JSON) dan dipulihkan setelah aplikasi ditutup. */
data class FlowState(
    val stage: String = Stage.IDLE,
    val idea: String = "",
    val answered: List<QA> = emptyList(),
    val pending: List<QA> = emptyList(),
    val rounds: Int = 0,
    val brief: String = "",
    val specJson: String = "",
    val planJson: String = "",
    val calls: Int = 0,
    val tokens: Int = 0
)

/**
 * Alur chat-first: ide → wawancara → blueprint (persetujuan pengguna) → tiket → selesai.
 * Semua keputusan alur ada di kode; model hanya dipanggil di dalam tahap. Tidak ada riwayat percakapan yang dikirim ulang.
 */
class EngineFlow(
    llmRaw: Llm,
    private val store: FileStore,
    private val log: EventLog,
    initial: FlowState = FlowState(),
    private val onState: (FlowState) -> Unit = {},
    private val maxCalls: Int = 30,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sandbox: Sandbox? = null
) {
    private val llm = CountingLlm(llmRaw)

    var state: FlowState = initial
        private set

    private fun set(s: FlowState) { state = s; onState(s) }

    companion object {
        const val MAX_DEBUG_ROUNDS = 2

        private val APPROVE = setOf("ya", "y", "ok", "oke", "setuju", "lanjut", "lanjutkan", "mulai", "gas", "siap", "betul", "sesuai", "yes")

        fun isApproval(text: String): Boolean =
            text.trim().lowercase().trim('.', '!', ' ', ',') in APPROVE

        /** Pintu murah tanpa LLM: sapaan atau kalimat terlalu pendek bukan ide aplikasi. */
        fun looksLikeIdea(text: String): Boolean =
            text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 3

        fun estimate(spec: AppSpec): String {
            val n = spec.files.size
            return "sekitar $n sampai ${n + n / 2 + 1} panggilan"
        }

        fun describe(spec: AppSpec, maxCalls: Int, plan: TestPlan? = null): String {
            val sb = StringBuilder()
            sb.append("**").append(spec.name).append("** (").append(spec.kind).append(")\n\nFitur:\n")
            spec.features.forEach { f ->
                sb.append("- ").append(f.id).append(" ").append(f.title)
                if (f.accept.isNotEmpty()) sb.append(" — lulus bila: ").append(f.accept.joinToString("; "))
                sb.append('\n')
            }
            sb.append("\nFile (").append(spec.files.size).append("): ").append(spec.files.joinToString(", ") { it.path }).append('\n')
            val cards = spec.files.flatMap { it.cards }.distinct().mapNotNull { Cards.get(it) }
            if (cards.isNotEmpty()) {
                sb.append("\nKartu fakta:\n")
                cards.forEach { sb.append("- ").append(it.title).append(if (it.verified) " (terverifikasi)" else " (BELUM terverifikasi; periksa sebelum dipercaya)").append('\n') }
            }
            if (spec.decisions.isNotEmpty()) {
                sb.append("\nKeputusan desain:\n"); spec.decisions.forEach { sb.append("- ").append(it).append('\n') }
            }
            if (plan != null && plan.checks.isNotEmpty()) {
                val v = plan.checks.count { it.verified }
                sb.append("\nTes otomatis: ").append(plan.checks.size).append(" (").append(v).append(" dengan nilai acuan terverifikasi). Setelah disetujui, tes dan blueprint tidak diubah agent.\n")
            }
            sb.append("\nAnggaran: ").append(estimate(spec)).append(" (batas $maxCalls).\n")
            sb.append("Ketik **setuju** untuk mulai, atau tulis revisi.")
            return sb.toString()
        }
    }

    suspend fun handle(text: String, onProgress: suspend (String) -> Unit = {}): List<Say> {
        val calls0 = llm.calls
        val tokens0 = llm.tokens
        try {
            val says = dispatch(text, onProgress).toMutableList()
            if (state.stage == Stage.DONE && says.lastOrNull()?.who == "TIKET") {
                val c = state.calls + (llm.calls - calls0)
                val k = state.tokens + (llm.tokens - tokens0)
                says += Say("SYSTEM", "Ukuran pekerjaan: $c panggilan LLM, sekitar $k token (perkiraan).")
            }
            return says
        } finally {
            val dc = llm.calls - calls0
            if (dc > 0) set(state.copy(calls = state.calls + dc, tokens = state.tokens + (llm.tokens - tokens0)))
        }
    }

    private suspend fun dispatch(text: String, onProgress: suspend (String) -> Unit): List<Say> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        return when (state.stage) {
            Stage.INTERVIEW -> answerInterview(t)
            Stage.REVIEW -> if (isApproval(t)) run(onProgress) else revise(t)
            Stage.RUNNING ->
                if (isApproval(t)) run(onProgress)
                else listOf(Say("SYSTEM", "Pembuatan berhenti di tengah jalan. Ketik **lanjut** untuk melanjutkan."))
            else -> if (state.stage == Stage.IDLE && state.brief.isNotBlank() && (isApproval(t) || t.lowercase() == "ulang")) makeBlueprint(state.brief)
            else if (!looksLikeIdea(t)) listOf(
                Say("SYSTEM", "Ceritakan aplikasi yang ingin dibuat dalam satu kalimat, misalnya: \"buat web waktu sholat dan arah kiblat\". Belum ada panggilan LLM yang terpakai.")
            ) else startIdea(t)
        }
    }

    private fun interview(): Interview = Interview(
        state.idea, llm,
        state.answered.map { it.q to it.a }, state.rounds, state.pending.map { Question(it.q, it.a) }
    )

    private suspend fun startIdea(idea: String): List<Say> {
        set(FlowState(stage = Stage.INTERVIEW, idea = idea))
        return advance(interview())
    }

    private suspend fun answerInterview(text: String): List<Say> {
        val iv = interview()
        val qs = iv.pendingQuestions()
        val answers = if (isApproval(text)) emptyList()
        else text.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() }
        iv.answer(answers.map { a -> a.replace(Regex("^\\d+[.):]\\s*"), "") })
        // Satu baris untuk beberapa pertanyaan: baris itu menjawab pertanyaan pertama, sisanya memakai saran.
        if (qs.isEmpty()) return advance(iv)
        return advance(iv)
    }

    private suspend fun advance(iv: Interview): List<Say> {
        return when (val step = iv.next()) {
            is InterviewStep.Ask -> {
                set(
                    state.copy(
                        stage = Stage.INTERVIEW,
                        answered = iv.answeredPairs().map { QA(it.first, it.second) },
                        pending = iv.pendingQuestions().map { QA(it.text, it.recommended) },
                        rounds = iv.rounds
                    )
                )
                val sb = StringBuilder("Beberapa hal perlu dipastikan. Balas satu jawaban per baris, atau ketik **ya** untuk menerima semua saran:\n")
                step.questions.forEachIndexed { i, q ->
                    sb.append('\n').append(i + 1).append(". ").append(q.text).append("\n   saran: ").append(q.recommended)
                }
                listOf(Say("WAWANCARA", sb.toString()))
            }
            is InterviewStep.Ready -> {
                set(
                    state.copy(
                        answered = iv.answeredPairs().map { QA(it.first, it.second) },
                        pending = emptyList(), rounds = iv.rounds
                    )
                )
                makeBlueprint(iv.brief(step))
            }
        }
    }

    private suspend fun makeBlueprint(brief: String): List<Say> {
        return when (val r = Blueprint.run(llm, brief)) {
            is BlueprintResult.Ok -> if (!Relevance.matches(state.idea, r.spec)) {
                set(state.copy(stage = Stage.IDLE, brief = brief))
                listOf(
                    Say(
                        "SYSTEM",
                        "Blueprint tidak sesuai ide (\"${r.spec.name}\" tidak menyinggung isi ide). Tidak ada file yang ditulis. " +
                            "Kirim ide lagi dengan kata kunci yang jelas."
                    )
                )
            } else {
                set(state.copy(stage = Stage.REVIEW, brief = brief, specJson = SpecJson.write(r.spec), planJson = r.plan?.let { PlanParser.write(it) } ?: "", pending = emptyList()))
                listOf(Say("BLUEPRINT", describe(r.spec, maxCalls, r.plan)))
            }
            is BlueprintResult.Failed -> {
                set(state.copy(stage = Stage.IDLE, brief = brief))
                listOf(
                    Say(
                        "SYSTEM",
                        "Blueprint belum lolos pemeriksaan:\n" + r.problems.take(5).joinToString("\n") { "- $it" } +
                            "\nKetik **ulang** untuk mencoba lagi (jawaban wawancara tidak hilang), atau kirim ide yang lebih sederhana."
                    )
                )
            }
        }
    }

    private suspend fun revise(text: String): List<Say> = makeBlueprint(state.brief + "\nREVISION: " + text.take(500))

    private suspend fun run(onProgress: suspend (String) -> Unit): List<Say> {
        val spec = (SpecParser.parse(state.specJson) as? SpecResult.Ok)?.spec
            ?: return listOf(Say("SYSTEM", "Blueprint tersimpan rusak; kirim ide lagi."))
        if (state.stage != Stage.RUNNING) {
            log.append(Event(Event.RUN_STARTED, null, spec.name, 0, clock()))
            log.append(Event(Event.BLUEPRINT_APPROVED, null, spec.name, 0, clock()))
            // Mulai bersih: file sisa dari percobaan lama yang tidak ada di blueprint dihapus.
            val keep = spec.files.map { it.path }.toSet()
            store.all().keys.filter { it !in keep }.forEach { store.delete(it) }
            set(state.copy(stage = Stage.RUNNING))
        }
        val runner = TicketRunner(llm, store, log, maxCalls, clock = clock)
        val report = runner.run(spec, onProgress)
        val out = mutableListOf<Say>()
        return when {
            report.stopped != null -> {
                out += Say("SYSTEM", report.stopped + " Progres tersimpan; ketik **lanjut** untuk melanjutkan.")
                out
            }
            report.failed.isNotEmpty() -> {
                out += Say(
                    "TIKET",
                    "Sebagian tiket gagal:\n" + report.failed.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } +
                        "\nKetik **lanjut** untuk mencoba lagi yang gagal."
                )
                out
            }
            else -> verifyAndFinish(spec, runner, report.calls, report.passed.size, onProgress)
        }
    }

    /** Uji di sandbox; hanya bug kode yang diserahkan ke Debugger (maks [MAX_DEBUG_ROUNDS] putaran). */
    private suspend fun verifyAndFinish(
        spec: AppSpec, runner: TicketRunner, startCalls: Int, fileCount: Int, onProgress: suspend (String) -> Unit
    ): List<Say> {
        val plan = (PlanParser.parse(state.planJson) as? PlanResult.Ok)?.plan ?: TestPlan(emptyList())
        var calls = startCalls
        var warnings: List<Failure> = emptyList()
        var tested = false
        if (sandbox != null) {
            var round = 0
            while (true) {
                onProgress("menguji halaman di sandbox")
                val report = try {
                    sandbox.run(plan)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SandboxReport(false, emptyList(), emptyList(), "sandbox gagal dijalankan: ${e.message}")
                }
                val failures = Triage.classify(report, plan)
                if (failures.isEmpty()) {
                    log.append(Event(Event.SANDBOX_PASSED, null, "${report.results.size} pemeriksaan", calls, clock()))
                    tested = true
                    break
                }
                log.append(Event(Event.SANDBOX_FAILED, null, failures.first().message.take(200), calls, clock()))
                val fixable = Triage.forDebugger(failures)
                if (fixable.isEmpty()) { warnings = failures; break }
                if (round >= MAX_DEBUG_ROUNDS) return stuck(failures, round)
                round++
                var fixedAny = false
                for (f in Implicated.files(fixable, plan, spec)) {
                    onProgress("Debugger memperbaiki $f ($round/$MAX_DEBUG_ROUNDS)")
                    val r = runner.repair(spec, f, fixable.map { it.message }, calls)
                    calls = r.calls
                    if (r.ok) fixedAny = true
                }
                if (!fixedAny) return stuck(failures, round)
            }
        }
        set(state.copy(stage = Stage.DONE))
        store.write("PROJECT.md", ProjectNotes.render(spec))
        val sb = StringBuilder("Selesai: $fileCount file dibuat dengan $calls panggilan.")
        if (tested) sb.append(" Lolos uji otomatis di sandbox.")
        if (warnings.isNotEmpty()) {
            sb.append("\n\nUji belum sepenuhnya meyakinkan; bukan bug kode, jadi butuh keputusanmu:\n").append(Triage.render(warnings))
        }
        sb.append("\nFile tersimpan di folder project (tab Files).")
        return listOf(Say("TIKET", sb.toString()))
    }

    private fun stuck(failures: List<Failure>, rounds: Int): List<Say> = listOf(
        Say(
            "TIKET",
            "Kode sudah dibuat tapi belum lolos uji (Debugger sudah mencoba $rounds kali):\n" + Triage.render(failures.take(6)) +
                "\nPenggunaan kuota dihentikan di sini. Ketik **lanjut** untuk mencoba lagi, atau tulis revisi."
        )
    )
}
