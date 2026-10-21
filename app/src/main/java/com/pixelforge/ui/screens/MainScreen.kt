package com.pixelforge.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixelforge.ui.MainViewModel
import com.pixelforge.ui.components.ProjectBar
import com.pixelforge.ui.screens.approval.ApprovalScreen
import com.pixelforge.ui.screens.chat.ChatScreen
import com.pixelforge.ui.screens.files.FilesScreen
import com.pixelforge.ui.screens.settings.SettingsScreen

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val approvals by vm.approvals.collectAsStateWithLifecycle()

    // Rencana baru siap → langsung buka tab Approvals
    LaunchedEffect(approvals.isNotEmpty()) { if (approvals.isNotEmpty()) tab = 1 }

    Scaffold(
        topBar = { ProjectBar(vm) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Chat, "Chat") }, label = { Text("Chat") })
                NavigationBarItem(
                    tab == 1, { tab = 1 },
                    {
                        BadgedBox(badge = { if (approvals.isNotEmpty()) Badge { Text("${approvals.size}") } }) {
                            Icon(Icons.Default.List, "Approvals")
                        }
                    },
                    label = { Text("Approvals") }
                )
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Folder, "Files") }, label = { Text("Files") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Settings, "Settings") }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ChatScreen(vm)
                1 -> ApprovalScreen(vm)
                2 -> FilesScreen(vm)
                3 -> SettingsScreen(vm)
            }
        }
    }
}
