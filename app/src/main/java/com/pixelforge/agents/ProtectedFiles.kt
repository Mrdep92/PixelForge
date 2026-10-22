package com.pixelforge.agents

/**
 * File infrastruktur yang tidak boleh ditulis agent: wrapper Gradle dan workflow CI. Agent yang menulis
 * `gradlew` tanpa file pendukungnya membuat build gagal berulang (kasus About Muslim), jadi file ini
 * hanya boleh dibuat aplikasi dari kerangka yang sudah terbukti.
 *
 * Catatan: workflow CI boleh DIBUAT agent selama aplikasi belum punya kerangka sendiri (fase 5), tetapi
 * workflow yang sudah ada tidak boleh ditimpa atau diedit.
 */
object ProtectedFiles {

    private fun norm(path: String): String =
        path.trim().replace('\\', '/').removePrefix("./").trimStart('/').lowercase()

    private fun isWrapper(p: String): Boolean {
        val name = p.substringAfterLast('/')
        return name == "gradlew" || name == "gradlew.bat" || p == "gradle/wrapper" || p.startsWith("gradle/wrapper/") ||
            p.contains("/gradle/wrapper/")
    }

    private fun isWorkflow(p: String): Boolean = p.startsWith(".github/workflows/")

    /** Pesan penolakan untuk model, atau null kalau penulisan boleh. [exists] = file itu sudah ada. */
    fun violation(path: String, exists: Boolean): String? {
        val p = norm(path)
        return when {
            isWrapper(p) ->
                "'$path' adalah file wrapper Gradle yang dilindungi; hanya aplikasi yang boleh membuatnya. " +
                    "Jangan menulis gradlew atau gradle/wrapper; lanjutkan tanpa file ini."
            isWorkflow(p) && exists ->
                "'$path' adalah workflow CI yang sudah ada dan dilindungi; tidak boleh ditimpa atau diedit agent."
            else -> null
        }
    }
}
