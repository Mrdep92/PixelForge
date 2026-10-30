package com.pixelforge.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.util.Collections
import kotlin.coroutines.resume

/**
 * Sandbox: WebView tersembunyi (tidak ditampilkan). File project disajikan dari penyimpanan aplikasi,
 * semua permintaan ke luar (internet) diblokir dan dicatat sebagai galat, lalu skrip uji dijalankan.
 */
class SandboxRunner(private val context: Context, private val readFile: (String) -> String?) : Sandbox {

    companion object {
        const val HOST = "pixelforge.local"
        private const val BASE = "https://$HOST/"

        private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json", "webmanifest" -> "application/json"
            "svg" -> "image/svg+xml"
            else -> "text/plain"
        }

        private fun empty(code: Int, reason: String) =
            WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun run(plan: TestPlan, timeoutMs: Long): SandboxReport = withContext(Dispatchers.Main) {
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val script = SandboxScript.build(plan)
        var view: WebView? = null
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine<SandboxReport> { cont ->
                    val wv = WebView(context)
                    view = wv
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.allowFileAccess = false
                    wv.settings.allowContentAccess = false
                    var started = false
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                errors += "${m.message()} (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})"
                            }
                            return true
                        }
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
                            val url = req.url
                            if (url.host != HOST) {
                                errors += "diblokir (sandbox tanpa internet): ${url.host}"
                                return empty(403, "Forbidden")
                            }
                            val path = url.path.orEmpty().trimStart('/').ifEmpty { "index.html" }
                            val body = readFile(path)
                            if (body == null) {
                                if (path != "favicon.ico") errors += "404: file tidak ada: $path"
                                return empty(404, "Not Found")
                            }
                            return WebResourceResponse(mimeOf(path), "utf-8", ByteArrayInputStream(body.toByteArray()))
                        }

                        override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                            if (req.isForMainFrame) errors += "gagal memuat halaman: ${err.description}"
                        }

                        override fun onPageFinished(v: WebView, url: String?) {
                            if (started) return
                            started = true
                            v.postDelayed({
                                v.evaluateJavascript(script) { raw ->
                                    val inner = runCatching { Gson().fromJson(raw, String::class.java) }.getOrNull()
                                    val results = inner?.let { SandboxReports.parseResults(it) }
                                    if (cont.isActive) {
                                        cont.resume(
                                            SandboxReport(
                                                loaded = true,
                                                consoleErrors = synchronized(errors) { errors.toList() },
                                                results = results.orEmpty(),
                                                harnessError = if (results == null) "skrip uji tidak menghasilkan hasil" else null
                                            )
                                        )
                                    }
                                }
                            }, 300)
                        }
                    }
                    wv.loadUrl(BASE + "index.html")
                }
            }
        } catch (e: TimeoutCancellationException) {
            SandboxReport(false, synchronized(errors) { errors.toList() }, emptyList(), "waktu uji habis (${timeoutMs / 1000} dtk): kode kemungkinan berputar tanpa akhir")
        } finally {
            view?.destroy()
        }
    }
}
