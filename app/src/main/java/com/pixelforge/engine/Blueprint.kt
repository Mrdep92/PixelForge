package com.pixelforge.engine

sealed class BlueprintResult {
    data class Ok(val spec: AppSpec, val calls: Int, val plan: TestPlan? = null) : BlueprintResult()
    data class Failed(val problems: List<String>, val calls: Int) : BlueprintResult()
}

/** Tahap blueprint: satu panggilan menghasilkan spec.json; kode memvalidasi; satu perbaikan bila perlu. */
object Blueprint {
    const val SYSTEM =
        "You design a small web app as a machine-readable spec. Reply with ONE JSON object and nothing else.\n" +
            "Schema:\n" +
            "{\"app\":{\"name\":\"...\",\"kind\":\"pwa\",\"lang\":\"id\"},\n" +
            " \"features\":[{\"id\":\"F1\",\"title\":\"...\",\"accept\":[\"observable criterion\", ...]}],\n" +
            " \"files\":[{\"path\":\"index.html\",\"features\":[\"F1\"],\"uses\":[\"js/app.js\"],\"exports\":[],\"cards\":[]},\n" +
            "           {\"path\":\"js/app.js\",\"features\":[\"F1\"],\"uses\":[],\"exports\":[\"functionName(arg1, arg2)\"],\"cards\":[\"prayer-times\"]}],\n" +
            " \"facts\":[], \"decisions\":[\"short design decision\"],\n" +
            " \"tests\":[{\"kind\":\"exists\",\"label\":\"title shown\",\"selector\":\"h1\"},\n" +
            "          {\"kind\":\"text\",\"label\":\"...\",\"selector\":\"#out\",\"text\":\"...\"},\n" +
            "          {\"kind\":\"call\",\"label\":\"...\",\"fn\":\"functionName\",\"args\":[1,2],\"expect\":3,\"tolerance\":0,\"verified\":false}]}\n" +
            "Rules:\n" +
            "- Plain HTML, CSS, JavaScript only. kind is \"pwa\" (or \"web\"). index.html is required and is the entry point.\n" +
            "- At most 8 files, as few as possible (5-6 is ideal). Allowed extensions: html, js, css, json, svg, webmanifest, txt.\n" +
            "- Keep the JSON COMPACT: one line, no indentation, short strings. A cut-off reply is useless.\n" +
            "- Every feature needs 1-3 acceptance criteria that can be observed in the running page (an element is visible, no console error, a value is shown).\n" +
            "- \"uses\" lists the files a file depends on; no cycles. \"exports\" lists exact function signatures other files may call.\n" +
            "- \"cards\" lists knowledge card ids a file needs, taken ONLY from the catalog given. Never invent card ids. If a needed fact has no card, say so in decisions.\n" +
            "- No external libraries or CDNs.\n" +
            "- \"tests\": 3-6 automatic checks derived from the acceptance criteria. kind is exists, text or call. A call test may only use a function listed in some file's exports.\n" +
            "- Set \"verified\":true ONLY when the expected value comes from a KNOWLEDGE CARD or the brief. Never guess expected values; if unsure, use an exists/text test instead.\n"

    private fun hasTests(reply: String): Boolean = Regex("\"tests\"\\s*:").containsMatchIn(reply)

    fun user(brief: String): String =
        "BRIEF:\n$brief\nKNOWLEDGE CARD CATALOG:\n${Cards.catalog()}\n"

    private fun isCutOff(problems: List<String>) =
        problems.any { it.contains("EOF", ignoreCase = true) || it.contains("End of input", ignoreCase = true) }

    private fun repairUser(brief: String, previous: String, problems: List<String>): String =
        if (isCutOff(problems)) {
            // Balasan terpotong: jangan kirim ulang potongannya; minta versi lebih pendek.
            user(brief) + "YOUR PREVIOUS REPLY WAS CUT OFF. Send the complete JSON again, much shorter: at most 6 files, at most 4 tests, " +
                "1 feature per ~2 files, no whitespace, no optional text.\n"
        } else {
            user(brief) + "PREVIOUS REPLY:\n" + previous.take(6000) + "\nPROBLEMS (fix all, resend the whole JSON):\n" +
                problems.take(8).joinToString("\n") { "- $it" } + "\n"
        }

    suspend fun run(llm: Llm, brief: String, maxRepairs: Int = 2): BlueprintResult {
        var calls = 0
        var reply = llm.call(SYSTEM, user(brief)); calls++
        var problems: List<String> = emptyList()
        for (attempt in 0..maxRepairs) {
            when (val r = SpecParser.parse(reply)) {
                is SpecResult.Err -> problems = listOf(r.message)
                is SpecResult.Ok -> {
                    problems = SpecValidator.validate(r.spec)
                    var plan: TestPlan? = null
                    if (problems.isEmpty() && hasTests(reply)) {
                        when (val p = PlanParser.parse(reply)) {
                            is PlanResult.Err -> problems = listOf("tests: " + p.message)
                            is PlanResult.Ok -> {
                                problems = PlanValidator.validate(p.plan, r.spec).map { "tests: $it" }
                                plan = p.plan
                            }
                        }
                    }
                    if (problems.isEmpty()) return BlueprintResult.Ok(r.spec, calls, plan)
                }
            }
            if (attempt == maxRepairs) break
            reply = llm.call(SYSTEM, repairUser(brief, reply, problems)); calls++
        }
        return BlueprintResult.Failed(problems, calls)
    }
}
