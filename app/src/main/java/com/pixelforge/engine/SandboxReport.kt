package com.pixelforge.engine

import com.google.gson.JsonParser

/** Penguji halaman. Implementasi nyata memakai WebView tersembunyi; tes memakai tiruan. */
interface Sandbox {
    suspend fun run(plan: TestPlan, timeoutMs: Long = 10_000): SandboxReport
}

data class CheckResult(val label: String, val ok: Boolean, val detail: String)

/** Hasil satu kali uji di sandbox. [harnessError] diisi bila alat ujinya sendiri bermasalah (bukan kode aplikasi). */
data class SandboxReport(
    val loaded: Boolean,
    val consoleErrors: List<String>,
    val results: List<CheckResult>,
    val harnessError: String? = null
) {
    val passed: Boolean get() = harnessError == null && loaded && consoleErrors.isEmpty() && results.all { it.ok }
}

object SandboxReports {
    fun parseResults(json: String): List<CheckResult>? = try {
        JsonParser.parseString(json).asJsonArray.map {
            val o = it.asJsonObject
            CheckResult(o.get("label").asString, o.get("ok").asBoolean, o.get("detail")?.asString.orEmpty())
        }
    } catch (_: Exception) { null }
}

object FailureKind {
    const val CODE_BUG = "CODE_BUG"   // kode salah: Debugger boleh memperbaiki
    const val TEST_BUG = "TEST_BUG"   // tes/acuan diragukan: jangan ubah kode, tanya manusia
    const val HARNESS = "HARNESS"     // alat uji bermasalah: bukan salah kode aplikasi
}

data class Failure(val kind: String, val message: String)

/** Triase tanpa LLM: memilah jenis kegagalan supaya Debugger tidak memperbaiki hal yang bukan bug kode. */
object Triage {
    fun classify(report: SandboxReport, plan: TestPlan): List<Failure> {
        if (report.harnessError != null) return listOf(Failure(FailureKind.HARNESS, report.harnessError))
        val out = mutableListOf<Failure>()
        if (!report.loaded) out += Failure(FailureKind.CODE_BUG, "Halaman tidak termuat.")
        report.consoleErrors.take(5).forEach { out += Failure(FailureKind.CODE_BUG, "Galat konsol: $it") }
        val byLabel = plan.checks.associateBy { it.label }
        for (r in report.results.filter { !it.ok }) {
            val check = byLabel[r.label]
            val mismatch = r.detail.startsWith("hasil ")
            val kind = when {
                r.detail.startsWith("fungsi async") || r.detail.startsWith("jenis tidak dikenal") -> FailureKind.TEST_BUG
                mismatch && check != null && !check.verified -> FailureKind.TEST_BUG
                else -> FailureKind.CODE_BUG
            }
            val note = if (kind == FailureKind.TEST_BUG && mismatch) " (nilai acuan belum terverifikasi)" else ""
            out += Failure(kind, "${r.label}: ${r.detail}$note")
        }
        return out
    }

    /** Hanya bug kode yang boleh diserahkan ke Debugger. */
    fun forDebugger(failures: List<Failure>): List<Failure> = failures.filter { it.kind == FailureKind.CODE_BUG }

    fun render(failures: List<Failure>): String =
        failures.joinToString("\n") { "- [${it.kind}] ${it.message}" }
}
