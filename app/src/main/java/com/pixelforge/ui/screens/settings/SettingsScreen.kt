package com.pixelforge.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.ui.MainViewModel

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF00E5FF),
    unfocusedBorderColor = Color.Gray,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val savedModel by vm.model.collectAsStateWithLifecycle()
    val savedBible by vm.bible.collectAsStateWithLifecycle()

    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var model by remember(savedModel) { mutableStateOf(savedModel) }
    var bible by remember(savedBible) { mutableStateOf(savedBible) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .imePadding()
    ) {
        Text("Settings", color = Color(0xFF00E5FF), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))

        Text("OpenRouter API Key", color = Color.White)
        if (hasKey) Text("Tersimpan ✓ (kosongkan jika tidak ingin mengganti)", color = Color(0xFF00E676))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; saved = false },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("sk-or-...") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            colors = fieldColors()
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = showKey, onCheckedChange = { showKey = it })
            Text("Tampilkan key", color = Color.White)
        }

        Spacer(Modifier.height(16.dp))
        Text("Model (cadangan otomatis: openrouter/auto)", color = Color.White)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; saved = false },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = fieldColors()
        )

        Spacer(Modifier.height(16.dp))
        Text("Project Bible (selalu dikirim ke semua agent)", color = Color.White)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = bible,
            onValueChange = { bible = it; saved = false },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            colors = fieldColors()
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { vm.saveSettings(apiKey, model, bible); apiKey = ""; saved = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
        ) { Text("Simpan") }
        if (saved) Text("Tersimpan ✓", color = Color(0xFF00E676))

        Spacer(Modifier.height(24.dp))
        Text("PixelForge v0.1.0-alpha\nPersonal use • Multi-agent living office", color = Color.Gray)
    }
}
