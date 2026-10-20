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
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * OpenRouter client dengan auto-fallback: kalau model pertama gagal
 * (rate limit, error server, respons kosong) otomatis coba model berikutnya.
 */
class OpenRouterClient(
    private val apiKey: String,
    private val models: List<String>
) {
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
        val max_tokens: Int = 2048
    )
    data class Choice(val message: ChatMessage?)
    data class ChatResponse(val choices: List<Choice>?)

    suspend fun chat(systemPrompt: String, userPrompt: String): String {
        var lastError = "tidak ada model"
        for (model in models) {
            try {
                return call(model, systemPrompt, userPrompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "$model → ${e.message ?: e.javaClass.simpleName}"
            }
        }
        error("Semua model gagal. Terakhir: $lastError")
    }

    private suspend fun call(model: String, system: String, user: String): String =
        withContext(Dispatchers.IO) {
            val response = client.post("https://openrouter.ai/api/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header("HTTP-Referer", "https://pixelforge.app")
                header("X-Title", "PixelForge")
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        model = model,
                        messages = listOf(ChatMessage("system", system), ChatMessage("user", user))
                    )
                )
            }
            if (!response.status.isSuccess()) {
                error("HTTP ${response.status.value}: ${response.bodyAsText().take(160)}")
            }
            response.body<ChatResponse>().choices?.firstOrNull()?.message?.content
                ?.takeIf { it.isNotBlank() }
                ?: error("respons kosong")
        }

    fun close() = client.close()
}
