package com.pixelforge.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.ui.MainViewModel
import com.pixelforge.ui.SettingsForm

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF00E5FF),
    unfocusedBorderColor = Color.Gray,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(text, color = Color(0xFF00E5FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    hasSaved: Boolean = false,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier
) {
    Spacer(Modifier.height(12.dp))
    Text(label, color = Color.White, fontSize = 14.sp)
    if (hasSaved) Text("Tersimpan ✓ (kosongkan jika tidak ingin mengganti)", color = Color(0xFF00E676), fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        placeholder = { Text(placeholder) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = fieldColors()
    )
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()

    var apiKey by remember(s) { mutableStateOf("") }
    var model by remember(s) { mutableStateOf(s.model) }
    var omniUrl by remember(s) { mutableStateOf(s.omniUrl) }
    var omniKey by remember(s) { mutableStateOf("") }
    var omniModel by remember(s) { mutableStateOf(s.omniModel) }
    var omniFirst by remember(s) { mutableStateOf(s.omniFirst) }
    val meta by vm.meta.collectAsStateWithLifecycle()
    var omniTrusted by remember(s) { mutableStateOf(s.omniTrusted) }
    var allowPaid by remember(s) { mutableStateOf(s.allowPaid) }
    var orCredits by remember(s) { mutableStateOf(s.orCredits) }
    val quota by vm.quota.collectAsStateWithLifecycle()
    var ghToken by remember(s) { mutableStateOf("") }
    var bible by remember(s) { mutableStateOf(s.bible) }
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

        SectionTitle("OpenRouter")
        Field("API Key", apiKey, { apiKey = it; saved = false }, "sk-or-...", secret = true, hasSaved = s.hasKey)
        Field("Model (kosong = pilih otomatis dari model gratis)", model, { model = it; saved = false }, "contoh: vendor/nama-model:free")
        Text(
            "Daftar model gratis diambil langsung dari OpenRouter. Model tanpa akhiran :free dilewati supaya saldo tidak terpakai.",
            color = Color.Gray, fontSize = 12.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = allowPaid, onCheckedChange = { allowPaid = it; saved = false })
            Spacer(Modifier.width(12.dp))
            Text("Izinkan model berbayar (saldo bisa terpotong)", color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = orCredits, onCheckedChange = { orCredits = it; saved = false })
            Spacer(Modifier.width(12.dp))
            Text("Pernah beli kredit OpenRouter (≥ \$10): jatah gratis 1.000/hari", color = Color.White)
        }

        SectionTitle("Jatah per provider")
        if (quota.isEmpty()) {
            Text("Belum ada permintaan. Meteran muncul setelah agent memakai model.", color = Color.Gray, fontSize = 12.sp)
        }
        quota.forEach { q ->
            val dayText = if (q.perDay != null) "${q.usedDay}/${q.perDay} hari ini" else "${q.usedDay} hari ini"
            val minText = if (q.perMinute != null) "${q.usedMinute}/${q.perMinute} per menit" else "${q.usedMinute} per menit"
            Text(q.provider, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("$dayText • $minText", color = Color.Gray, fontSize = 12.sp)
            q.reportedRemaining?.let { Text("Sisa menurut server: $it", color = Color.Gray, fontSize = 12.sp) }
            if (q.blockedUntil != null) {
                Text("⏸ Ditahan sampai ±" + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(q.blockedUntil)), color = Color(0xFFFFB300), fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            "Hitungan dibuat aplikasi dan dikoreksi dari header server bila ada. Angka batas gratis bisa berubah di sisi penyedia.",
            color = Color.Gray, fontSize = 12.sp
        )

        SectionTitle("OmniRoute / endpoint kompatibel OpenAI")
        Text(
            "Dipakai sebagai cadangan otomatis kalau OpenRouter limit atau error (atau jadi utama bila dicentang).",
            color = Color.Gray, fontSize = 12.sp
        )
        Field("Base URL", omniUrl, { omniUrl = it; saved = false }, "http://192.168.1.10:20128/v1")
        Field("API Key (boleh kosong untuk server lokal)", omniKey, { omniKey = it; saved = false }, secret = true, hasSaved = s.hasOmniKey)
        Field("Model", omniModel, { omniModel = it; saved = false }, "kosong = auto")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = omniFirst, onCheckedChange = { omniFirst = it; saved = false })
            Spacer(Modifier.width(12.dp))
            Text("Pakai OmniRoute lebih dulu", color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = omniTrusted, onCheckedChange = { omniTrusted = it; saved = false })
            Spacer(Modifier.width(12.dp))
            Text("OmniRoute tepercaya (boleh untuk project privat)", color = Color.White)
        }

        SectionTitle("GitHub (wadah kode)")
        Text(
            "Agent menyimpan file ke repo project, GitHub Actions membuild, lalu Debugger memperbaiki error otomatis.\n\n" +
                "Agar setiap project bisa punya repo sendiri, token harus mencakup SEMUA repo, bukan satu repo saja. Dua pilihan:\n" +
                "• Classic (paling mudah): github.com/settings/tokens → Generate new token (classic) → centang `repo` dan `workflow`.\n" +
                "• Fine-grained: pilih All repositories, lalu izin Contents (RW), Workflows (RW), Actions (Read), Administration (RW).",
            color = Color.Gray, fontSize = 12.sp
        )
        Field("Token", ghToken, { ghToken = it; saved = false }, "github_pat_...", secret = true, hasSaved = s.hasGhToken)
        Text(
            "Repo & branch diatur per project lewat tombol ✎ Repo di bar atas.",
            color = Color.Gray, fontSize = 12.sp
        )

        SectionTitle("Privasi project aktif")
        if (meta.isPrivate) {
            Text(
                "Repo ini PRIVAT. Secara bawaan kodenya tidak dikirim ke model gratis, karena sebagian penyedia model gratis " +
                    "boleh memakai data untuk memperbaiki produk mereka (cek kebijakan tiap penyedia).",
                color = Color.Gray, fontSize = 12.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = meta.freeConsent == true, onCheckedChange = { vm.setFreeConsent(it) })
                Spacer(Modifier.width(12.dp))
                Text("Izinkan kirim ke provider gratis", color = Color.White)
            }
        } else {
            Text(
                if (meta.repo.isBlank()) "Belum ada repo. Project dianggap publik."
                else "Repo ini PUBLIK, semua provider boleh dipakai.",
                color = Color.Gray, fontSize = 12.sp
            )
        }
        Text(
            "Semua teks yang dikirim ke model otomatis disaring: API key, token, dan kunci pribadi diganti [REDACTED].",
            color = Color.Gray, fontSize = 12.sp
        )

        SectionTitle("Project Bible (project aktif)")
        Text("Selalu dikirim ke semua agent", color = Color.Gray, fontSize = 12.sp)
        Field(
            "Isi Bible", bible, { bible = it; saved = false },
            singleLine = false, modifier = Modifier.heightIn(min = 160.dp)
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                vm.saveSettings(
                    SettingsForm(apiKey, model, omniUrl, omniKey, omniModel, omniFirst, ghToken, bible, allowPaid, omniTrusted, orCredits)
                )
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
        ) { Text("Simpan") }
        if (saved) Text("Tersimpan ✓", color = Color(0xFF00E676))

        Spacer(Modifier.height(24.dp))
        Text("PixelForge v0.4.2-alpha\nPersonal use • Multi-agent living office", color = Color.Gray)
    }
}
