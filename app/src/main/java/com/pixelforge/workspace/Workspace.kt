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

    data class FileOut(val path: String, val content: String, val complete: Boolean = true)

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

        sealed class Segment {
            data class Text(val text: String) : Segment()
            data class File(val path: String, val content: String, val complete: Boolean) : Segment()
        }

        private val START_RE = Regex("""^\s*={2,}\s*FILE:\s*(.+?)\s*=*\s*$""")
        private val END_RE = Regex("""^\s*={2,}\s*END\s+FILE\s*=*\s*$""", RegexOption.IGNORE_CASE)

        /**
         * Pecah output agent jadi teks biasa dan blok file. Toleran: kalau penanda END hilang
         * (jawaban terpotong), file tetap diambil tapi ditandai tidak lengkap.
         */
        fun parse(text: String): List<Segment> {
            val out = mutableListOf<Segment>()
            val textBuf = StringBuilder()
            val fileBuf = StringBuilder()
            var curPath: String? = null
            for (line in text.lines()) {
                val m = START_RE.matchEntire(line)
                if (m != null) {
                    val prev = curPath
                    if (prev != null) {
                        out += Segment.File(prev, fileBuf.toString(), false)
                        fileBuf.clear()
                    } else if (textBuf.isNotEmpty()) {
                        out += Segment.Text(textBuf.toString())
                        textBuf.clear()
                    }
                    curPath = m.groupValues[1].trim()
                    continue
                }
                val cur = curPath
                if (cur != null) {
                    if (END_RE.matches(line)) {
                        out += Segment.File(cur, fileBuf.toString(), true)
                        fileBuf.clear()
                        curPath = null
                    } else {
                        fileBuf.append(line).append('\n')
                    }
                } else {
                    textBuf.append(line).append('\n')
                }
            }
            val last = curPath
            if (last != null) out += Segment.File(last, fileBuf.toString(), false)
            else if (textBuf.isNotEmpty()) out += Segment.Text(textBuf.toString())
            return out
        }

        fun extractFiles(text: String): List<FileOut> =
            parse(text).filterIsInstance<Segment.File>().mapNotNull { f ->
                val path = cleanPath(f.path) ?: return@mapNotNull null
                val body = stripFence(f.content)
                if (body.isBlank()) null else FileOut(path, body, f.complete)
            }

        /** Ganti blok file dengan penanda ringkas supaya chat tetap enak dibaca. */
        fun stripFiles(text: String): String =
            parse(text).joinToString("\n") { seg ->
                when (seg) {
                    is Segment.Text -> seg.text.trimEnd()
                    is Segment.File -> "📄 " + seg.path + if (seg.complete) "" else " ⚠ terpotong"
                }
            }

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
