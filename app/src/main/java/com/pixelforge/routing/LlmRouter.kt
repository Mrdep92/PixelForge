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
    val headers: Map<String, String> = emptyMap(),
    /** Kalau true, model tanpa akhiran :free dilewati (mencegah tagihan tak sengaja). */
    val freeOnly: Boolean = false,
    /** Provider milik sendiri / dipercaya pemilik: boleh menerima kode project privat. */
    val trusted: Boolean = false,
    /** Nama model menunjukkan gratis/berbayar (OpenRouter pakai akhiran :free). Provider lain tidak bisa dinilai. */
    val modelsDeclarePricing: Boolean = false,
    /** Batas jatah yang diketahui (untuk meteran). Null = tidak diketahui. */
    val limits: QuotaMeter.Limits? = null,
    /** Jatah berlaku per akun, bukan per model: kena 429 berarti pindah model di provider ini tidak menolong. */
    val limitPerAccount: Boolean = false
)

/** Semua provider gagal (limit / jaringan / key salah). Progres kerja harus disimpan lalu dilanjutkan nanti. */
class LlmExhaustedException(
    message: String,
    val rateLimited: Boolean,
    /** Perkiraan waktu jatah kembali (epoch ms), kalau diketahui dari meteran. */
    val resetAtMs: Long? = null
) : Exception(message)

private class HttpFail(val code: Int, message: String, val retryAfterMs: Long?) : Exception(message)

/**
 * Router LLM: coba provider & model berurutan. Kena limit (429) → tunggu sebentar lalu pindah
 * ke model/provider berikutnya. Kalau semuanya gagal → LlmExhaustedException.
 */
class LlmRouter(private val providers: List<Provider>, private val meter: QuotaMeter? = null) {

    init {
        meter?.let { m -> providers.forEach { p -> p.limits?.let { m.setLimits(p.name, it) } } }
    }

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

    suspend fun chat(systemPrompt: String, userPrompt: String): String =
        chat(listOf(ChatMessage("system", systemPrompt), ChatMessage("user", userPrompt)))

    /** Percakapan multi-putaran (dipakai agent loop). */
    suspend fun chat(rawMessages: List<ChatMessage>): String {
        val messages = rawMessages.map { m -> m.copy(content = m.content?.let { SecretFilter.redact(it) }) }
        if (providers.isEmpty()) {
            throw LlmExhaustedException("Belum ada provider LLM yang diatur di Settings.", false)
        }
        val errors = mutableListOf<String>()
        var rateLimited = false
        var resetAt: Long? = null
        for (p in providers) {
            var skipProvider = false
            for (model in p.models) {
                if (skipProvider) break
                if ("${p.name}/$model" in unusable) continue
                if (p.freeOnly && !FreeModels.isFree(model)) {
                    errors += "${p.name}/$model: dilewati (bukan model gratis)"
                    continue
                }
                var attempt = 0
                while (attempt < 2) {
                    attempt++
                    when (val v = meter?.check(p.name)) {
                        is QuotaMeter.Verdict.DayExhausted -> {
                            errors += "${p.name}: jatah harian habis"
                            rateLimited = true
                            resetAt = minOf(resetAt ?: v.resetAtMs, v.resetAtMs)
                            skipProvider = true
                        }
                        is QuotaMeter.Verdict.Wait -> {
                            if (v.ms <= MAX_WAIT_MS) delay(v.ms + 200L)
                            else {
                                errors += "${p.name}: jatah ditahan ±${v.ms / 1000} detik"
                                rateLimited = true
                                skipProvider = true
                            }
                        }
                        else -> {}
                    }
                    if (skipProvider) break
                    try {
                        return call(p, model, messages)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HttpFail) {
                        errors += "${p.name}/$model: ${e.message}"
                        if (e.code == 429) {
                            rateLimited = true
                            meter?.noteRateLimited(p.name, e.retryAfterMs)
                            val wait = e.retryAfterMs ?: 4000L
                            if (attempt == 1 && wait <= MAX_WAIT_MS) delay(wait)
                            else {
                                if (p.limitPerAccount) skipProvider = true
                                break
                            }
                        } else if (e.code in 400..499 && e.code != 408) {
                            // 403 "hanya untuk agentic harness": model ini tidak akan pernah jalan dari aplikasi → lewati selamanya.
                            if (e.code == 403 && e.message?.contains("agentic", ignoreCase = true) == true) {
                                unusable += "${p.name}/$model"
                            }
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
        val resetNote = resetAt?.let {
            "\nJatah kembali sekitar " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) + "."
        } ?: ""
        throw LlmExhaustedException(
            "Semua provider gagal:\n" + errors.takeLast(5).joinToString("\n") + resetNote,
            rateLimited,
            resetAt
        )
    }

    private suspend fun call(p: Provider, model: String, messages: List<ChatMessage>): String =
        withContext(Dispatchers.IO) {
            val base = p.baseUrl.trim().trimEnd('/')
            val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            meter?.record(p.name)
            val response = client.post(url) {
                if (p.apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${p.apiKey}")
                p.headers.forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(
                    ChatRequest(model = model, messages = messages)
                )
            }
            meter?.observeHeaders(p.name) { response.headers[it] }
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

    private companion object {
        /** Model yang ditolak permanen oleh penyedia selama proses aplikasi berjalan. */
        val unusable: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        const val MAX_WAIT_MS = 20_000L
    }
}
