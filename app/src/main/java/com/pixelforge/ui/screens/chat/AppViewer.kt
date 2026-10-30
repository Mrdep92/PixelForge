package com.pixelforge.ui.screens.chat

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream

private const val HOST = "pixelforge.local"

private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html"
    "js", "mjs" -> "application/javascript"
    "css" -> "text/css"
    "json", "webmanifest" -> "application/json"
    "svg" -> "image/svg+xml"
    else -> "text/plain"
}

/** Menjalankan aplikasi hasil buatan layar penuh. File dibaca dari folder project; hanya alamat lokal yang dilayani. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppViewer(readFile: (String) -> String?, onClose: () -> Unit) {
    val errors = remember { mutableStateListOf<String>() }
    var reload by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1A1A2E)).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Aplikasi", color = Color(0xFF00E5FF), fontSize = 18.sp, modifier = Modifier.weight(1f).padding(12.dp))
                TextButton(onClick = { errors.clear(); reload++ }) { Text("Muat ulang", color = Color(0xFF00E5FF)) }
                TextButton(onClick = onClose) { Text("Tutup", color = Color(0xFFFFAB40)) }
            }
            key(reload) {
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
                                        errors += "${m.message()} (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})"
                                    }
                                    return true
                                }

                                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                                    callback.invoke(origin, true, false)
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val url = request.url
                                    if (url.host != HOST) {
                                        return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                    }
                                    val path = url.path.orEmpty().trimStart('/').ifEmpty { "index.html" }
                                    val body = readFile(path)
                                        ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                    return WebResourceResponse(mimeOf(path), "utf-8", ByteArrayInputStream(body.toByteArray()))
                                }
                            }
                            loadUrl("https://$HOST/index.html")
                        }
                    }
                )
            }
            if (errors.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().heightIn(max = 100.dp).background(Color(0xFF3B1F1F)).padding(8.dp)) {
                    Text("Galat konsol:", color = Color(0xFFFF8A80), fontSize = 12.sp)
                    errors.take(4).forEach { Text(it, color = Color.White, fontSize = 11.sp) }
                }
            }
        }
    }
}
