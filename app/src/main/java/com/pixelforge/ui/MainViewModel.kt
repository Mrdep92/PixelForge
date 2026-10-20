package com.pixelforge.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.PixelForgeApp
import com.pixelforge.core.AgentForegroundService
import com.pixelforge.data.local.entity.MessageEntity
import com.pixelforge.data.local.entity.ProjectBibleEntity
import com.pixelforge.data.local.entity.ProjectEntity
import com.pixelforge.agents.AgentOrchestrator
import com.pixelforge.routing.OpenRouterClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val pf = app as PixelForgeApp
    private val db = pf.db
    private val store = pf.store
    private val orchestrator = AgentOrchestrator()

    val agents = orchestrator.agents
    val approvals = orchestrator.pendingApprovals
    val plan = orchestrator.currentPlan

    val messages: StateFlow<List<MessageEntity>> = db.messageDao().getMessages(PROJECT_ID)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _bible = MutableStateFlow(DEFAULT_BIBLE)
    val bible = _bible.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _hasKey = MutableStateFlow(store.apiKey.isNotBlank())
    val hasKey = _hasKey.asStateFlow()
    private val _model = MutableStateFlow(store.model)
    val model = _model.asStateFlow()

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (db.projectDao().getProject(PROJECT_ID) == null) {
                db.projectDao().insert(ProjectEntity(PROJECT_ID, "My Project", "", now, now, true))
                db.bibleDao().upsert(ProjectBibleEntity(PROJECT_ID, DEFAULT_BIBLE, now))
                post("SYSTEM", "system", "Selamat datang di PixelForge. Isi API key di tab Settings, lalu beri perintah — tim akan membuat rencana dulu dan menunggu persetujuanmu.")
            }
            _bible.value = db.bibleDao().get(PROJECT_ID)?.content ?: DEFAULT_BIBLE
        }
        viewModelScope.launch {
            while (true) {
                delay(3000)
                orchestrator.idleTick()
            }
        }
    }

    fun send(command: String) {
        val text = command.trim()
        if (text.isEmpty() || _busy.value) return
        viewModelScope.launch {
            post("USER", "user", text)
            val key = store.apiKey
            if (key.isBlank()) {
                post("SYSTEM", "system", "⚠ API key belum diisi. Buka tab Settings.")
                return@launch
            }
            _busy.value = true
            val client = newClient(key)
            try {
                val p = orchestrator.submitCommand(PROJECT_ID, text, _bible.value) { s, u -> client.chat(s, u) }
                post("AGENT", "assistant", p.architectPlan, "ARCHITECT")
                post("SYSTEM", "system", "Rencana siap. Buka tab Approvals untuk menyetujui atau menolak.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                orchestrator.resetAgents()
                post("SYSTEM", "system", "⚠ ${e.message}")
            } finally {
                client.close()
                _busy.value = false
            }
        }
    }

    fun approve() {
        if (_busy.value) return
        viewModelScope.launch {
            val key = store.apiKey
            if (key.isBlank()) return@launch
            _busy.value = true
            post("SYSTEM", "system", "✅ Disetujui. Agent mulai bekerja...")
            val ctx = getApplication<Application>()
            val svc = Intent(ctx, AgentForegroundService::class.java)
            runCatching { ContextCompat.startForegroundService(ctx, svc) }
            val client = newClient(key)
            try {
                orchestrator.execute(_bible.value, { s, u -> client.chat(s, u) }) { role, out ->
                    post("AGENT", "assistant", out, role.name)
                }
                post("SYSTEM", "system", "Semua agent selesai.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post("SYSTEM", "system", "⚠ ${e.message}")
            } finally {
                client.close()
                ctx.stopService(svc)
                _busy.value = false
            }
        }
    }

    fun reject() {
        orchestrator.rejectAll()
        viewModelScope.launch { post("SYSTEM", "system", "❌ Rencana ditolak. Beri perintah baru atau revisi.") }
    }

    fun saveSettings(apiKey: String, model: String, bible: String) {
        if (apiKey.isNotBlank()) store.apiKey = apiKey
        store.model = model
        _hasKey.value = store.apiKey.isNotBlank()
        _model.value = store.model
        _bible.value = bible
        viewModelScope.launch {
            db.bibleDao().upsert(ProjectBibleEntity(PROJECT_ID, bible, System.currentTimeMillis()))
        }
    }

    private fun newClient(key: String) =
        OpenRouterClient(key, listOf(store.model, "openrouter/auto").distinct())

    /** type: USER | AGENT | SYSTEM. Untuk AGENT, kolom agentId diisi nama role (mis. CODER). */
    private suspend fun post(type: String, role: String, content: String, agentRole: String? = null) {
        db.messageDao().insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                projectId = PROJECT_ID,
                agentId = agentRole,
                role = role,
                content = content,
                type = type,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    companion object {
        const val PROJECT_ID = "default"
        val DEFAULT_BIBLE = """
            # Project Bible
            ## Tujuan
            (Tulis tujuan proyekmu di sini)
            ## Tech stack
            (mis. Kotlin, Jetpack Compose, Room)
            ## Aturan
            - Jawab singkat dan konkret
            - Ikuti gaya kode yang sudah ada
        """.trimIndent()
    }
}
