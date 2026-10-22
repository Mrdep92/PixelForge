package com.pixelforge.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import com.pixelforge.domain.model.Agent
import com.pixelforge.domain.model.AgentStatus
import com.pixelforge.ui.MainViewModel
import com.pixelforge.ui.components.MarkdownText

@Composable
fun ChatScreen(vm: MainViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val repoHelp by vm.repoHelp.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(8.dp)
            .imePadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Chat",
                color = Color(0xFF00E5FF),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(8.dp).weight(1f)
            )
            TextButton(
                onClick = { confirmClear = true },
                enabled = messages.isNotEmpty() && !busy,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFFFFAB40),
                    disabledContentColor = Color.Gray
                )
            ) { Text("🗑 Bersihkan") }
        }
        TeamStrip(agents)
        Spacer(Modifier.height(6.dp))

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Belum ada pesan.\nBeri perintah di bawah — tim akan menyusun rencana dan menunggu persetujuanmu.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        } else LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages, key = { it.id }) { m ->
                val isUser = m.type == "USER"
                val label = when (m.type) {
                    "AGENT" -> (m.agentId ?: "AGENT").lowercase().replaceFirstChar { it.uppercase() }
                    "SYSTEM" -> "System"
                    else -> null
                }
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .background(if (isUser) Color(0xFF7C4DFF) else Color(0xFF16213E), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        if (label != null) Text(label, color = Color(0xFFFFAB40), fontSize = 12.sp)
                        SelectionContainer { MarkdownText(m.content) }
                    }
                }
            }
        }

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Color(0xFF00E5FF))
            Text("Agent sedang bekerja...", color = Color.Gray, fontSize = 12.sp)
        }

        repoHelp?.let { help ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .background(Color(0xFF16213E), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text("Hubungkan repo \"${help.name}\"", color = Color(0xFFFFAB40), fontSize = 13.sp)
                Text(
                    "1) Buka GitHub dan buat repo dengan nama itu. 2) Kembali ke sini, tekan Sudah dibuat.",
                    color = Color.White, fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val vis = if (help.isPrivate) "private" else "public"
                        uriHandler.openUri("https://github.com/new?name=${help.name}&visibility=$vis")
                    }) { Text("Buka GitHub", color = Color(0xFF00E5FF)) }
                    TextButton(enabled = !busy, onClick = { vm.linkCreatedRepo() }) {
                        Text("Sudah dibuat", color = Color(0xFF00E676))
                    }
                    TextButton(onClick = { vm.dismissRepoHelp() }) { Text("Tutup", color = Color.Gray) }
                }
            }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Bersihkan chat?") },
                text = { Text("Semua pesan di project ini dihapus. Rencana, file, dan repo tidak ikut terhapus.") },
                confirmButton = {
                    TextButton(onClick = { confirmClear = false; vm.clearChat() }) { Text("Hapus") }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Batal") } }
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Beri perintah...") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = !busy && input.isNotBlank(),
                onClick = { vm.send(input); input = "" }
            ) {
                Icon(Icons.Default.Send, "Send", tint = if (busy) Color.Gray else Color(0xFF00E5FF))
            }
        }
    }
}

private fun statusLabel(s: AgentStatus): String = when (s) {
    AgentStatus.IDLE -> "siaga"
    AgentStatus.THINKING -> "berpikir"
    AgentStatus.REPORTING -> "lapor"
    AgentStatus.WORKING -> "bekerja"
    AgentStatus.WAITING_APPROVAL -> "tunggu ACC"
    AgentStatus.PAUSED -> "tertunda"
    AgentStatus.DONE -> "selesai"
}

private fun statusColor(s: AgentStatus): Color = when (s) {
    AgentStatus.IDLE -> Color(0xFF607D8B)
    AgentStatus.THINKING, AgentStatus.REPORTING -> Color(0xFF00E5FF)
    AgentStatus.WORKING -> Color(0xFF00E676)
    AgentStatus.WAITING_APPROVAL -> Color(0xFFFFAB40)
    AgentStatus.PAUSED -> Color(0xFFFF5252)
    AgentStatus.DONE -> Color(0xFF69F0AE)
}

/** Status tim secara langsung: siapa sedang apa. */
@Composable
private fun TeamStrip(agents: List<Agent>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        agents.forEach { a ->
            Row(
                modifier = Modifier
                    .background(Color(0xFF16213E), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(statusColor(a.status), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${a.name} • ${a.bubbleText ?: statusLabel(a.status)}",
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp)
                )
            }
        }
    }
}
