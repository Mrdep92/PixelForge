package com.pixelforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.ui.MainViewModel

/** Bar atas: pilih / buat project dan atur repo GitHub-nya. */
@Composable
fun ProjectBar(vm: MainViewModel) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val currentId by vm.projectId.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var menu by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    val current = projects.firstOrNull { it.id == currentId }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.clickable { menu = true }) {
                Text(
                    "📁 ${current?.name ?: "Project"} ▾",
                    color = Color(0xFF00E5FF),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (meta.repo.isBlank()) "belum ada repo GitHub" else "${meta.repo} • ${meta.branch}",
                    color = if (meta.repo.isBlank()) Color(0xFFFFAB40) else Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                projects.forEach { p ->
                    DropdownMenuItem(
                        text = { Text((if (p.id == currentId) "✓ " else "") + p.name) },
                        enabled = !busy,
                        onClick = { menu = false; vm.switchProject(p.id) }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("＋ Project baru") },
                    enabled = !busy,
                    onClick = { menu = false; showNew = true }
                )
            }
        }
        TextButton(
            onClick = { showEdit = true },
            enabled = !busy,
            colors = ButtonDefaults.textButtonColors(
                contentColor = Color(0xFF00E5FF),
                disabledContentColor = Color.Gray
            )
        ) { Text("✎ Repo") }
    }

    if (showNew) NewProjectDialog(
        onDismiss = { showNew = false },
        onCreate = { name, repo, auto, priv ->
            showNew = false
            vm.createProject(name, repo, auto, priv)
        }
    )
    if (showEdit) EditRepoDialog(
        repo = meta.repo,
        branch = meta.branch,
        onDismiss = { showEdit = false },
        onSave = { r, b -> showEdit = false; vm.updateRepo(r, b) },
        onFetchRepos = { cb -> vm.fetchRepos(cb) },
        onCreateRepo = { name, priv -> showEdit = false; vm.createRepo(name, priv) }
    )
}

@Composable
private fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(true) }
    var priv by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project baru") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nama project") }, singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = auto, onCheckedChange = { auto = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Agent Repo membuat repo otomatis", fontSize = 13.sp)
                }
                OutlinedTextField(
                    value = repo, onValueChange = { repo = it },
                    label = { Text(if (auto) "Nama repo baru (opsional)" else "Repo yang sudah ada") },
                    placeholder = { Text(if (auto) "kosong = dari nama project" else "username/nama-repo") },
                    singleLine = true
                )
                if (auto) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = priv, onCheckedChange = { priv = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Repo privat", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, repo, auto, priv) }, enabled = name.isNotBlank()) { Text("Buat") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
private fun EditRepoDialog(
    repo: String,
    branch: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onFetchRepos: ((List<com.pixelforge.github.RepoInfo>, String?) -> Unit) -> Unit,
    onCreateRepo: (String, Boolean) -> Unit
) {
    var repos by remember { mutableStateOf<List<com.pixelforge.github.RepoInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var r by remember { mutableStateOf(repo) }
    var b by remember { mutableStateOf(branch) }
    var newName by remember { mutableStateOf("") }
    var priv by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repo project ini") },
        text = {
            Column {
                TextButton(enabled = !loading, onClick = {
                    loading = true; err = null
                    onFetchRepos { list, e -> repos = list; err = e; loading = false }
                }) { Text(if (loading) "Mengambil..." else "📂 Pilih dari repo GitHub-mu") }
                err?.let { Text(it, color = Color(0xFFFFAB40), fontSize = 12.sp) }
                if (repos.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        repos.forEach { ri ->
                            TextButton(onClick = { r = ri.fullName; b = ri.defaultBranch; repos = emptyList() }) {
                                Text((if (ri.isPrivate) "🔒 " else "") + ri.fullName, fontSize = 12.sp)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = r, onValueChange = { r = it },
                    label = { Text("Repo (username/nama-repo)") }, singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = b, onValueChange = { b = it },
                    label = { Text("Branch") }, singleLine = true
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Atau minta agent Repo membuat repo baru:", fontSize = 13.sp)
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("Nama repo baru (opsional)") }, singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = priv, onCheckedChange = { priv = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Repo privat", fontSize = 13.sp)
                }
                TextButton(onClick = { onCreateRepo(newName, priv) }) { Text("🏗 Buat repo baru") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(r, b) }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}
