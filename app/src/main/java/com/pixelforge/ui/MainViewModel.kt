package com.pixelforge.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.PixelForgeApp
import com.pixelforge.agents.AgentOrchestrator
import com.pixelforge.core.AgentForegroundService
import com.pixelforge.core.StateStore
import com.pixelforge.data.local.entity.MessageEntity
import com.pixelforge.data.local.entity.ProjectBibleEntity
import com.pixelforge.data.local.entity.ProjectEntity
import com.pixelforge.domain.model.AgentRole
import com.pixelforge.github.GitHubClient
import com.pixelforge.github.GitHubException
import com.pixelforge.routing.LlmExhaustedException
import com.pixelforge.routing.LlmRouter
import com.pixelforge.routing.Provider
import com.pixelforge.workspace.Workspace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Nilai non-rahasia + penanda apakah rahasia (key/token) sudah tersimpan. */
data class SettingsState(
    val model: String,
    val hasKey: Boolean,
    val omniUrl: String,
    val omniModel: String,
    val hasOmniKey: Boolean,
    val omniFirst: Boolean,
    val ghRepo: String,
    val ghBranch: String,
    val hasGhToken: Boolean,
    val bible: String
)

/** Isian form Settings. Kolom rahasia yang kosong = tidak diganti. */
data class SettingsForm(
    val apiKey: String,
    val model: String,
    val omniUrl: String,
    val omniKey: String,
    val omniModel: String,
    val omniFirst: Boolean,
    val ghToken: String,
    val ghRepo: String,
    val ghBranch: String,
    val bible: String
)

/** Progres fase build/debug di GitHub, disimpan supaya bisa dilanjutkan. */
data class CiState(
    val pending: Boolean = false,
    val debugLog: String? = null,
    val attempt: Int = 0,
    val fixed: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val pf = app as PixelForgeApp
    private val db = pf.db
    private val store = pf.store
    private val stateStore = StateStore(app)
    private val workspace = Workspace(app)
    private val orchestrator = AgentOrchestrator(stateStore)

    val agents = orchestrator.agents
    val approvals = orchestrator.pendingApprovals
    val unfinished = orchestrator.unfinished
    val plan = orchestrator.currentPlan

    val messages: StateFlow<List<MessageEntity>> = db.messageDao().getMessages(PROJECT_ID)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _bible = MutableStateFlow(DEFAULT_BIBLE)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings = _settings.asStateFlow()

    private val _files = MutableStateFlow(workspace.list())
    val files = _files.asStateFlow()

    private val _ci = MutableStateFlow(stateStore.load("ci", CiState::class.java) ?: CiState())

    /** Ada pekerjaan yang terhenti (agent tertunda / build-debug belum selesai) dan bisa dilanjutkan. */
    val canResume: StateFlow<Boolean> =
        combine(orchestrator.unfinished, _ci) { u, c -> u.isNotEmpty() || c.pending }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (db.projectDao().getProject(PROJECT_ID) == null) {
                db.projectDao().insert(ProjectEntity(PROJECT_ID, "My Project", "", now, now, true))
                db.bibleDao().upsert(ProjectBibleEntity(PROJECT_ID, DEFAULT_BIBLE, now))
                post("SYSTEM", "system", "Selamat datang di PixelForge. Isi API key di tab Settings, lalu beri perintah — tim akan membuat rencana dulu dan menunggu persetujuanmu.")
            }
            _bible.value = db.bibleDao().get(PROJECT_ID)?.content ?: DEFAULT_BIBLE
            _settings.value = readSettings()
        }
        viewModelScope.launch {
            while (true) {
                delay(3000)
                orchestrator.idleTick()
            }
        }
    }

    // ---------- Settings ----------

    private fun readSettings() = SettingsState(
        model = store.model,
        hasKey = store.apiKey.isNotBlank(),
        omniUrl = store.omniUrl,
        omniModel = store.omniModel,
        hasOmniKey = store.omniKey.isNotBlank(),
        omniFirst = store.omniFirst,
        ghRepo = store.ghRepo,
        ghBranch = store.ghBranch,
        hasGhToken = store.ghToken.isNotBlank(),
        bible = _bible.value
    )

    fun saveSettings(f: SettingsForm) {
        if (f.apiKey.isNotBlank()) store.apiKey = f.apiKey
        store.model = f.model
        store.omniUrl = f.omniUrl
        if (f.omniKey.isNotBlank()) store.omniKey = f.omniKey
        store.omniModel = f.omniModel
        store.omniFirst = f.omniFirst
        if (f.ghToken.isNotBlank()) store.ghToken = f.ghToken
        store.ghRepo = f.ghRepo
        store.ghBranch = f.ghBranch
        _bible.value = f.bible
        _settings.value = readSettings()
        viewModelScope.launch {
            db.bibleDao().upsert(ProjectBibleEntity(PROJECT_ID, f.bible, System.currentTimeMillis()))
        }
    }

    /** Urutan provider: OpenRouter dan/atau OmniRoute; kalau satu limit, otomatis pindah ke yang lain. */
    private fun buildProviders(): List<Provider> {
        val list = mutableListOf<Provider>()
        if (store.apiKey.isNotBlank()) {
            list += Provider(
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                apiKey = store.apiKey,
                models = listOf(store.model, "openrouter/auto").distinct(),
                headers = mapOf("HTTP-Referer" to "https://pixelforge.app", "X-Title" to "PixelForge")
            )
        }
        if (store.omniUrl.isNotBlank()) {
            list += Provider(
                name = "OmniRoute",
                baseUrl = store.omniUrl,
                apiKey = store.omniKey,
                models = listOf(store.omniModel.ifBlank { "auto" })
            )
        }
        return if (store.omniFirst) list.reversed() else list
    }

    // ---------- Perintah → rencana ----------

    fun send(command: String) {
        val text = command.trim()
        if (text.isEmpty() || _busy.value) return
        viewModelScope.launch {
            post("USER", "user", text)
            val providers = buildProviders()
            if (providers.isEmpty()) {
                post("SYSTEM", "system", "⚠ Belum ada provider LLM. Isi API key OpenRouter atau URL OmniRoute di tab Settings.")
                return@launch
            }
            _busy.value = true
            val router = LlmRouter(providers)
            try {
                val p = orchestrator.submitCommand(PROJECT_ID, text, fullContext()) { s, u -> router.chat(s, u) }
                post("AGENT", "assistant", p.architectPlan, "ARCHITECT")
                post("SYSTEM", "system", "Rencana siap. Buka tab Approvals untuk menyetujui atau menolak.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                orchestrator.resetAgents()
                post("SYSTEM", "system", "⚠ ${e.message}")
            } finally {
                router.close()
                _busy.value = false
            }
        }
    }

    // ---------- Approve / lanjutkan ----------

    fun approve() {
        if (_busy.value) return
        viewModelScope.launch {
            post("SYSTEM", "system", "✅ Disetujui. Agent mulai bekerja...")
            runPipeline()
        }
    }

    fun resume() {
        if (_busy.value) return
        viewModelScope.launch {
            post("SYSTEM", "system", "▶ Melanjutkan dari progres terakhir...")
            runPipeline()
        }
    }

    fun reject() {
        orchestrator.rejectAll()
        viewModelScope.launch { post("SYSTEM", "system", "❌ Rencana ditolak. Beri perintah baru atau revisi.") }
    }

    private suspend fun runPipeline() {
        val providers = buildProviders()
        if (providers.isEmpty()) {
            post("SYSTEM", "system", "⚠ Belum ada provider LLM. Isi di tab Settings.")
            return
        }
        _busy.value = true
        val ctx = getApplication<Application>()
        val svc = Intent(ctx, AgentForegroundService::class.java)
        runCatching { ContextCompat.startForegroundService(ctx, svc) }
        val router = LlmRouter(providers)
        try {
            if (orchestrator.hasWork()) {
                val done = orchestrator.execute(
                    fullContext(),
                    { s, u -> router.chat(s, u) },
                    { role, out -> handleOutput(role, out) },
                    { role, msg -> post("SYSTEM", "system", "⏸ ${role.name} tertunda: $msg") }
                )
                if (!done) {
                    post("SYSTEM", "system", "⏸ Sebagian agent berhenti (limit/jaringan). Progres tersimpan — tekan Lanjutkan di tab Approvals.")
                    return
                }
                post("SYSTEM", "system", "Semua agent selesai.")
                setCi(CiState(pending = true))
            }
            if (_ci.value.pending) runCi(router)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmExhaustedException) {
            post("SYSTEM", "system", "⏸ Semua provider LLM gagal/limit. Progres tersimpan — tekan Lanjutkan nanti.\n${e.message}")
        } catch (e: Exception) {
            post("SYSTEM", "system", "⚠ ${e.message}")
        } finally {
            router.close()
            ctx.stopService(svc)
            _busy.value = false
        }
    }

    /** Simpan file dari output agent ke workspace; chat hanya menampilkan ringkasannya. */
    private suspend fun handleOutput(role: AgentRole, out: String) {
        val files = Workspace.extractFiles(out)
        var text = Workspace.stripFiles(out).trim()
        if (files.isNotEmpty()) {
            val written = workspace.write(files)
            _files.value = workspace.list()
            text += "\n\n📁 Disimpan ke workspace:\n" + written.joinToString("\n") { "• $it" }
            val cut = files.filter { !it.complete }.map { it.path }
            if (cut.isNotEmpty()) {
                text += "\n\n⚠ Jawaban terpotong (batas token), file ini mungkin tidak lengkap: " + cut.joinToString()
            }
        }
        post("AGENT", "assistant", text.trim(), role.name)
    }

    // ---------- Commit → build → debug (GitHub) ----------

    private fun setCi(c: CiState) {
        _ci.value = c
        stateStore.save("ci", c)
    }

    private suspend fun runCi(router: LlmRouter) {
        val token = store.ghToken
        val repo = store.ghRepo
        if (token.isBlank() || repo.isBlank()) {
            post("SYSTEM", "system", "ℹ GitHub belum diatur — file tersimpan di tab Files. Isi Settings → GitHub untuk commit & build otomatis.")
            setCi(CiState())
            return
        }
        if (workspace.list().isEmpty()) {
            setCi(CiState())
            return
        }
        val gh = GitHubClient(token, repo, store.ghBranch)
        try {
            var log = _ci.value.debugLog
            var attempt = _ci.value.attempt
            var fixed = _ci.value.fixed
            while (true) {
                if (log != null && !fixed) {
                    if (attempt >= MAX_FIX) {
                        post("SYSTEM", "system", "❌ Build masih gagal setelah $MAX_FIX kali perbaikan otomatis. Periksa log di GitHub Actions.")
                        setCi(CiState())
                        return
                    }
                    post("SYSTEM", "system", "🛠 Debugger memperbaiki error (percobaan ${attempt + 1}/$MAX_FIX)...")
                    val out = router.chat(DEBUG_SYSTEM + Workspace.FILE_FORMAT_PROMPT + "\nProject Bible:\n" + _bible.value, debugUser(log))
                    val files = Workspace.extractFiles(out)
                    if (files.isEmpty()) {
                        post("SYSTEM", "system", "⚠ Debugger tidak menghasilkan file perbaikan:\n" + Workspace.stripFiles(out).take(1200))
                        setCi(CiState())
                        return
                    }
                    val written = workspace.write(files)
                    _files.value = workspace.list()
                    post("AGENT", "assistant", Workspace.stripFiles(out).trim() + "\n\n📁 Diperbarui:\n" + written.joinToString("\n") { "• $it" }, "DEBUGGER")
                    attempt++
                    fixed = true
                    setCi(CiState(true, log, attempt, true))
                }
                post("SYSTEM", "system", "📤 Commit ke GitHub ($repo)...")
                val sha = gh.commitFiles(workspace.readAll(), "PixelForge agents: update workspace")
                fixed = false
                setCi(CiState(true, log, attempt, false))
                post("SYSTEM", "system", "⏳ Menunggu build GitHub Actions (commit ${sha.take(7)})...")
                val run = gh.waitForRun(sha)
                if (run == null) {
                    post("SYSTEM", "system", "ℹ Commit ${sha.take(7)} terkirim. Repo ini belum punya workflow Actions, jadi tidak ada build yang dicek.")
                    setCi(CiState())
                    return
                }
                if (run.conclusion == "success") {
                    post("SYSTEM", "system", "✅ Build berhasil.\n${run.url}")
                    setCi(CiState())
                    return
                }
                post("SYSTEM", "system", "❌ Build gagal (${run.conclusion}). Membaca log...\n${run.url}")
                log = gh.failureLog(run.id)
                setCi(CiState(true, log, attempt, false))
            }
        } catch (e: GitHubException) {
            post("SYSTEM", "system", "⚠ GitHub: ${e.message}\nProgres tersimpan — perbaiki masalahnya lalu tekan Lanjutkan.")
        } finally {
            gh.close()
        }
    }

    private fun debugUser(log: String): String {
        val sb = StringBuilder()
        sb.append("CI build log (condensed):\n").append(log).append("\n\nCurrent workspace files:\n")
        var budget = 24_000
        for ((path, content) in workspace.readAll()) {
            if (budget <= 0) break
            val chunk = content.take(budget)
            sb.append("=== FILE: ").append(path).append(" ===\n").append(chunk).append("\n=== END FILE ===\n")
            budget -= chunk.length
        }
        return sb.toString()
    }

    // ---------- Util ----------

    fun readFile(path: String): String = workspace.read(path)

    private fun fullContext(): String {
        val f = workspace.list()
        return if (f.isEmpty()) _bible.value
        else _bible.value + "\n\n## Workspace files (existing)\n" + f.take(80).joinToString("\n") { "- $it" }
    }

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
        private const val MAX_FIX = 3
        private const val DEBUG_SYSTEM =
            "You are the Debugger agent. A CI build failed. Find the root cause from the log and fix it. " +
                "Explain in at most 3 sentences, then output ONLY the files that must change, each COMPLETE. " +
                "Reply in Indonesian.\n"
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
