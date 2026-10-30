package com.pixelforge.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelforge.PixelForgeApp
import com.pixelforge.agents.AgentLoop
import com.pixelforge.agents.AgentOrchestrator
import com.pixelforge.agents.Executor
import com.pixelforge.agents.ProtectedFiles
import com.pixelforge.agents.ToolProtocol
import com.pixelforge.core.AgentForegroundService
import com.pixelforge.core.StateStore
import com.pixelforge.data.local.entity.MessageEntity
import com.pixelforge.data.local.entity.ProjectBibleEntity
import com.pixelforge.data.local.entity.ProjectEntity
import com.pixelforge.domain.model.Agent
import com.pixelforge.domain.model.AgentRole
import com.pixelforge.domain.model.AgentStatus
import com.pixelforge.domain.model.ApprovalRequest
import com.pixelforge.domain.model.TaskPlan
import com.pixelforge.github.GitHubClient
import com.pixelforge.github.GitHubException
import com.pixelforge.github.RepoDeniedException
import com.pixelforge.github.RepoInfo
import com.pixelforge.routing.Privacy
import com.pixelforge.routing.FreeModels
import com.pixelforge.routing.LlmExhaustedException
import com.pixelforge.routing.LlmRouter
import com.pixelforge.agents.AndroidScaffold
import com.pixelforge.agents.CiAutoFix
import com.pixelforge.routing.ProviderConfig
import com.pixelforge.routing.ProviderPresets
import com.pixelforge.routing.QuotaMeter
import com.pixelforge.routing.Provider
import com.pixelforge.workspace.Workspace
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    val hasGhToken: Boolean,
    val bible: String,
    val allowPaid: Boolean,
    val omniTrusted: Boolean,
    val orCredits: Boolean
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
    val bible: String,
    val allowPaid: Boolean,
    val omniTrusted: Boolean,
    val orCredits: Boolean
)

/** Repo belum bisa dibuat otomatis: pengguna diminta membuatnya sendiri lalu menghubungkan. */
data class RepoHelp(val name: String, val isPrivate: Boolean)

/** Repo GitHub milik satu project. */
data class ProjectMeta(
    val repo: String = "",
    val branch: String = "main",
    /** Repo privat di GitHub → kode tidak dikirim ke provider gratis tanpa izin. */
    val isPrivate: Boolean = false,
    /** null = belum ditanya, true = diizinkan, false = ditolak. */
    val freeConsent: Boolean? = null
)

/** Progres fase build/debug di GitHub, disimpan per project supaya bisa dilanjutkan. */
data class CiState(
    val pending: Boolean = false,
    val debugLog: String? = null,
    val attempt: Int = 0,
    val fixed: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val pf = app as PixelForgeApp
    private val db = pf.db
    private val store = pf.store
    private val stateStore = StateStore(app)

    /** Meteran jatah semua provider; hitungan harian disimpan supaya tidak hilang saat app ditutup. */
    private val quotaMeter = QuotaMeter().also { m ->
        m.restore(stateStore.loadRaw("quota"))
        m.onChange = { stateStore.saveRaw("quota", m.export()) }
    }
    val quota: StateFlow<List<QuotaMeter.Snapshot>> = quotaMeter.snapshots

    // ---------- Project aktif: tiap project punya orchestrator, workspace, chat, bible, repo sendiri ----------

    private val _projectId = MutableStateFlow(stateStore.load("current_project", String::class.java) ?: PROJECT_ID)
    val projectId = _projectId.asStateFlow()

    private val orchestrators = mutableMapOf<String, AgentOrchestrator>()
    private val workspaces = mutableMapOf<String, Workspace>()
    private fun orchFor(id: String): AgentOrchestrator = orchestrators.getOrPut(id) { AgentOrchestrator(stateStore, id) }
    private fun wsFor(id: String): Workspace = workspaces.getOrPut(id) { Workspace(getApplication<Application>(), id) }
    private val orchestrator: AgentOrchestrator get() = orchFor(_projectId.value)
    private val workspace: Workspace get() = wsFor(_projectId.value)

    val projects: StateFlow<List<ProjectEntity>> =
        db.projectDao().getAllProjects().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val agents: StateFlow<List<Agent>> = _projectId.flatMapLatest { orchFor(it).agents }
        .stateIn(viewModelScope, SharingStarted.Eagerly, orchFor(_projectId.value).agents.value)
    val approvals: StateFlow<List<ApprovalRequest>> = _projectId.flatMapLatest { orchFor(it).pendingApprovals }
        .stateIn(viewModelScope, SharingStarted.Eagerly, orchFor(_projectId.value).pendingApprovals.value)
    val unfinished: StateFlow<List<ApprovalRequest>> = _projectId.flatMapLatest { orchFor(it).unfinished }
        .stateIn(viewModelScope, SharingStarted.Eagerly, orchFor(_projectId.value).unfinished.value)
    val plan: StateFlow<TaskPlan?> = _projectId.flatMapLatest { orchFor(it).currentPlan }
        .stateIn(viewModelScope, SharingStarted.Eagerly, orchFor(_projectId.value).currentPlan.value)

    val messages: StateFlow<List<MessageEntity>> =
        _projectId.flatMapLatest { db.messageDao().getMessages(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _bible = MutableStateFlow(DEFAULT_BIBLE)
    private val _engineOn = MutableStateFlow(stateStore.loadRaw("engine_mode") != "off")
    val engineOn = _engineOn.asStateFlow()
    private val _stage = MutableStateFlow(com.pixelforge.engine.FlowStateStore.load(stateStore, _projectId.value).stage)
    val stage = _stage.asStateFlow()
    private val _progress = MutableStateFlow("")
    val progress = _progress.asStateFlow()

    fun setEngineOn(on: Boolean) {
        stateStore.saveRaw("engine_mode", if (on) "on" else "off")
        _engineOn.value = on
    }

    /** Dev: kirim hasil ke repo project dan aktifkan GitHub Pages supaya aplikasi punya alamat web. */
    fun publishWeb() {
        if (_busy.value) return
        val pid = _projectId.value
        viewModelScope.launch {
            val token = store.ghToken
            val repo = _meta.value.repo
            val branch = _meta.value.branch
            if (token.isBlank() || repo.isBlank()) {
                post("SYSTEM", "system", "ℹ Atur repo lewat tombol Repo di atas dan isi token GitHub di Settings dulu.", null, pid)
                return@launch
            }
            val files = wsFor(pid).readAll()
            if ("index.html" !in files) {
                post("SYSTEM", "system", "ℹ Belum ada index.html untuk dikirim.", null, pid)
                return@launch
            }
            _busy.value = true
            post("SYSTEM", "system", "⬆ Mengirim ${files.size} file ke $repo ...", null, pid)
            val gh = GitHubClient(token, repo, branch)
            try {
                gh.commitFiles(files, "PixelForge: " + projectName())
                val url = gh.enablePages() ?: ("https://" + repo.substringBefore('/') + ".github.io/" + repo.substringAfter('/') + "/")
                post("SYSTEM", "system", "✅ Terkirim ke $repo. Alamat aplikasi (aktif sekitar 1–2 menit): $url", null, pid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post("SYSTEM", "system", "⚠ ${e.message}", null, pid)
            } finally {
                gh.close()
                _busy.value = false
            }
        }
    }

    /** Isi file project untuk penampil aplikasi. */
    fun readProjectFile(path: String): String? = workspace.takeIf { it.exists(path) }?.read(path)

    private suspend fun runEngine(text: String, providers: List<Provider>) {
        val pid = _projectId.value
        _busy.value = true
        val router = LlmRouter(providers, quotaMeter)
        try {
            val flow = com.pixelforge.engine.EngineFlow(
                com.pixelforge.engine.RouterLlm(router),
                com.pixelforge.engine.WorkspaceStore(wsFor(pid)),
                com.pixelforge.engine.PersistentEventLog(stateStore, "events:$pid"),
                com.pixelforge.engine.FlowStateStore.load(stateStore, pid),
                onState = {
                    com.pixelforge.engine.FlowStateStore.save(stateStore, pid, it)
                    if (pid == _projectId.value) _stage.value = it.stage
                },
                sandbox = com.pixelforge.engine.SandboxRunner(getApplication<Application>()) { path ->
                    wsFor(pid).takeIf { it.exists(path) }?.read(path)
                }
            )
            val says = flow.handle(text) { _progress.value = it }
            says.forEach { post("AGENT", "assistant", it.text, it.who, pid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmExhaustedException) {
            post("SYSTEM", "system", "⏸ ${e.message} Progres tersimpan; ketik **lanjut** nanti.", null, pid)
        } catch (e: Exception) {
            post("SYSTEM", "system", "⚠ ${e.message}", null, pid)
        } finally {
            router.close()
            _progress.value = ""
            _files.value = wsFor(pid).list()
            _busy.value = false
        }
    }

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings = _settings.asStateFlow()

    private val _meta = MutableStateFlow(loadMeta(_projectId.value))
    val meta = _meta.asStateFlow()

    private val _repoHelp = MutableStateFlow<RepoHelp?>(null)
    val repoHelp = _repoHelp.asStateFlow()

    private val _files = MutableStateFlow(wsFor(_projectId.value).list())
    val files = _files.asStateFlow()

    private val _ci = MutableStateFlow(loadCi(_projectId.value))

    /** Ada pekerjaan yang terhenti (agent tertunda / build-debug belum selesai) dan bisa dilanjutkan. */
    val canResume: StateFlow<Boolean> =
        combine(unfinished, _ci) { u, c -> u.isNotEmpty() || c.pending }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (db.projectDao().getProject(PROJECT_ID) == null) {
                db.projectDao().insert(ProjectEntity(PROJECT_ID, "My Project", "", now, now, true))
                db.bibleDao().upsert(ProjectBibleEntity(PROJECT_ID, DEFAULT_BIBLE, now))
                post("SYSTEM", "system", "Selamat datang di PixelForge. Isi API key di tab Settings, lalu beri perintah — tim akan membuat rencana dulu dan menunggu persetujuanmu.", null, PROJECT_ID)
            }
            // Migrasi dari versi lama: repo global → repo milik project pertama
            if (stateStore.load("meta:$PROJECT_ID", ProjectMeta::class.java) == null && store.ghRepo.isNotBlank()) {
                stateStore.save("meta:$PROJECT_ID", ProjectMeta(store.ghRepo, store.ghBranch))
                if (_projectId.value == PROJECT_ID) _meta.value = loadMeta(PROJECT_ID)
            }
            _bible.value = db.bibleDao().get(_projectId.value)?.content?.takeUnless { Brief.isOldTemplate(it) } ?: DEFAULT_BIBLE
            _settings.value = readSettings()
        }
    }

    // ---------- Kelola project ----------

    private fun loadMeta(id: String): ProjectMeta =
        stateStore.load("meta:$id", ProjectMeta::class.java) ?: ProjectMeta()

    private fun loadCi(id: String): CiState =
        stateStore.load("ci:$id", CiState::class.java)
            ?: (if (id == PROJECT_ID) stateStore.load("ci", CiState::class.java) else null)
            ?: CiState()

    private fun setMeta(m: ProjectMeta) {
        _meta.value = m
        stateStore.save("meta:${_projectId.value}", m)
    }

    private fun projectName(): String =
        projects.value.firstOrNull { it.id == _projectId.value }?.name ?: "project"

    /** Tidak bisa pindah project saat agent sedang bekerja (supaya hasil tidak nyasar ke project lain). */
    fun switchProject(id: String) {
        if (_busy.value || id == _projectId.value) return
        _projectId.value = id
        stateStore.save("current_project", id)
        _meta.value = loadMeta(id)
        _ci.value = loadCi(id)
        _files.value = wsFor(id).list()
        _stage.value = com.pixelforge.engine.FlowStateStore.load(stateStore, id).stage
        viewModelScope.launch {
            _bible.value = db.bibleDao().get(id)?.content?.takeUnless { Brief.isOldTemplate(it) } ?: DEFAULT_BIBLE
            _settings.value = readSettings()
        }
    }

    fun createProject(name: String, repoName: String, autoRepo: Boolean, isPrivate: Boolean) {
        val n = name.trim()
        if (n.isEmpty() || _busy.value) return
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.projectDao().insert(ProjectEntity(id, n, "", now, now, true))
            db.bibleDao().upsert(ProjectBibleEntity(id, DEFAULT_BIBLE, now))
            stateStore.save("meta:$id", ProjectMeta(if (autoRepo) "" else repoName.trim(), "main"))
            switchProject(id)
            post("SYSTEM", "system", "📁 Project \"$n\" dibuat. Beri perintah di tab Chat.")
            if (autoRepo) doCreateRepo(repoName.ifBlank { n }, isPrivate)
        }
    }

    fun updateRepo(repo: String, branch: String) {
        if (_busy.value) return
        val full = repo.trim().removePrefix("https://github.com/").trim('/')
        val br = branch.trim().ifBlank { "main" }
        val old = _meta.value
        setMeta(old.copy(repo = full, branch = br, freeConsent = if (full == old.repo) old.freeConsent else null))
        // Cari tahu repo privat/publik supaya aturan privasi benar.
        val token = store.ghToken
        if (full.isNotBlank() && token.isNotBlank()) viewModelScope.launch {
            val gh = GitHubClient(token, "", "main")
            try {
                gh.findRepo(full)?.let { info -> setMeta(_meta.value.copy(isPrivate = info.isPrivate)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                gh.close()
            }
        }
    }

    /** Ambil daftar repo di akun GitHub untuk dipilih. Hasil: (daftar, pesan error atau null). */
    fun fetchRepos(onResult: (List<RepoInfo>, String?) -> Unit) {
        val token = store.ghToken
        if (token.isBlank()) { onResult(emptyList(), "Isi token GitHub di Settings dulu."); return }
        viewModelScope.launch {
            val gh = GitHubClient(token, "", "main")
            try {
                onResult(gh.listRepos(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(emptyList(), e.message ?: "Gagal mengambil daftar repo.")
            } finally {
                gh.close()
            }
        }
    }

    /** Terapkan repo baru ke project aktif; izin privasi direset kalau repo-nya berbeda. */
    private fun setRepo(info: RepoInfo) {
        val old = _meta.value
        setMeta(
            ProjectMeta(
                info.fullName, info.defaultBranch, info.isPrivate,
                if (info.fullName == old.repo) old.freeConsent else null
            )
        )
    }

    // ---------- Privasi ----------

    private val _privacyAsk = MutableStateFlow(false)
    val privacyAsk = _privacyAsk.asStateFlow()
    private var privacyRetry: (() -> Unit)? = null

    /** Provider yang boleh dipakai untuk project aktif. Kosong = berhenti (pesan sudah dikirim ke chat). */
    private suspend fun allowedProviders(retry: () -> Unit): List<Provider> {
        val all = buildProviders()
        if (all.isEmpty()) {
            post("SYSTEM", "system", "⚠ Belum ada provider LLM. Isi API key OpenRouter atau URL OmniRoute di tab Settings.")
            return emptyList()
        }
        val m = _meta.value
        val allowed = Privacy.apply(all, m.isPrivate, m.freeConsent == true).providers
        if (allowed.isNotEmpty()) return allowed
        if (m.freeConsent == null) {
            privacyRetry = retry
            _privacyAsk.value = true
        } else {
            post(
                "SYSTEM", "system",
                "🔒 Project ini privat dan tidak ada provider yang diizinkan. Izinkan provider gratis di Settings > Privasi project, " +
                    "tandai OmniRoute sebagai tepercaya, atau pakai model berbayar."
            )
        }
        return emptyList()
    }

    fun answerPrivacy(allow: Boolean) {
        _privacyAsk.value = false
        setMeta(_meta.value.copy(freeConsent = allow))
        val retry = privacyRetry
        privacyRetry = null
        if (allow) retry?.invoke()
        else viewModelScope.launch {
            post("SYSTEM", "system", "🔒 Dibatalkan. Kode project privat tidak dikirim ke provider gratis.")
        }
    }

    /** Ubah izin dari Settings (hanya relevan untuk project privat). */
    fun setFreeConsent(allow: Boolean) = setMeta(_meta.value.copy(freeConsent = allow))

    /** Agent Repo: membuat repo GitHub untuk project aktif. */
    fun createRepo(repoName: String, isPrivate: Boolean) {
        if (_busy.value) return
        viewModelScope.launch { doCreateRepo(repoName.ifBlank { projectName() }, isPrivate) }
    }

    fun dismissRepoHelp() { _repoHelp.value = null }

    /** Pengguna sudah membuat repo sendiri: cari repo itu di akunnya lalu hubungkan ke project. */
    fun linkCreatedRepo() {
        val help = _repoHelp.value ?: return
        if (_busy.value) return
        val token = store.ghToken
        viewModelScope.launch {
            if (token.isBlank()) {
                post("SYSTEM", "system", "⚠ Isi token GitHub di Settings dulu.")
                return@launch
            }
            _busy.value = true
            val gh = GitHubClient(token, "", "main")
            try {
                val full = "${gh.login()}/${help.name}"
                val info = gh.findRepo(full)
                if (info == null) {
                    post(
                        "SYSTEM", "system",
                        "⚠ Repo $full belum terlihat. Pastikan repo sudah dibuat dengan nama persis itu, dan token punya akses ke repo tersebut " +
                            "(token fine-grained yang hanya mencakup satu repo tidak akan melihat repo baru)."
                    )
                } else {
                    setRepo(info)
                    _repoHelp.value = null
                    post("AGENT", "assistant", "Repo terhubung: ${info.url}\nBranch: `${info.defaultBranch}`.", "REPO")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post("SYSTEM", "system", "⚠ ${e.message}")
            } finally {
                gh.close()
                _busy.value = false
            }
        }
    }

    private fun slug(s: String): String {
        val x = s.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.')
        return x.ifBlank { "pixelforge-project" }.take(80)
    }

    private suspend fun doCreateRepo(repoName: String, isPrivate: Boolean) {
        val token = store.ghToken
        if (token.isBlank()) {
            post("SYSTEM", "system", "⚠ Isi token GitHub di Settings dulu supaya agent Repo bisa membuat repo.")
            return
        }
        _busy.value = true
        val orch = orchestrator
        orch.announce(AgentRole.REPO, AgentStatus.WORKING, "Membuat repo...")
        val name = slug(repoName)
        val gh = GitHubClient(token, "", "main")
        try {
            post("SYSTEM", "system", "🏗 Agent Repo membuat repo \"$name\" (${if (isPrivate) "privat" else "publik"})...")
            val info = gh.createRepo(name, "Dibuat oleh PixelForge — ${projectName()}", isPrivate)
            setRepo(info)
            post("AGENT", "assistant", "Repo siap: ${info.url}\nBranch: `${info.defaultBranch}`. Agent akan commit ke repo ini.", "REPO")
            orch.announce(AgentRole.REPO, AgentStatus.DONE, "Repo siap ✓")
            delay(4000)
            orch.announce(AgentRole.REPO, AgentStatus.IDLE, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RepoDeniedException) {
            _repoHelp.value = RepoHelp(e.repoName, isPrivate)
            post("SYSTEM", "system", "⚠ ${e.message}")
            orch.announce(AgentRole.REPO, AgentStatus.IDLE, null)
        } catch (e: Exception) {
            post("SYSTEM", "system", "⚠ ${e.message}")
            orch.announce(AgentRole.REPO, AgentStatus.IDLE, null)
        } finally {
            gh.close()
            _busy.value = false
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
        hasGhToken = store.ghToken.isNotBlank(),
        bible = _bible.value,
        allowPaid = store.allowPaid,
        omniTrusted = store.omniTrusted,
        orCredits = store.orCredits
    )

    fun saveSettings(f: SettingsForm) {
        if (f.apiKey.isNotBlank()) store.apiKey = f.apiKey
        store.model = f.model
        store.omniUrl = f.omniUrl
        if (f.omniKey.isNotBlank()) store.omniKey = f.omniKey
        store.omniModel = f.omniModel
        store.omniFirst = f.omniFirst
        store.allowPaid = f.allowPaid
        store.omniTrusted = f.omniTrusted
        store.orCredits = f.orCredits
        if (f.ghToken.isNotBlank()) store.ghToken = f.ghToken
        _settings.value = readSettings()
    }

    /** Daftar model gratis OpenRouter; diperbarui paling sering sekali per 12 jam, sisanya dari simpanan. */
    private suspend fun freeCatalog(): List<String> {
        val cached = stateStore.loadRaw("free_models")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val at = stateStore.loadRaw("free_models_at")?.toLongOrNull() ?: 0L
        if (cached.isNotEmpty() && System.currentTimeMillis() - at < 12 * 3600_000L) return cached
        val fresh = FreeModels.fetch()
        if (fresh.isNotEmpty()) {
            stateStore.saveRaw("free_models", fresh.joinToString("\n"))
            stateStore.saveRaw("free_models_at", System.currentTimeMillis().toString())
            return fresh
        }
        return cached
    }

    /** Urutan provider: OpenRouter dan/atau OmniRoute; kalau satu limit, otomatis pindah ke yang lain. */
    private suspend fun buildProviders(): List<Provider> {
        val list = mutableListOf<Provider>()
        if (store.apiKey.isNotBlank()) {
            list += Provider(
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                apiKey = store.apiKey,
                models = FreeModels.candidates(store.model, freeCatalog(), store.allowPaid),
                headers = mapOf("HTTP-Referer" to "https://pixelforge.app", "X-Title" to "PixelForge"),
                freeOnly = !store.allowPaid,
                modelsDeclarePricing = true,
                limits = if (store.orCredits) QuotaMeter.OPENROUTER_FREE_WITH_CREDITS else QuotaMeter.OPENROUTER_FREE,
                limitPerAccount = true
            )
        }
        for (c in ProviderPresets.parse(store.extraProvidersJson)) {
            if (!c.enabled || c.baseUrl.isBlank() || c.model.isBlank()) continue
            val k = ProviderPresets.known(c.name, c.model)
            list += Provider(
                name = c.name, baseUrl = c.baseUrl, apiKey = c.apiKey, models = listOf(c.model),
                limitPerAccount = true, limits = k.limits, tpm = k.tpm
            )
        }
        if (store.omniUrl.isNotBlank()) {
            list += Provider(
                name = "OmniRoute",
                baseUrl = store.omniUrl,
                apiKey = store.omniKey,
                models = listOf(store.omniModel.ifBlank { "auto" }),
                trusted = store.omniTrusted
            )
        }
        return if (store.omniFirst) list.reversed() else list
    }

    // ---------- Kolam provider ----------

    private val _pool = MutableStateFlow(ProviderPresets.parse(store.extraProvidersJson))
    val pool = _pool.asStateFlow()

    private fun savePool(list: List<ProviderConfig>) {
        store.extraProvidersJson = ProviderPresets.toJson(list)
        _pool.value = list
    }

    fun addProvider(c: ProviderConfig) {
        if (c.name.isBlank() || c.baseUrl.isBlank()) return
        savePool(ProviderPresets.upsert(_pool.value, c.copy(name = c.name.trim(), baseUrl = c.baseUrl.trim(), model = c.model.trim())))
    }

    fun removeProvider(name: String) = savePool(_pool.value.filterNot { it.name == name })

    fun setProviderEnabled(name: String, enabled: Boolean) =
        savePool(_pool.value.map { if (it.name == name) it.copy(enabled = enabled) else it })

    fun fetchModels(baseUrl: String, apiKey: String, onResult: (List<String>) -> Unit) {
        viewModelScope.launch { onResult(ProviderPresets.listModels(baseUrl, apiKey)) }
    }

    // ---------- Perintah → rencana ----------

    fun send(command: String) = doSend(command, true)

    private fun doSend(command: String, echo: Boolean) {
        val text = command.trim()
        if (text.isEmpty() || _busy.value) return
        viewModelScope.launch {
            if (echo) {
                post("USER", "user", text)
                val pid = _projectId.value
                val updated = Brief.add(_bible.value, text)
                _bible.value = updated
                db.bibleDao().upsert(ProjectBibleEntity(pid, updated, System.currentTimeMillis()))
            }
            val providers = allowedProviders { doSend(text, false) }
            if (providers.isEmpty()) return@launch
            if (_engineOn.value) { runEngine(text, providers); return@launch }
            _busy.value = true
            val router = LlmRouter(providers, quotaMeter)
            try {
                val p = orchestrator.submitCommand(_projectId.value, text, fullContext()) { s, u -> router.chat(s, u) }
                post("AGENT", "assistant", p.architectPlan, "ARCHITECT")
                val est = if (p.estimatedCalls > 0) " · Estimasi ~${p.estimatedCalls} panggilan" else ""
                post("SYSTEM", "system", "Rencana siap$est. Buka tab Approvals untuk menyetujui atau menolak.")
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
        val providers = allowedProviders { viewModelScope.launch { runPipeline() } }
        if (providers.isEmpty()) return
        _busy.value = true
        val ctx = getApplication<Application>()
        val svc = Intent(ctx, AgentForegroundService::class.java)
        runCatching { ContextCompat.startForegroundService(ctx, svc) }
        val router = LlmRouter(providers, quotaMeter)
        try {
            addAndroidScaffoldIfNeeded()
            if (orchestrator.hasWork()) {
                val done = orchestrator.execute(
                    fullContext(),
                    agentRunner(router),
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

    /** Proyek Android tanpa Gradle: beri kerangka yang sudah terbukti sebelum agent mulai (file yang sudah ada tidak ditimpa). */
    private suspend fun addAndroidScaffoldIfNeeded() {
        if (!AndroidScaffold.wanted(fullContext(), workspace.list())) return
        val fresh = AndroidScaffold.files(projectName()).filterKeys { !workspace.exists(it) }
        if (fresh.isEmpty()) return
        workspace.write(fresh.map { (path, text) -> Workspace.FileOut(path, text) })
        _files.value = workspace.list()
        post("SYSTEM", "system", "🧱 Kerangka proyek Android disiapkan (Gradle, Compose, workflow build). Agent hanya menambah kode di app/src/main.")
    }

    /** Simpan file dari output agent ke workspace; chat hanya menampilkan ringkasannya. */
    private suspend fun handleOutput(role: AgentRole, out: String) {
        val all = Workspace.extractFiles(out)
        val blocked = all.filter { ProtectedFiles.violation(it.path, workspace.exists(it.path)) != null }
        val files = all - blocked.toSet()
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
        if (blocked.isNotEmpty()) {
            text += "\n\n🛡 Dilewati (file infrastruktur dilindungi): " + blocked.joinToString { it.path }
        }
        post("AGENT", "assistant", text.trim(), role.name)
    }

    // ---------- Agent bertool (Coder / Debugger) ----------

    private class ToolRun(val text: String, val changed: List<String>)

    /** Coder & Debugger memakai loop bertool (baca/cari/edit file); agent lain satu panggilan biasa. */
    private fun agentRunner(router: LlmRouter): suspend (AgentRole, String, String) -> String = { role, system, user ->
        if (role == AgentRole.CODER || role == AgentRole.DEBUGGER) {
            val run = runToolAgent(router, role, system, user)
            if (run.text.startsWith(LOOP_INCOMPLETE)) throw IllegalStateException(run.text)
            run.text
        } else {
            router.chat(system + "\n" + Workspace.FILE_FORMAT_PROMPT, user)
        }
    }

    private suspend fun runToolAgent(router: LlmRouter, role: AgentRole, system: String, user: String): ToolRun {
        val ws = workspace
        val exec = object : Executor {
            override fun read(path: String): String? = if (ws.exists(path)) ws.read(path) else null
            override fun write(path: String, content: String) {
                ws.write(listOf(Workspace.FileOut(path, content)))
            }
            override fun list(): List<String> = ws.list()
        }
        val orch = orchestrator
        val loop = AgentLoop(
            { msgs -> router.chat(msgs.map { LlmRouter.ChatMessage(it.role, it.content) }) },
            exec,
            12
        )
        val res = loop.run(system + "\n" + ToolProtocol.PROMPT, user) { step ->
            orch.announce(role, AgentStatus.WORKING, step.take(40))
            post("SYSTEM", "system", "${role.name.lowercase().replaceFirstChar { it.uppercase() }} • $step")
        }
        _files.value = ws.list()
        val text = buildString {
            append(if (res.finished) res.summary else LOOP_INCOMPLETE + " " + res.summary)
            if (res.changed.isNotEmpty()) append("\n\n📁 Diubah:\n" + res.changed.joinToString("\n") { "• $it" })
        }
        return ToolRun(text, res.changed)
    }

    fun clearChat() {
        if (_busy.value) return
        val pid = _projectId.value
        viewModelScope.launch { db.messageDao().clearProject(pid) }
    }

    // ---------- Commit → build → debug (GitHub) ----------

    private fun setCi(c: CiState) {
        _ci.value = c
        stateStore.save("ci:${_projectId.value}", c)
    }

    private suspend fun runCi(router: LlmRouter) {
        val token = store.ghToken
        val repo = _meta.value.repo
        val branch = _meta.value.branch
        if (token.isBlank() || repo.isBlank()) {
            post("SYSTEM", "system", "ℹ Project ini belum punya repo/token GitHub — file tersimpan di tab Files. Atur repo lewat tombol ✎ di bar project (atau minta agent Repo membuatnya), dan isi token di Settings.")
            setCi(CiState())
            return
        }
        if (workspace.list().isEmpty()) {
            setCi(CiState())
            return
        }
        val gh = GitHubClient(token, repo, branch)
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
                    val auto = CiAutoFix.plan(workspace.readAll(), projectName())
                    if (auto.isNotEmpty()) {
                        workspace.write(auto.map { (path, text) -> Workspace.FileOut(path, text) })
                        _files.value = workspace.list()
                        post("SYSTEM", "system", "🔧 Penyebab pasti: workflow memanggil ./gradlew padahal wrapper tidak ada. Aplikasi mengganti workflow dengan yang terbukti" +
                            (if (auto.keys.any { it.startsWith("app/") }) " dan menambah kerangka Gradle" else "") + ": " + auto.keys.joinToString())
                        attempt++
                        fixed = true
                        setCi(CiState(true, log, attempt, true))
                    } else {
                    post("SYSTEM", "system", "🛠 Debugger memperbaiki error (percobaan ${attempt + 1}/$MAX_FIX)...")
                    val run = runToolAgent(
                        router,
                        AgentRole.DEBUGGER,
                        DEBUG_SYSTEM + "Ringkasan project:\n" + fullContext(),
                        "CI build log (condensed):\n$log\n\nFind the root cause and fix it. Use LIST/READ/GREP to inspect the files first."
                    )
                    if (run.changed.isEmpty()) {
                        post("SYSTEM", "system", "⚠ Debugger tidak mengubah file apa pun:\n" + run.text.take(1200))
                        setCi(CiState())
                        return
                    }
                    post("AGENT", "assistant", run.text, "DEBUGGER")
                    attempt++
                    fixed = true
                    setCi(CiState(true, log, attempt, true))
                    }
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

    // ---------- Util ----------

    fun readFile(path: String): String = workspace.read(path)

    private fun fullContext(): String {
        val f = workspace.list()
        return if (f.isEmpty()) _bible.value
        else _bible.value + "\n\n## Workspace files (existing)\n" + f.take(80).joinToString("\n") { "- $it" }
    }

    /** type: USER | AGENT | SYSTEM. Untuk AGENT, kolom agentId diisi nama role (mis. CODER). */
    private suspend fun post(
        type: String,
        role: String,
        content: String,
        agentRole: String? = null,
        pid: String = _projectId.value
    ) {
        db.messageDao().insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                projectId = pid,
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
        private const val LOOP_INCOMPLETE = "[BATAS PUTARAN]"
        private const val DEBUG_SYSTEM =
            "You are the Debugger agent. A CI build failed. Find the root cause from the log and fix it " +
                "with minimal edits. Reply in Indonesian.\n"
        val DEFAULT_BIBLE = Brief.HEADER
    }
}
