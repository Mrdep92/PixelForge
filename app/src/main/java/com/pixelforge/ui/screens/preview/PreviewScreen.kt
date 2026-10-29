package com.pixelforge.ui.screens.preview

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.ui.MainViewModel
import java.io.ByteArrayInputStream

private const val BASE = "https://pixelforge.local/"

private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html"
    "js", "mjs" -> "application/javascript"
    "css" -> "text/css"
    "json", "webmanifest" -> "application/json"
    "svg" -> "image/svg+xml"
    else -> "text/plain"
}

/** Pratinjau web/PWA: file project disajikan dari penyimpanan aplikasi; galat konsol ditampilkan. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(vm: MainViewModel) {
    val files by vm.files.collectAsStateWithLifecycle()
    var reload by remember { mutableIntStateOf(0) }
    val errors = remember { mutableStateListOf<String>() }
    val hasIndex = "index.html" in files

    Column(Modifier.fillMaxSize().background(Color(0xFF1A1A2E)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Preview", color = Color(0xFF00E5FF), fontSize = 20.sp, modifier = Modifier.weight(1f).padding(8.dp))
            Button(onClick = { errors.clear(); reload++ }, enabled = hasIndex) { Text("Muat ulang") }
        }
        if (!hasIndex) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Belum ada index.html. Selesaikan pembuatan di tab Chat, lalu buka lagi.",
                    color = Color.Gray, fontSize = 13.sp
                )
            }
        } else {
            key(reload, files.size) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        errors += "${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})"
                                    }
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val url = request.url
                                    if (url.host != "pixelforge.local") return null
                                    val path = url.path.orEmpty().trimStart('/').ifEmpty { "index.html" }
                                    val body = vm.readProjectFile(path)
                                        ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                    return WebResourceResponse(mimeOf(path), "utf-8", ByteArrayInputStream(body.toByteArray()))
                                }
                            }
                            loadUrl(BASE + "index.html")
                        }
                    }
                )
            }
        }
        if (errors.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()).background(Color(0xFF3B1F1F)).padding(8.dp)) {
                Text("Galat konsol:", color = Color(0xFFFF8A80), fontSize = 12.sp)
                errors.take(10).forEach { Text(it, color = Color.White, fontSize = 11.sp) }
            }
        }
    }
}
