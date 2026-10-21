package com.pixelforge.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.ui.MainViewModel
import com.pixelforge.ui.components.MarkdownText

@Composable
fun ChatScreen(vm: MainViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
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
        Text("Project Chat", color = Color(0xFF00E5FF), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
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
