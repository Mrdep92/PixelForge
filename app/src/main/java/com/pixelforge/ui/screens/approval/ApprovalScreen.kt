package com.pixelforge.ui.screens.approval

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixelforge.domain.model.TaskStatus
import com.pixelforge.ui.MainViewModel

@Composable
fun ApprovalScreen(vm: MainViewModel) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    val approvals by vm.approvals.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val unfinished by vm.unfinished.collectAsStateWithLifecycle()
    val canResume by vm.canResume.collectAsStateWithLifecycle()
    val p = plan

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A2E)).padding(12.dp)
    ) {
        Text("Approval Gate", color = Color(0xFF00E5FF), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        if (p == null || p.status != TaskStatus.AWAITING_APPROVAL) {
            if (canResume) {
                ReportCard(
                    "⏸ Proses terhenti",
                    "Progres tersimpan. Biasanya karena limit/jaringan LLM atau build GitHub belum selesai." +
                        if (unfinished.isNotEmpty())
                            "\n\nAgent tertunda: " + unfinished.joinToString { it.agentRole.name.lowercase() }
                        else "\n\nTahap build/debug di GitHub belum selesai."
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.resume() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black)
                ) { Text(if (busy) "Sedang berjalan..." else "Lanjutkan") }
            } else {
                Text("Tidak ada rencana yang menunggu persetujuan.", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    ReportCard("Architect — Rencana", p.architectPlan)
                }
                items(approvals, key = { it.id }) { a ->
                    ReportCard(a.agentRole.name.lowercase().replaceFirstChar { it.uppercase() }, a.report)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.approve() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black)
                ) { Text("Approve & Jalankan") }
                OutlinedButton(onClick = { vm.reject() }, enabled = !busy) {
                    Text("Reject", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ReportCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color(0xFFFFAB40), fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(body, color = Color.White, fontSize = 14.sp)
        }
    }
}
