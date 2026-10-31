package com.pixelforge.engine

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Satu pemeriksaan di sandbox. kind: EXISTS (elemen ada), TEXT (elemen memuat teks), CALL (fungsi dipanggil, hasil dibandingkan).
 * [args] dan [expect] berupa JSON. [verified] = nilai acuan berasal dari kartu fakta/kamu, bukan tebakan model.
 */
data class Check(
    val kind: String,
    val label: String,
    val selector: String = "",
    val text: String = "",
    val fn: String = "",
    val args: String = "[]",
    val expect: String = "null",
    val tolerance: Double = 0.0,
    val verified: Boolean = false
) {
    companion object {
        const val EXISTS = "EXISTS"
        const val TEXT = "TEXT"
        const val CALL = "CALL"
        const val NONEMPTY = "NONEMPTY"
    }
}

data class TestPlan(val checks: List<Check>)

sealed class PlanResult {
    data class Ok(val plan: TestPlan) : PlanResult()
    data class Err(val message: String) : PlanResult()
}

object PlanParser {
    fun parse(reply: String): PlanResult {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return PlanResult.Err("Rencana tes tidak berisi JSON.")
        val root = try {
            JsonParser.parseString(reply.substring(start, end + 1)).asJsonObject
        } catch (e: Exception) {
            return PlanResult.Err("Rencana tes bukan JSON valid: ${e.message?.take(80)}")
        }
        val arr = (root.get("checks") ?: root.get("tests"))?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return PlanResult.Err("Rencana tes tidak punya daftar 'checks'.")
        val checks = mutableListOf<Check>()
        for ((i, el) in arr.withIndex()) {
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return PlanResult.Err("Check ${i + 1} bukan objek.")
            checks += Check(
                kind = str(o, "kind").uppercase(),
                label = str(o, "label").ifBlank { "check ${i + 1}" },
                selector = str(o, "selector"),
                text = str(o, "text"),
                fn = str(o, "fn"),
                args = o.get("args")?.takeUnless { it.isJsonNull }?.toString() ?: "[]",
                expect = o.get("expect")?.takeUnless { it.isJsonNull }?.toString() ?: "null",
                tolerance = o.get("tolerance")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0,
                verified = o.get("verified")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            )
        }
        return PlanResult.Ok(TestPlan(checks))
    }

    private fun str(o: JsonObject, k: String): String =
        o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    fun write(plan: TestPlan): String {
        val arr = JsonArray()
        plan.checks.forEach { c ->
            arr.add(JsonObject().apply {
                addProperty("kind", c.kind); addProperty("label", c.label)
                if (c.selector.isNotEmpty()) addProperty("selector", c.selector)
                if (c.text.isNotEmpty()) addProperty("text", c.text)
                if (c.fn.isNotEmpty()) {
                    addProperty("fn", c.fn)
                    add("args", JsonParser.parseString(c.args)); add("expect", JsonParser.parseString(c.expect))
                    addProperty("tolerance", c.tolerance); addProperty("verified", c.verified)
                }
            })
        }
        return JsonObject().apply { add("checks", arr) }.toString()
    }
}

/** Pemeriksaan rencana tes oleh kode, sebelum dipakai. */
object PlanValidator {
    const val MAX_CHECKS = 30
    private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$")

    fun isSafeIdent(s: String) = IDENT.matches(s)

    private fun isJson(text: String, array: Boolean): Boolean = try {
        val e: JsonElement = JsonParser.parseString(text)
        if (array) e.isJsonArray else true
    } catch (_: Exception) { false }

    fun validate(plan: TestPlan, spec: AppSpec): List<String> {
        val out = mutableListOf<String>()
        if (plan.checks.isEmpty()) return listOf("Rencana tes kosong.")
        if (plan.checks.size > MAX_CHECKS) out += "Terlalu banyak check (${plan.checks.size}); maksimal $MAX_CHECKS."
        val exported = spec.files.flatMap { it.exports }.map { it.substringBefore('(').trim() }.toSet()
        for (c in plan.checks) {
            val who = "'${c.label}'"
            when (c.kind) {
                Check.EXISTS, Check.NONEMPTY -> if (c.selector.isBlank()) out += "$who: selector kosong."
                Check.TEXT -> {
                    if (c.selector.isBlank()) out += "$who: selector kosong."
                    if (c.text.isBlank()) out += "$who: teks kosong."
                }
                Check.CALL -> {
                    if (!isSafeIdent(c.fn)) out += "$who: nama fungsi '${c.fn}' tidak aman."
                    else if (c.fn !in exported) out += "$who: fungsi '${c.fn}' tidak dijanjikan blueprint."
                    if (!isJson(c.args, true)) out += "$who: args bukan array JSON."
                    if (!isJson(c.expect, false)) out += "$who: expect bukan JSON."
                }
                else -> out += "$who: jenis '${c.kind}' tidak dikenal."
            }
        }
        return out
    }
}
