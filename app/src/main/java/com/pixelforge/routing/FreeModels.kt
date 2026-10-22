package com.pixelforge.routing

import com.google.gson.JsonParser
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Katalog model OpenRouter yang benar-benar gratis. Daftar model gratis berubah-ubah, jadi diambil
 * langsung dari OpenRouter lalu disimpan sementara; yang ditulis di sini hanya cadangan terakhir.
 */
object FreeModels {

    /** Cadangan kalau katalog belum pernah berhasil diambil. */
    val FALLBACK = listOf("minimax/minimax-m3.1-flash:free", "nvidia/nemotron-3.5-super-vl:free")

    const val MAX_MODELS = 6
    private const val MIN_CONTEXT = 32_000

    /** `openrouter/auto` dan model tanpa akhiran :free bisa ditagih, jadi tidak dianggap gratis. */
    fun isFree(modelId: String): Boolean = modelId.trim().endsWith(":free")

    /** `auto` (atau kosong) = tidak ada pilihan khusus dari pengguna. */
    fun isAutoChoice(modelId: String): Boolean {
        val m = modelId.trim()
        return m.isEmpty() || m == "openrouter/auto" || m == "auto"
    }

    /**
     * Urutan model yang dicoba: pilihan pengguna dulu (kalau valid), lalu katalog gratis.
     * Kalau [allowPaid] mati, model berbayar dibuang di sini; router juga menjaga hal yang sama.
     */
    fun candidates(userChoice: String, catalog: List<String>, allowPaid: Boolean): List<String> {
        val base = catalog.ifEmpty { FALLBACK }
        val chosen = userChoice.trim().takeUnless { isAutoChoice(it) }
        val all = (listOfNotNull(chosen) + base).distinct()
        return if (allowPaid) all else all.filter { isFree(it) }
    }

    /** Ambil model :free dari katalog publik OpenRouter (tanpa API key). */
    fun parseCatalog(json: String): List<String> {
        val data = JsonParser.parseString(json).asJsonObject.getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el.asJsonObject
            val id = o.get("id")?.asString ?: return@mapNotNull null
            if (!isFree(id)) return@mapNotNull null
            val pricing = o.getAsJsonObject("pricing")
            val promptFree = pricing?.get("prompt")?.asString?.toDoubleOrNull() == 0.0
            val completionFree = pricing?.get("completion")?.asString?.toDoubleOrNull() == 0.0
            if (!promptFree || !completionFree) return@mapNotNull null
            val ctx = o.get("context_length")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            if (ctx < MIN_CONTEXT) return@mapNotNull null
            val outText = o.getAsJsonObject("architecture")
                ?.getAsJsonArray("output_modalities")
                ?.map { it.asString }
                ?.let { "text" in it } ?: true
            if (!outText) return@mapNotNull null
            id to ctx
        }.sortedByDescending { it.second }.map { it.first }.take(MAX_MODELS)
    }

    suspend fun fetch(): List<String> = withContext(Dispatchers.IO) {
        val client = HttpClient(Android) { engine { connectTimeout = 15_000; socketTimeout = 30_000 } }
        try {
            val r = client.get("https://openrouter.ai/api/v1/models") {
                header(HttpHeaders.UserAgent, "PixelForge")
            }
            if (!r.status.isSuccess()) emptyList() else parseCatalog(r.bodyAsText())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        } finally {
            client.close()
        }
    }
}
