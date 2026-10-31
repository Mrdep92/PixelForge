package com.pixelforge.engine

/** Masalah kecocokan antar file, dicari kode (tanpa LLM). [file] = file yang harus diperbaiki. */
data class Issue(val file: String, val message: String)

/** Menyusun bagian yang bisa diputuskan kode: tag script/link untuk file milik aplikasi. */
object Assemble {
    private val SCRIPT_SRC = Regex("""<script[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val LINK_HREF = Regex("""<link[^>]*\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun norm(p: String) = p.substringBefore('?').substringBefore('#').removePrefix("./").removePrefix("/")

    /** Menambah <link>/<script> untuk file css/js di spec yang belum dirujuk index.html (js menurut urutan ketergantungan). */
    fun injectAssets(html: String, spec: AppSpec): String {
        val ordered = (TicketPlanner.order(spec) ?: spec.files).map { it.path }
        val scripts = SCRIPT_SRC.findAll(html).map { norm(it.groupValues[1]) }.toSet()
        val links = LINK_HREF.findAll(html).map { norm(it.groupValues[1]) }.toSet()
        val missingJs = ordered.filter { it.endsWith(".js") && it != "sw.js" && it !in scripts }
        val missingCss = ordered.filter { it.endsWith(".css") && it !in links }
        val needBase = BaseCss.PATH !in links
        var out = html
        if (missingCss.isNotEmpty()) {
            val tags = missingCss.joinToString("") { "<link rel=\"stylesheet\" href=\"$it\">\n" }
            val i = out.indexOf("</head>", ignoreCase = true)
            out = if (i >= 0) out.substring(0, i) + tags + out.substring(i) else tags + out
        }
        if (needBase) {
            // base.css harus paling awal agar style.css buatan model bisa menimpanya.
            val base = "<link rel=\"stylesheet\" href=\"${BaseCss.PATH}\">\n"
            val firstLink = Regex("<link[^>]*stylesheet", RegexOption.IGNORE_CASE).find(out)?.range?.first
            val head = out.indexOf("</head>", ignoreCase = true)
            out = when {
                firstLink != null -> out.substring(0, firstLink) + base + out.substring(firstLink)
                head >= 0 -> out.substring(0, head) + base + out.substring(head)
                else -> base + out
            }
        }
        if (missingJs.isNotEmpty()) {
            val tags = missingJs.joinToString("") { "<script src=\"$it\"></script>\n" }
            val first = SCRIPT_SRC.find(out)?.range?.first?.let { out.lastIndexOf("<script", it, ignoreCase = true) }
            val body = out.lastIndexOf("</body>", ignoreCase = true)
            out = when {
                first != null && first >= 0 -> out.substring(0, first) + tags + out.substring(first)
                body >= 0 -> out.substring(0, body) + tags + out.substring(body)
                else -> out + "\n" + tags
            }
        }
        return out
    }
}

object AppChecks {
    private val INLINE_SCRIPT = Regex("""<script(?![^>]*\bsrc\b)([^>]*)>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
    private val HTML_ID = Regex("""\bid\s*=\s*["']([A-Za-z][\w-]*)["']""")
    private val JS_USE_ID = Regex("""getElementById\(\s*["']([\w-]+)["']\s*\)|querySelector(?:All)?\(\s*["']#([\w-]+)["']\s*\)""")
    private val JS_DEF_ID = Regex("""\.id\s*=\s*["']([\w-]+)["']|\bid=\\?["']([\w-]+)""")
    private val BODY_APPEND = Regex("""document\.body\.(?:appendChild|append|insertBefore|prepend)\s*\(""")
    private val LOCAL_REF = Regex("""["']((?:\.{0,2}/)?[A-Za-z0-9_\-./]+\.(?:js|css|json|webmanifest|html))["']""")

    fun verify(spec: AppSpec, files: Map<String, String>): List<Issue> {
        val out = mutableListOf<Issue>()
        val paths = spec.files.map { it.path }.toSet()
        val known = paths + spec.assets.mapNotNull { Assets.get(it)?.file } + BaseCss.PATH
        val html = files["index.html"]

        for ((path, text) in files) {
            if (path in paths && Checks.arabic(text).isNotEmpty()) out += Issue(path, Checks.arabic(text).first())
        }

        if (html != null) {
            for (m in INLINE_SCRIPT.findAll(html)) {
                val type = m.groupValues[1].lowercase()
                if (m.groupValues[2].isNotBlank() && "json" !in type) {
                    out += Issue("index.html", "Ada blok <script> inline. Hapus; perilaku harus ada di file .js, dan tag script untuk file aplikasi ditambahkan otomatis.")
                    break
                }
            }
            val have = HTML_ID.findAll(html).map { it.groupValues[1] }.toSet()
            spec.dom.map { it.id }.filter { it !in have }.take(6).forEach {
                out += Issue("index.html", "Elemen dari kontrak DOM tidak ada: id=\"$it\" (harus ada, kosong, dengan id persis itu).")
            }
        }

        val htmlIds = html?.let { h -> HTML_ID.findAll(h).map { it.groupValues[1] }.toSet() }.orEmpty()
        val jsFiles = files.filterKeys { it.endsWith(".js") && it in paths }
        val jsDefined = jsFiles.values.flatMap { j -> JS_DEF_ID.findAll(j).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList() }.toSet()
        for ((path, js) in jsFiles) {
            val used = JS_USE_ID.findAll(js).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
            val missing = used.filter { it !in htmlIds && it !in jsDefined }.sorted()
            if (html != null && missing.isNotEmpty()) {
                val known = spec.dom.map { it.id }.take(15).joinToString(", ").ifEmpty { htmlIds.take(15).joinToString(", ") }
                out += Issue(path, "Memakai id elemen yang tidak ada di index.html: ${missing.take(5).joinToString(", ")}. Id yang ada: $known.")
            }
            if (BODY_APPEND.containsMatchIn(js)) {
                out += Issue(path, "Membuat elemen langsung di document.body. index.html sudah memiliki strukturnya; isi elemen yang ada lewat document.getElementById.")
            }
        }

        for ((path, text) in files) {
            if (path !in paths || !(path.endsWith(".js") || path.endsWith(".html"))) continue
            val bad = LOCAL_REF.findAll(text).map { it.groupValues[1] }.filter { ref ->
                ref.startsWith("/") || ref.removePrefix("./") !in known
            }.distinct().take(4).toList()
            if (bad.isNotEmpty()) {
                out += Issue(
                    path,
                    "Acuan file yang tidak valid: ${bad.joinToString(", ")}. Pakai path relatif tanpa '/' di depan, dan hanya file yang ada: ${known.take(12).joinToString(", ")}."
                )
            }
        }
        return out
    }
}
