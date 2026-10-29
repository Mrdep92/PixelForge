package com.pixelforge.engine

/** Outline deterministik tanpa model: bentuk file (fungsi, kelas, id elemen) dengan nomor baris. */
object Outline {
    private const val MAX_ENTRIES = 40
    private val JS_FUNC = Regex("""^\s*(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*\(([^)]*)\)""")
    private val JS_CLASS = Regex("""^\s*(?:export\s+)?class\s+(\w+)""")
    private val JS_CONST = Regex("""^\s*(?:export\s+)?(?:const|let|var)\s+(\w+)\s*=""")
    private val HTML_ID = Regex("""\bid\s*=\s*["']([^"']+)["']""")

    fun of(path: String, content: String): String {
        val out = mutableListOf<String>()
        val ext = path.substringAfterLast('.', "").lowercase()
        content.lines().forEachIndexed { i, line ->
            val n = i + 1
            when (ext) {
                "js" -> {
                    JS_FUNC.find(line)?.let { out += "L$n function ${it.groupValues[1]}(${it.groupValues[2].trim()})" }
                        ?: JS_CLASS.find(line)?.let { out += "L$n class ${it.groupValues[1]}" }
                        ?: JS_CONST.find(line)?.let { out += "L$n const ${it.groupValues[1]}" }
                }
                "html" -> HTML_ID.findAll(line).forEach { out += "L$n #${it.groupValues[1]}" }
            }
        }
        val shown = out.take(MAX_ENTRIES)
        return shown.joinToString("\n") + if (out.size > MAX_ENTRIES) "\n[... ${out.size - MAX_ENTRIES} lagi]" else ""
    }
}
