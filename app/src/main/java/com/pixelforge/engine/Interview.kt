package com.pixelforge.engine

/** Pertanyaan wawancara beserta jawaban yang direkomendasikan (pengguna cukup menerima atau mengganti). */
data class Question(val text: String, val recommended: String)

sealed class InterviewStep {
    data class Ask(val questions: List<Question>) : InterviewStep()
    data class Ready(val summary: String, val assumptions: List<String>) : InterviewStep()
}

/**
 * Wawancara berbasis skill `grilling` (mattpocock/skills, MIT): hanya tanya yang tidak bisa diputuskan sendiri,
 * tiap pertanyaan membawa jawaban rekomendasi, selesai bila tidak ada pertanyaan tersisa, dan tidak membangun apa pun
 * sebelum pengguna menyetujui. Dibatasi [MAX_ROUNDS] putaran supaya hemat jatah.
 */
class Interview(
    private val idea: String,
    private val llm: Llm,
    initialAnswered: List<Pair<String, String>> = emptyList(),
    initialRounds: Int = 0,
    initialPending: List<Question> = emptyList()
) {

    companion object {
        const val MAX_ROUNDS = 3
        const val MAX_QUESTIONS = 3

        const val SYSTEM =
            "You interview a user to turn a short app idea into a clear brief. You do NOT build anything.\n" +
                "Rules:\n" +
                "- Ask only what you cannot decide yourself or look up. Prefer sensible defaults.\n" +
                "- Each question must come with your recommended answer so the user can just accept it.\n" +
                "- At most $MAX_QUESTIONS questions per round. Short, concrete, one topic each.\n" +
                "- The app is a plain HTML/CSS/JavaScript web page or PWA. Do not ask about frameworks or hosting.\n" +
                "- Write questions and recommended answers in Indonesian (the user's language).\n" +
                "- Finish as soon as nothing important is open.\n" +
                "Reply in EXACTLY one of these formats, nothing else:\n" +
                "STATUS: ASK\n" +
                "Q1: <question> || REC: <your recommended answer>\n" +
                "Q2: ...\n" +
                "or\n" +
                "STATUS: READY\n" +
                "SUMMARY: <the idea in 2-4 sentences, concrete>\n" +
                "ASSUMPTION: <one assumption you made>   (repeat the line for each assumption)\n"

        private val QLINE = Regex("""^\s*Q\d*\s*[:.)]\s*(.+?)\s*\|\|\s*REC\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

        /** Urai balasan model. Tidak terbaca → null (pemanggil memakai ide apa adanya). */
        fun parse(reply: String): InterviewStep? {
            val lines = reply.lines()
            val status = lines.firstOrNull { it.trim().startsWith("STATUS", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.uppercase() ?: return null
            if (status.startsWith("ASK")) {
                val qs = lines.mapNotNull { l -> QLINE.find(l)?.let { Question(it.groupValues[1], it.groupValues[2]) } }.take(MAX_QUESTIONS)
                return if (qs.isEmpty()) null else InterviewStep.Ask(qs)
            }
            if (status.startsWith("READY")) {
                val summary = lines.firstOrNull { it.trim().startsWith("SUMMARY", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()
                val assumptions = lines.filter { it.trim().startsWith("ASSUMPTION", ignoreCase = true) }
                    .map { it.substringAfter(':').trim() }.filter { it.isNotBlank() }
                return InterviewStep.Ready(summary, assumptions)
            }
            return null
        }
    }

    private val answered = initialAnswered.toMutableList()
    private var pending: List<Question> = initialPending
    var rounds = initialRounds
        private set
    var calls = 0
        private set

    private fun userPrompt(lastRound: Boolean): String {
        val sb = StringBuilder("IDEA: ").append(idea.trim()).append('\n')
        if (answered.isNotEmpty()) {
            sb.append("ANSWERED SO FAR:\n")
            answered.forEach { (q, a) -> sb.append("- ").append(q).append(" => ").append(a).append('\n') }
        }
        sb.append("ROUND ").append(rounds + 1).append(" OF ").append(MAX_ROUNDS).append('\n')
        if (lastRound) sb.append("This is the last round: reply STATUS: READY and decide anything still open yourself, listing it as ASSUMPTION lines.\n")
        return sb.toString()
    }

    fun answeredPairs(): List<Pair<String, String>> = answered.toList()
    fun pendingQuestions(): List<Question> = pending

    /** Putaran berikutnya: satu panggilan. Setelah putaran terakhir selalu Ready. */
    suspend fun next(): InterviewStep {
        val last = rounds + 1 >= MAX_ROUNDS
        val reply = llm.call(SYSTEM, userPrompt(last))
        calls++
        rounds++
        val step = parse(reply) ?: InterviewStep.Ready(idea.trim(), listOf("Wawancara tidak terbaca; memakai ide apa adanya."))
        if (step is InterviewStep.Ask) {
            if (last) return InterviewStep.Ready(idea.trim(), listOf("Batas putaran wawancara tercapai; sisanya diputuskan agent."))
            pending = step.questions
        }
        return step
    }

    /** Jawaban pengguna sesuai urutan pertanyaan terakhir; kosong berarti menerima rekomendasi. */
    fun answer(answers: List<String>) {
        pending.forEachIndexed { i, q ->
            val a = answers.getOrNull(i)?.trim().orEmpty().ifEmpty { q.recommended }
            answered += q.text to a
        }
        pending = emptyList()
    }

    /** Ringkasan untuk blueprint, disusun kode (bukan model) dan dibatasi ukurannya. */
    fun brief(ready: InterviewStep.Ready): String {
        val sb = StringBuilder("IDEA: ").append(idea.trim()).append('\n')
        if (ready.summary.isNotBlank()) sb.append("SUMMARY: ").append(ready.summary).append('\n')
        if (answered.isNotEmpty()) {
            sb.append("DECISIONS:\n")
            answered.forEach { (q, a) -> sb.append("- ").append(q).append(" => ").append(a).append('\n') }
        }
        if (ready.assumptions.isNotEmpty()) {
            sb.append("ASSUMPTIONS:\n")
            ready.assumptions.forEach { sb.append("- ").append(it).append('\n') }
        }
        return sb.toString().take(2000)
    }
}
