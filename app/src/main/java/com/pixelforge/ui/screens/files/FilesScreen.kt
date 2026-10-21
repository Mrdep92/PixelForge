package com.pixelforge.ui.screens.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.ui.MainViewModel

@Composable
fun FilesScreen(vm: MainViewModel) {
    val files by vm.files.collectAsStateWithLifecycle()
    var openPath by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A2E)).padding(12.dp)
    ) {
        Text("Workspace", color = Color(0xFF00E5FF), style = MaterialTheme.typography.titleLarge)
        Text("${files.size} file • hasil kerja agent", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        if (files.isEmpty()) {
            Text(
                "Belum ada file. Beri perintah coding di tab Chat, setujui di Approvals, " +
                    "dan file buatan agent akan muncul di sini.",
                color = Color.Gray
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(files, key = { it }) { path ->
                    Text(
                        text = "📄 $path",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                content = vm.readFile(path)
                                openPath = path
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                    )
                    HorizontalDivider(color = Color(0xFF16213E))
                }
            }
        }
    }

    val p = openPath
    if (p != null) {
        AlertDialog(
            onDismissRequest = { openPath = null },
            title = { Text(p, fontSize = 14.sp) },
            text = {
                Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(content, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { openPath = null }) { Text("Tutup") } }
        )
    }
}
