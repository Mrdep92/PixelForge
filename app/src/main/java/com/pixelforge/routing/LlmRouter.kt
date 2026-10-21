package com.pixelforge.routing

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Satu penyedia LLM yang kompatibel dengan format OpenAI (/chat/completions). */
data class Provider(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String>,
    val headers: Map<String, String> = emptyMap()
)

/** Semua provider gagal (limit / jaringan / key salah). Progres kerja harus disimpan lalu dilanjutkan nanti. */
class LlmExhaustedException(message: String, val rateLimited: Boolean) : Exception(message)

private class HttpFail(val code: Int, message: String, val retryAfterMs: Long?) : Exception(message)

/**
 * Router LLM: coba provider & model berurutan. Kena limit (429) → tunggu sebentar lalu pindah
 * ke model/provider berikutnya. Kalau semuanya gagal → LlmExhaustedException.
 */
class LlmRouter(private val providers: List<Provider>) {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { gson() }
        engine {
            connectTimeout = 30_000
            socketTimeout = 120_000
        }
    }

    data class ChatMessage(val role: String, val content: String?)
    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.7,
        val max_tokens: Int = 4096
    )
    data class Choice(val message: ChatMessage?, val finish_reason: String? = null)
    data class ChatResponse(val choices: List<Choice>?)

    suspend fun chat(systemPrompt: String, userPrompt: String): String {
        if (providers.isEmpty()) {
            throw LlmExhaustedException("Belum ada provider LLM yang diatur di Settings.", false)
        }
        val errors = mutableListOf<String>()
        var rateLimited = false
        for (p in providers) {
            for (model in p.models) {
                var attempt = 0
                while (attempt < 2) {
                    attempt++
                    try {
                        return call(p, model, systemPrompt, userPrompt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HttpFail) {
                        errors += "${p.name}/$model: ${e.message}"
                        if (e.code == 429) {
                            rateLimited = true
                            val wait = e.retryAfterMs ?: 4000L
                            if (attempt == 1 && wait <= 20_000L) delay(wait) else break
                        } else if (e.code in 400..499 && e.code != 408) {
                            break // key salah / saldo habis / model tidak ada → langsung ke berikutnya
                        } else {
                            delay(2000L * attempt)
                        }
                    } catch (e: Exception) {
                        errors += "${p.name}/$model: ${e.message ?: e.javaClass.simpleName}"
                        delay(1500L * attempt)
                    }
                }
            }
        }
        throw LlmExhaustedException(
            "Semua provider gagal:\n" + errors.takeLast(5).joinToString("\n"),
            rateLimited
        )
    }

    private suspend fun call(p: Provider, model: String, system: String, user: String): String =
        withContext(Dispatchers.IO) {
            val base = p.baseUrl.trim().trimEnd('/')
            val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            val response = client.post(url) {
                if (p.apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${p.apiKey}")
                p.headers.forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        model = model,
                        messages = listOf(ChatMessage("system", system), ChatMessage("user", user))
                    )
                )
            }
            if (!response.status.isSuccess()) {
                val retry = response.headers["Retry-After"]?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                throw HttpFail(
                    response.status.value,
                    "HTTP ${response.status.value}: ${response.bodyAsText().take(160)}",
                    retry
                )
            }
            val choice = response.body<ChatResponse>().choices?.firstOrNull()
            choice?.message?.content?.takeIf { it.isNotBlank() }
                ?: error("respons kosong (finish=${choice?.finish_reason ?: "?"})")
        }

    fun close() = client.close()
}
