package com.pixelforge.engine

/**
 * Berkas data terverifikasi yang disediakan aplikasi dan disalin kode ke project apa adanya.
 * Model tidak menulis isinya (teks Al-Quran tidak boleh dikarang); ia hanya membacanya lewat fetch().
 */
data class Asset(val id: String, val file: String, val resource: String, val description: String)

object Assets {
    val ALL: List<Asset> = listOf(
        Asset(
            id = "quran-juz30",
            file = "data/quran-juz30.json",
            resource = "quran-juz30.json",
            description = "Juz 30 (surah 78-114, 564 ayat): teks Arab Utsmani terverifikasi + terjemahan Indonesia Kemenag. " +
                "JSON: {\"source\":\"...\",\"surahs\":[{\"n\":103,\"ar\":\"nama arab\",\"lat\":\"Al-'Asr\",\"id\":\"arti\",\"verses\":[{\"n\":1,\"ar\":\"teks arab\",\"t\":\"terjemahan\"}]}]}. " +
                "Muat dengan fetch('data/quran-juz30.json'). Tampilkan 'source' sebagai atribusi."
        )
    )

    private val byId = ALL.associateBy { it.id }
    fun get(id: String): Asset? = byId[id]
    fun known(id: String) = id in byId

    fun catalog(): String = ALL.joinToString("\n") { "- ${it.id}: ${it.description.take(110)}..." }

    /** Teks untuk tiket: cara memakai berkas tanpa menyalin isinya. */
    fun factsFor(ids: List<String>): List<String> = ids.mapNotNull { get(it) }.map {
        "ASSET ${it.file} sudah ada di project (jangan ditulis ulang). ${it.description} " +
            "Jangan mengetik teks Arab sendiri; ambil dari berkas ini. Beri class \"arabic\" pada elemen teks Arab."
    }
}

/** Gaya dasar mobile-first yang ditambahkan kode ke setiap project web. Menata elemen standar tanpa class. */
object BaseCss {
    const val PATH = "base.css"

    const val TEXT = """:root{--bg:#f4f1ea;--card:#ffffff;--ink:#1f2a24;--muted:#5d6b63;--accent:#0f766e;--accent-ink:#ffffff;--line:#d9d3c7;--radius:14px;--gap:16px}
@media (prefers-color-scheme:dark){:root{--bg:#10161a;--card:#18222a;--ink:#e8eee9;--muted:#9fb0a7;--accent:#34c3b0;--accent-ink:#06201c;--line:#2b3a44}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;padding:var(--gap);background:var(--bg);color:var(--ink);font:16px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;max-width:720px;margin-inline:auto}
h1{font-size:1.6rem;margin:.2em 0 .6em;color:var(--accent)}
h2{font-size:1.15rem;margin:0 0 .6em;color:var(--accent)}
h3{font-size:1rem;margin:.8em 0 .4em}
section,article,header,footer,nav{background:var(--card);border:1px solid var(--line);border-radius:var(--radius);padding:var(--gap);margin:0 0 var(--gap);box-shadow:0 1px 2px rgba(0,0,0,.05)}
p{margin:.4em 0}
ul,ol{padding-inline-start:1.3em;margin:.4em 0}
li{margin:.25em 0}
button,input,select,textarea{font:inherit;color:inherit}
button{background:var(--accent);color:var(--accent-ink);border:0;border-radius:10px;padding:.65em 1.1em;cursor:pointer;min-height:44px}
button:active{opacity:.85}
input,select,textarea{width:100%;background:var(--bg);border:1px solid var(--line);border-radius:10px;padding:.6em .8em;min-height:44px}
label{display:block;margin:.5em 0 .25em;color:var(--muted);font-size:.9rem}
table{width:100%;border-collapse:collapse}
td,th{padding:.5em .4em;border-bottom:1px solid var(--line);text-align:start}
.arabic,[lang=ar]{font-family:"Noto Naskh Arabic","Amiri","Scheherazade New",serif;font-size:1.6rem;line-height:2.1;direction:rtl;text-align:right}
.muted{color:var(--muted);font-size:.9rem}
"""
}
