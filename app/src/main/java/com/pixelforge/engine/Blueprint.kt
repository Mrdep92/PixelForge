package com.pixelforge.engine

sealed class BlueprintResult {
    data class Ok(val spec: AppSpec, val calls: Int) : BlueprintResult()
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
            " \"facts\":[], \"decisions\":[\"short design decision\"]}\n" +
            "Rules:\n" +
            "- Plain HTML, CSS, JavaScript only. kind is \"pwa\" (or \"web\"). index.html is required and is the entry point.\n" +
            "- At most ${SpecValidator.MAX_FILES} files, as few as possible. Allowed extensions: html, js, css, json, svg, webmanifest, txt.\n" +
            "- Every feature needs 1-3 acceptance criteria that can be observed in the running page (an element is visible, no console error, a value is shown).\n" +
            "- \"uses\" lists the files a file depends on; no cycles. \"exports\" lists exact function signatures other files may call.\n" +
            "- \"cards\" lists knowledge card ids a file needs, taken ONLY from the catalog given. Never invent card ids. If a needed fact has no card, say so in decisions.\n" +
            "- No external libraries or CDNs.\n"

    fun user(brief: String): String =
        "BRIEF:\n$brief\nKNOWLEDGE CARD CATALOG:\n${Cards.catalog()}\n"

    private fun repairUser(brief: String, previous: String, problems: List<String>): String =
        user(brief) + "PREVIOUS REPLY:\n" + previous.take(6000) + "\nPROBLEMS (fix all, resend the whole JSON):\n" +
            problems.take(8).joinToString("\n") { "- $it" } + "\n"

    suspend fun run(llm: Llm, brief: String, maxRepairs: Int = 1): BlueprintResult {
        var calls = 0
        var reply = llm.call(SYSTEM, user(brief)); calls++
        var problems: List<String> = emptyList()
        for (attempt in 0..maxRepairs) {
            when (val r = SpecParser.parse(reply)) {
                is SpecResult.Err -> problems = listOf(r.message)
                is SpecResult.Ok -> {
                    problems = SpecValidator.validate(r.spec)
                    if (problems.isEmpty()) return BlueprintResult.Ok(r.spec, calls)
                }
            }
            if (attempt == maxRepairs) break
            reply = llm.call(SYSTEM, repairUser(brief, reply, problems)); calls++
        }
        return BlueprintResult.Failed(problems, calls)
    }
}
