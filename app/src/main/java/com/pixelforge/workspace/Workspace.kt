package com.pixelforge.workspace

import android.content.Context
import java.io.File

/**
 * Wadah kode: folder project di penyimpanan app. Agent menulis file lewat blok:
 *   === FILE: path/ke/File.kt ===
 *   isi file
 *   === END FILE ===
 */
class Workspace(context: Context) {

    data class FileOut(val path: String, val content: String)

    private val root = File(context.filesDir, "workspace").apply { mkdirs() }

    @Synchronized
    fun write(files: List<FileOut>): List<String> {
        val written = mutableListOf<String>()
        val rootPath = root.canonicalPath + File.separator
        for (f in files) {
            if (f.content.length > 500_000) continue
            val target = File(root, f.path)
            if (!target.canonicalPath.startsWith(rootPath)) continue
            target.parentFile?.mkdirs()
            target.writeText(f.content.trimEnd('\n') + "\n")
            written += f.path
        }
        return written
    }

    @Synchronized
    fun list(): List<String> =
        root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted().toList()

    @Synchronized
    fun read(path: String): String {
        val f = File(root, path)
        if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) return ""
        return if (f.isFile) f.readText() else ""
    }

    @Synchronized
    fun readAll(): Map<String, String> = list().associateWith { read(it) }

    companion object {
        const val FILE_FORMAT_PROMPT =
            "When you produce code or any file, output EACH file exactly like this " +
                "(complete content, never truncated, NO markdown code fences around the block):\n" +
                "=== FILE: relative/path/File.ext ===\n<entire file content>\n=== END FILE ===\n"

        private val FILE_RE = Regex(
            """===\s*FILE:\s*(.+?)\s*===[ \t]*\r?\n(.*?)\r?\n?===\s*END FILE\s*===""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        fun extractFiles(text: String): List<FileOut> =
            FILE_RE.findAll(text).mapNotNull { m ->
                val path = cleanPath(m.groupValues[1]) ?: return@mapNotNull null
                FileOut(path, stripFence(m.groupValues[2]))
            }.toList()

        /** Ganti blok file dengan penanda ringkas supaya chat tetap enak dibaca. */
        fun stripFiles(text: String): String =
            FILE_RE.replace(text) { "📄 ${it.groupValues[1].trim()}" }

        private fun cleanPath(raw: String): String? {
            val p = raw.trim().trim('`', '"', '\'').replace('\\', '/').removePrefix("./").trimStart('/')
            if (p.isBlank() || p.length > 200) return null
            if (p.split('/').any { it == ".." || it.isEmpty() }) return null
            return p
        }

        private fun stripFence(s: String): String {
            val t = s.trim('\n', '\r')
            val lines = t.lines()
            if (lines.size >= 2 && lines.first().trimStart().startsWith("```") && lines.last().trim() == "```") {
                return lines.drop(1).dropLast(1).joinToString("\n")
            }
            return t
        }
    }
}
