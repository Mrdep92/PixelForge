package com.pixelforge.routing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.google.gson.Gson
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Penyedia tambahan di kolam: titik akhir kompatibel OpenAI + key milik pengguna sendiri (BYOK). */
data class ProviderConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val enabled: Boolean = true
)

object ProviderPresets {
    data class Preset(val name: String, val baseUrl: String)

    /** Alamat bawaan; semuanya bisa diedit karena penyedia bisa mengubahnya. */
    val all = listOf(
        Preset("Groq", "https://api.groq.com/openai/v1"),
        Preset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
        Preset("GitHub Models", "https://models.github.ai/inference"),
        Preset("Cerebras", "https://api.cerebras.ai/v1"),
        Preset("Mistral", "https://api.mistral.ai/v1"),
        Preset("Lainnya", "")
    )

    /** Batas yang diketahui untuk penyedia tertentu. Angka dari akun pengguna; server tetap yang menentukan. */
    data class Known(val limits: QuotaMeter.Limits?, val tpm: Int?)

    fun known(name: String, model: String): Known = when {
        // Gemini: jatah harian direset tengah malam Pasifik. 20/hari terkonfirmasi untuk gemini-3.6-flash (pesan 429 server).
        name.contains("gemini", ignoreCase = true) -> Known(
            QuotaMeter.Limits(null, if (model.contains("3.6-flash")) 20 else null, "America/Los_Angeles"), null
        )
        // Groq: 8000 TPM untuk gpt-oss-120b di akun pengguna; model lain bisa berbeda.
        name.contains("groq", ignoreCase = true) -> Known(null, if (model.contains("gpt-oss-120b")) 8000 else null)
        else -> Known(null, null)
    }

    private val gson = Gson()
    private val listType = object : TypeToken<List<ProviderConfig>>() {}.type

    fun parse(json: String): List<ProviderConfig> =
        runCatching { gson.fromJson<List<ProviderConfig>>(json, listType) ?: emptyList() }.getOrDefault(emptyList())

    fun toJson(list: List<ProviderConfig>): String = gson.toJson(list)

    /** Ganti entri bernama sama, atau tambahkan di akhir. */
    fun upsert(list: List<ProviderConfig>, item: ProviderConfig): List<ProviderConfig> {
        val i = list.indexOfFirst { it.name.equals(item.name, ignoreCase = true) }
        return if (i >= 0) list.toMutableList().also { it[i] = item } else list + item
    }

    /** Daftar id model dari balasan /models (bentuk {data:[...]} atau array langsung). */
    fun parseModelIds(json: String): List<String> {
        val root = JsonParser.parseString(json)
        val arr: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> (root as JsonObject).getAsJsonArray("data") ?: return emptyList()
            else -> return emptyList()
        }
        return arr.mapNotNull { runCatching { it.asJsonObject.get("id").asString }.getOrNull() }
            .distinct().sorted().take(60)
    }

    /** Ambil daftar model dari penyedia supaya pengguna tinggal memilih. Kosong bila gagal. */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val client = HttpClient(Android) { engine { connectTimeout = 15_000; socketTimeout = 30_000 } }
        try {
            val base = baseUrl.trim().trimEnd('/')
            val urls = buildList {
                add("$base/models")
                if (base.contains("models.github.ai")) add("https://models.github.ai/catalog/models")
            }
            for (u in urls) {
                val r = client.get(u) {
                    if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    header(HttpHeaders.UserAgent, "PixelForge")
                }
                if (r.status.isSuccess()) {
                    val ids = runCatching { parseModelIds(r.bodyAsText()) }.getOrDefault(emptyList())
                    if (ids.isNotEmpty()) return@withContext ids
                }
            }
            emptyList()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        } finally {
            client.close()
        }
    }
}
