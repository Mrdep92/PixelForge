package com.pixelforge.agents

/**
 * Perbaikan CI tanpa LLM untuk kegagalan yang sudah pasti penyebabnya: workflow memanggil ./gradlew padahal
 * wrapper tidak ada (kasus About Muslim). Agent tidak boleh mengedit workflow (dilindungi) dan gradlew tidak bisa
 * dibuat model, jadi aplikasi menggantinya dengan workflow terbukti dan, bila perlu, kerangka Gradle.
 */
object CiAutoFix {

    private fun hasWrapper(files: Collection<String>) = files.any { it.endsWith("gradle/wrapper/gradle-wrapper.jar") }

    /** Berkas yang harus ditulis (path → isi); kosong bila tidak ada perbaikan pasti. */
    fun plan(files: Map<String, String>, projectName: String): Map<String, String> {
        if (hasWrapper(files.keys)) return emptyMap()
        val badWorkflows = files.filter { (path, text) ->
            path.startsWith(".github/workflows/") && (path.endsWith(".yml") || path.endsWith(".yaml")) &&
                (text.contains("./gradlew") || Regex("""\bgradlew\b""").containsMatchIn(text))
        }.keys
        if (badWorkflows.isEmpty()) return emptyMap()

        val scaffold = AndroidScaffold.files(projectName)
        val goodWorkflow = scaffold.getValue(AndroidScaffold.WORKFLOW_PATH)
        val out = linkedMapOf<String, String>()
        val hasGradleProject = files.keys.any { it.substringAfterLast('/').startsWith("settings.gradle") }
        if (!hasGradleProject) scaffold.filterKeys { it != AndroidScaffold.WORKFLOW_PATH }.forEach { (k, v) -> if (k !in files) out[k] = v }
        badWorkflows.forEach { out[it] = goodWorkflow }
        // Satu workflow cukup; jangan tambah build-apk.yml kalau workflow lama sudah diganti.
        return out
    }
}
