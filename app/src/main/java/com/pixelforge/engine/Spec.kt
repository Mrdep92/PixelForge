package com.pixelforge.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Fitur aplikasi dan kriteria lulusnya (kriteria dicek mesin bila bisa, sisanya oleh auditor). */
data class Feature(val id: String, val title: String, val accept: List<String>)

/** Satu file yang boleh ada. [exports] = tanda tangan yang dijanjikan; [uses] = file lain yang dipakainya. */
data class FileSpec(
    val path: String,
    val features: List<String>,
    val exports: List<String>,
    val uses: List<String>,
    /** Id kartu pengetahuan yang dibutuhkan file ini (hanya itu yang masuk tiketnya). */
    val cards: List<String> = emptyList()
)

/** Sumber kebenaran proyek: dibuat sekali oleh tahap blueprint, lalu divalidasi kode. */
data class AppSpec(
    val name: String,
    val kind: String,
    val lang: String,
    val features: List<Feature>,
    val files: List<FileSpec>,
    val facts: List<String>,
    val decisions: List<String>,
    /** Kontrak DOM: elemen yang dimiliki index.html (kosong) dan file JS yang mengisinya. */
    val dom: List<DomItem> = emptyList(),
    /** Id berkas data terverifikasi dari [Assets] yang disalin kode ke project. */
    val assets: List<String> = emptyList()
)

/** [lazy] = elemen sengaja kosong saat halaman dimuat (baru terisi setelah pengguna beraksi). */
data class DomItem(val id: String, val by: String, val lazy: Boolean = false)

sealed class SpecResult {
    data class Ok(val spec: AppSpec) : SpecResult()
    data class Err(val message: String) : SpecResult()
}

private fun JsonObject.str(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

private fun JsonObject.strList(key: String): List<String> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList()

private fun JsonObject.objList(key: String): List<JsonObject> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject } ?: emptyList()

object SpecParser {

    /** Ambil objek JSON dari balasan model (boleh dibungkus ```json ... ```). */
    fun extractJson(text: String): String? {
        val fence = Regex("```(?:json)?\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(text)
        val body = fence?.groupValues?.get(1) ?: text
        val a = body.indexOf('{')
        val b = body.lastIndexOf('}')
        return if (a >= 0 && b > a) body.substring(a, b + 1) else null
    }

    fun parse(text: String): SpecResult {
        val json = extractJson(text) ?: return SpecResult.Err("Tidak ada objek JSON di balasan.")
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return SpecResult.Err("JSON spec tidak valid: ${e.message?.take(120)}")
        }
        val app = root.get("app")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val features = root.objList("features").map { f ->
            Feature(f.str("id"), f.str("title"), f.strList("accept"))
        }
        val files = root.objList("files").map { f ->
            FileSpec(f.str("path"), f.strList("features"), f.strList("exports"), f.strList("uses"), f.strList("cards"))
        }
        return SpecResult.Ok(
            AppSpec(
                name = app.str("name"),
                kind = app.str("kind").ifBlank { "pwa" },
                lang = app.str("lang").ifBlank { "id" },
                features = features,
                files = files,
                facts = root.strList("facts"),
                decisions = root.strList("decisions"),
                dom = root.objList("dom").map {
                    DomItem(it.str("id"), it.str("by"), it.get("lazy")?.takeIf { l -> l.isJsonPrimitive }?.asBoolean ?: false)
                },
                assets = root.strList("assets")
            )
        )
    }
}

object SpecValidator {
    const val MAX_FILES = 12
    private val ALLOWED_EXT = setOf("html", "js", "css", "json", "svg", "webmanifest", "txt")
    private val SAFE_PATH = Regex("^[A-Za-z0-9_\\-./]+$")

    /** Daftar masalah; kosong berarti spec layak dijalankan. */
    fun validate(spec: AppSpec): List<String> {
        val errors = mutableListOf<String>()
        if (spec.name.isBlank()) errors += "Nama aplikasi kosong."
        if (spec.kind != "web" && spec.kind != "pwa") errors += "Jenis aplikasi harus 'web' atau 'pwa', bukan '${spec.kind}'."
        if (spec.features.isEmpty()) errors += "Tidak ada fitur."
        if (spec.files.isEmpty()) errors += "Tidak ada file."
        if (spec.files.size > MAX_FILES) errors += "Terlalu banyak file (${spec.files.size}); maksimal $MAX_FILES."

        val featureIds = spec.features.map { it.id }
        if (featureIds.any { it.isBlank() }) errors += "Ada fitur tanpa id."
        featureIds.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { errors += "Id fitur ganda: $it." }
        spec.features.filter { it.accept.isEmpty() }.forEach { errors += "Fitur ${it.id} tidak punya kriteria lulus." }

        val paths = spec.files.map { it.path }
        paths.groupBy { it.lowercase() }.filter { it.value.size > 1 }.keys.forEach { errors += "Path ganda: $it." }
        for (p in paths) {
            val ext = p.substringAfterLast('.', "").lowercase()
            when {
                p.isBlank() || !SAFE_PATH.matches(p) || p.contains("..") || p.startsWith("/") ->
                    errors += "Path tidak aman: '$p'."
                p.startsWith(".github/") || p.startsWith("gradle") || p == "gradlew" ->
                    errors += "Path dilarang: '$p'."
                ext !in ALLOWED_EXT -> errors += "Jenis file tidak diizinkan: '$p'."
            }
        }
        if (paths.none { it == "index.html" }) errors += "Harus ada index.html."

        for (f in spec.files) {
            f.features.filter { it !in featureIds }.forEach { errors += "${f.path} merujuk fitur yang tidak ada: $it." }
            f.uses.filter { it !in paths }.forEach { errors += "${f.path} memakai file yang tidak ada: $it." }
            if (f.path in f.uses) errors += "${f.path} memakai dirinya sendiri."
            f.cards.filter { !Cards.known(it) }.forEach { errors += "${f.path} meminta kartu yang tidak dikenal: $it." }
        }
        val domIds = spec.dom.map { it.id }
        if (spec.dom.size > 25) errors += "Kontrak DOM terlalu panjang (${spec.dom.size}); maksimal 25."
        domIds.filter { !Regex("^[A-Za-z][A-Za-z0-9_-]*$").matches(it) }.forEach { errors += "Id DOM tidak valid: '$it'." }
        domIds.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { errors += "Id DOM ganda: $it." }
        spec.dom.filter { it.by.isNotBlank() && (it.by !in paths || !it.by.endsWith(".js")) }.forEach { errors += "Id DOM '${it.id}' diisi oleh file yang tidak ada/bukan .js: '${it.by}'." }
        spec.assets.filter { !Assets.known(it) }.forEach { errors += "Asset tidak dikenal: $it." }
        val covered = spec.files.flatMap { it.features }.toSet()
        featureIds.filter { it.isNotBlank() && it !in covered }.forEach { errors += "Fitur $it tidak punya file." }

        if (errors.isEmpty() && TicketPlanner.order(spec) == null) errors += "Ketergantungan antar file berputar (siklus)."
        return errors
    }
}

/** Tulis spec kembali ke JSON dengan skema yang sama dengan yang dibaca [SpecParser]. */
object SpecJson {
    fun write(spec: AppSpec): String {
        val root = com.google.gson.JsonObject()
        root.add("app", com.google.gson.JsonObject().apply {
            addProperty("name", spec.name); addProperty("kind", spec.kind); addProperty("lang", spec.lang)
        })
        fun arr(items: List<String>) = com.google.gson.JsonArray().apply { items.forEach { add(it) } }
        root.add("features", com.google.gson.JsonArray().apply {
            spec.features.forEach { f ->
                add(com.google.gson.JsonObject().apply {
                    addProperty("id", f.id); addProperty("title", f.title); add("accept", arr(f.accept))
                })
            }
        })
        root.add("files", com.google.gson.JsonArray().apply {
            spec.files.forEach { f ->
                add(com.google.gson.JsonObject().apply {
                    addProperty("path", f.path); add("features", arr(f.features)); add("exports", arr(f.exports))
                    add("uses", arr(f.uses)); add("cards", arr(f.cards))
                })
            }
        })
        root.add("dom", com.google.gson.JsonArray().apply {
            spec.dom.forEach { d ->
                add(com.google.gson.JsonObject().apply { addProperty("id", d.id); addProperty("by", d.by); if (d.lazy) addProperty("lazy", true) })
            }
        })
        root.add("assets", arr(spec.assets))
        root.add("facts", arr(spec.facts))
        root.add("decisions", arr(spec.decisions))
        return root.toString()
    }
}
