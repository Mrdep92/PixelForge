package com.pixelforge.engine

import com.google.gson.JsonParser

/** Pengecekan otomatis tanpa model. Tiap fungsi mengembalikan daftar masalah (kosong = lulus). */
object Checks {

    private val ALLOWED_HOSTS = listOf("cdnjs.cloudflare.com", "cdn.jsdelivr.net", "unpkg.com")
    private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    private val OPTIONAL_CLOSE = setOf("p", "li", "td", "th", "tr", "thead", "tbody", "tfoot", "option", "dt", "dd", "colgroup", "html", "body", "head")

    /** Pagar tiket: hanya file tiket yang boleh ditulis. */
    fun fence(ticket: Ticket, writtenPaths: Collection<String>): List<String> =
        writtenPaths.filter { it != ticket.file }.map { "Hanya boleh menulis '${ticket.file}', bukan '$it'." }

    /** Semua pengecekan untuk file hasil tiket. [files] = isi semua file proyek (termasuk kandidat ini). */
    fun all(ticket: Ticket, content: String, files: Map<String, String>, specPaths: Collection<String>): List<String> =
        leftovers(content) + syntax(ticket.file, content) + references(ticket, content, files, specPaths)

    /** Sisa penanda format yang bocor dari balasan model. */
    fun leftovers(content: String): List<String> {
        val out = mutableListOf<String>()
        if (content.isBlank()) out += "File kosong."
        if (content.lines().any { it.trimStart().startsWith("=== END") || it.trimStart().startsWith("=== FILE") }) out += "Ada sisa penanda '===' di dalam file."
        if (content.lines().any { it.startsWith("<<<<<<<") || it.startsWith(">>>>>>>") }) out += "Ada sisa penanda konflik di dalam file."
        if (content.trimStart().startsWith("```") || content.trimEnd().endsWith("```")) out += "File dibungkus pagar markdown (```)."
        return out
    }

    fun syntax(path: String, content: String): List<String> = when (path.substringAfterLast('.', "").lowercase()) {
        "json", "webmanifest" -> jsonErrors(content)
        "js" -> bracketErrors(content, js = true)
        "css" -> bracketErrors(content, js = false)
        "html" -> htmlErrors(content)
        else -> emptyList()
    }

    private fun jsonErrors(text: String): List<String> = try {
        JsonParser.parseString(text); emptyList()
    } catch (e: Exception) {
        listOf("JSON tidak valid: ${e.message?.take(100)}")
    }

    /** Keseimbangan kurung, mengabaikan string, komentar, dan literal regex. */
    fun bracketErrors(src: String, js: Boolean): List<String> {
        val stack = ArrayDeque<Char>()
        var i = 0
        var prev = ' '
        val n = src.length
        fun err(msg: String) = listOf(msg)
        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else ' '
            when {
                c == '/' && next == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    if (end < 0) return err("Komentar /* tidak ditutup.")
                    i = end + 2; continue
                }
                js && c == '/' && next == '/' -> {
                    val end = src.indexOf('\n', i)
                    i = if (end < 0) n else end; continue
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && src[j] != c && src[j] != '\n') { if (src[j] == '\\') j++; j++ }
                    if (j >= n || src[j] != c) return err("String tidak ditutup di sekitar posisi $i.")
                    i = j + 1; prev = c; continue
                }
                js && c == '`' -> {
                    var j = i + 1
                    var depth = 0
                    while (j < n) {
                        val d = src[j]
                        if (d == '\\') { j += 2; continue }
                        if (depth == 0 && d == '`') break
                        if (d == '$' && j + 1 < n && src[j + 1] == '{') { depth++; j += 2; continue }
                        if (depth > 0 && d == '}') depth--
                        j++
                    }
                    if (j >= n) return err("Template string ` tidak ditutup.")
                    i = j + 1; prev = '`'; continue
                }
                js && c == '/' && (prev in "(,=:[!&|?{};+-*%<>~^" || prev == ' ' && i == 0) -> {
                    var j = i + 1
                    var inClass = false
                    while (j < n && src[j] != '\n') {
                        val d = src[j]
                        if (d == '\\') { j += 2; continue }
                        if (d == '[') inClass = true else if (d == ']') inClass = false
                        else if (d == '/' && !inClass) break
                        j++
                    }
                    if (j < n && src[j] == '/') { i = j + 1; prev = '/'; continue }
                }
                c == '(' || c == '[' || c == '{' -> stack.addLast(c)
                c == ')' || c == ']' || c == '}' -> {
                    val open = stack.removeLastOrNull() ?: return err("Kurung '$c' berlebih di posisi $i.")
                    val ok = (open == '(' && c == ')') || (open == '[' && c == ']') || (open == '{' && c == '}')
                    if (!ok) return err("Kurung '$open' ditutup dengan '$c' di posisi $i.")
                }
            }
            if (!c.isWhitespace()) prev = c
            i++
        }
        return if (stack.isEmpty()) emptyList() else err("Kurung '${stack.last()}' tidak ditutup (file mungkin terpotong).")
    }

    private fun stripBlocks(html: String): String =
        html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "<script></script>")
            .replace(Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "<style></style>")

    fun htmlErrors(html: String): List<String> {
        val text = stripBlocks(html)
        val errors = mutableListOf<String>()
        val trimmed = text.trimStart().lowercase()
        if ((trimmed.startsWith("<!doctype") || trimmed.startsWith("<html")) && !text.lowercase().contains("</html>")) {
            errors += "HTML tidak ditutup dengan </html> (file mungkin terpotong)."
        }
        val stack = ArrayDeque<String>()
        for (m in Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*?(/?)>").findAll(text)) {
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val selfClosed = m.groupValues[3] == "/"
            if (tag in VOID_TAGS || selfClosed) continue
            if (!closing) { stack.addLast(tag); continue }
            if (tag !in stack) { errors += "Tag penutup </$tag> tanpa pembuka."; continue }
            while (stack.isNotEmpty() && stack.last() != tag) {
                val skipped = stack.removeLast()
                if (skipped !in OPTIONAL_CLOSE) errors += "Tag <$skipped> tidak ditutup sebelum </$tag>."
            }
            stack.removeLastOrNull()
        }
        stack.filter { it !in OPTIONAL_CLOSE }.forEach { errors += "Tag <$it> tidak ditutup." }
        return errors.distinct().take(5)
    }

    private val JS_EXPORT_NAME = Regex("^[A-Za-z_$][\\w$]*")
    private val DOM_ID_USE = Regex("""getElementById\(\s*['"]([^'"]+)['"]\s*\)|querySelector\(\s*['"]#([\w-]+)['"]\s*\)""")
    private val HTML_ID_DEF = Regex("""\bid\s*=\s*["']([^"']+)["']""")
    private val SRC_HREF = Regex("""<(?:script|link)\b[^>]*?\b(?:src|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** Rujukan: ekspor yang dijanjikan ada, file yang dirujuk ada, id elemen cocok, tidak ada sumber luar terlarang. */
    fun references(ticket: Ticket, content: String, files: Map<String, String>, specPaths: Collection<String>): List<String> {
        val errors = mutableListOf<String>()
        val ext = ticket.file.substringAfterLast('.', "").lowercase()

        if (ext == "js") {
            for (e in ticket.exports) {
                val name = JS_EXPORT_NAME.find(e.trim())?.value ?: continue
                val def = Regex("""\b(?:function|const|let|var|class)\s+\Q$name\E\b|\b\Q$name\E\s*[:=]\s*(?:async\s*)?(?:function|\()|window\.\Q$name\E\s*=""")
                if (!def.containsMatchIn(content)) errors += "Ekspor yang dijanjikan '$name' tidak ditemukan di ${ticket.file}."
            }
        }
        if (ext == "html") {
            for (m in SRC_HREF.findAll(content)) {
                val ref = m.groupValues[1]
                if (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("//")) {
                    if (ALLOWED_HOSTS.none { ref.contains(it) }) errors += "Sumber luar tidak diizinkan: $ref."
                } else if (!ref.startsWith("data:") && !ref.startsWith("#")) {
                    val clean = ref.removePrefix("./").substringBefore('?').substringBefore('#')
                    if (clean.isNotBlank() && clean !in specPaths && clean !in files) errors += "Merujuk file yang tidak ada di spec: $clean."
                }
            }
        }
        if (ext == "js" && ticket.domFill.isNotEmpty()) {
            // Kontrak DOM: JS hanya boleh memakai id dari kontrak (atau id yang ia buat sendiri), bukan menebak isi index.html.
            val own = Regex("""\.id\s*=\s*["']([\w-]+)["']|\bid=\\?["']([\w-]+)""").findAll(content)
                .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
            val used = DOM_ID_USE.findAll(content).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
            (used - ticket.domFill.toSet() - own).sorted().take(5).forEach {
                errors += "JavaScript memakai id elemen '$it' yang tidak ada di kontrak DOM. Id yang boleh dipakai: ${ticket.domFill.joinToString(", ")}."
            }
        }
        return errors
    }
}
