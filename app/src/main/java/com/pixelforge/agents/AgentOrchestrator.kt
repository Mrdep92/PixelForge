package com.pixelforge.agents

import com.pixelforge.core.StateStore
import com.pixelforge.domain.model.*
import com.pixelforge.workspace.Workspace
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

typealias LlmCall = suspend (systemPrompt: String, userPrompt: String) -> String

/** Status yang disimpan ke disk supaya kerja bisa dilanjutkan setelah limit / app ditutup. */
data class SavedOrch(
    val plan: TaskPlan?,
    val pending: List<ApprovalRequest>,
    val unfinished: List<ApprovalRequest>
)

/**
 * Flow: perintah user → Architect bikin rencana → agent lapor → USER APPROVE → eksekusi paralel.
 * Agent yang gagal (limit/jaringan) tidak hilang: tetap di daftar `unfinished` dan bisa dilanjutkan.
 * Posisi agent berupa pecahan 0..1 dari ukuran kantor supaya pas di semua ukuran layar.
 */
class AgentOrchestrator(private val store: StateStore? = null) {

    // Batasi panggilan LLM bersamaan supaya tidak kena rate limit
    private val llmGate = Semaphore(2)

    private val _agents = MutableStateFlow(createDefaultAgents())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<ApprovalRequest>> = _pendingApprovals.asStateFlow()

    private val _unfinished = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val unfinished: StateFlow<List<ApprovalRequest>> = _unfinished.asStateFlow()

    private val _currentPlan = MutableStateFlow<TaskPlan?>(null)
    val currentPlan: StateFlow<TaskPlan?> = _currentPlan.asStateFlow()

    init {
        val s = store?.load("orch", SavedOrch::class.java)
        if (s != null) {
            _currentPlan.value = s.plan
            _pendingApprovals.value = s.pending ?: emptyList()
            _unfinished.value = s.unfinished ?: emptyList()
            _pendingApprovals.value.forEach { setStatus(it.agentRole, AgentStatus.WAITING_APPROVAL, null) }
            _unfinished.value.forEach { setStatus(it.agentRole, AgentStatus.PAUSED, "⏸ Tertunda") }
        }
    }

    private fun persist() {
        store?.save("orch", SavedOrch(_currentPlan.value, _pendingApprovals.value, _unfinished.value))
    }

    fun hasWork(): Boolean = _pendingApprovals.value.isNotEmpty() || _unfinished.value.isNotEmpty()

    private fun createDefaultAgents(): List<Agent> = listOf(
        Agent(role = AgentRole.ARCHITECT, name = "Arch", positionX = 0.08f, positionY = 0.08f),
        Agent(role = AgentRole.CODER, name = "Code", positionX = 0.40f, positionY = 0.15f),
        Agent(role = AgentRole.AUDITOR, name = "Audit", positionX = 0.72f, positionY = 0.08f),
        Agent(role = AgentRole.DEBUGGER, name = "Debug", positionX = 0.08f, positionY = 0.42f),
        Agent(role = AgentRole.CREATIVE, name = "Creat", positionX = 0.40f, positionY = 0.50f),
        Agent(role = AgentRole.BUSINESS, name = "Biz", positionX = 0.72f, positionY = 0.42f),
        Agent(role = AgentRole.GENERAL, name = "Gen", positionX = 0.40f, positionY = 0.78f)
    )

    /** Langkah 1–3. Hasilnya menunggu persetujuan user. */
    suspend fun submitCommand(
        projectId: String,
        command: String,
        projectBible: String,
        llmCall: LlmCall
    ): TaskPlan {
        resetAgents()
        _unfinished.value = emptyList()
        setStatus(AgentRole.ARCHITECT, AgentStatus.THINKING, "Menyusun rencana...")

        val planText = llmCall(
            "You are a senior software architect in PixelForge. Reply only with the plan. " +
                "Reply in the same language as the user's command. " +
                "If code is needed, list the files (with relative paths) to create or change.",
            "Project Bible:\n$projectBible\n\nUser command: $command\n\n" +
                "Create a clear, step-by-step plan. Be concise."
        )
        val plan = TaskPlan(
            projectId = projectId,
            userCommand = command,
            architectPlan = planText,
            status = TaskStatus.AWAITING_APPROVAL
        )
        _currentPlan.value = plan

        val roles = determineRelevantRoles(command)
        roles.forEach { setStatus(it, AgentStatus.REPORTING, "Menulis laporan...") }

        val approvals = coroutineScope {
            roles.map { role ->
                async {
                    val report = llmGate.withPermit {
                        llmCall(
                            "You are the ${role.name} agent. Give a very short report (2-4 sentences) " +
                                "of what you will do based on this plan:\n$planText\n" +
                                "Reply in the same language as the user's command.",
                            "Command: $command\nKeep it under 80 words."
                        )
                    }
                    ApprovalRequest(
                        taskPlanId = plan.id,
                        agentId = _agents.value.first { it.role == role }.id,
                        agentRole = role,
                        report = report
                    )
                }
            }.awaitAll()
        }

        _pendingApprovals.value = approvals
        persist()
        setStatus(AgentRole.ARCHITECT, AgentStatus.WAITING_APPROVAL, "Menunggu approval")
        roles.forEach { setStatus(it, AgentStatus.WAITING_APPROVAL, null) }
        return plan
    }

    /**
     * Langkah 5 (juga dipakai untuk melanjutkan). Semua agent jalan paralel.
     * @return true kalau semua agent selesai; false kalau masih ada yang tertunda (bisa dilanjutkan).
     */
    suspend fun execute(
        projectBible: String,
        llmCall: LlmCall,
        onResult: suspend (AgentRole, String) -> Unit,
        onFailure: suspend (AgentRole, String) -> Unit
    ): Boolean {
        val plan = _currentPlan.value ?: return true
        if (_pendingApprovals.value.isNotEmpty()) {
            _unfinished.value = _pendingApprovals.value
            _pendingApprovals.value = emptyList()
        }
        val todo = _unfinished.value
        if (todo.isEmpty()) return true

        _currentPlan.value = plan.copy(status = TaskStatus.IN_PROGRESS)
        persist()
        setStatus(AgentRole.ARCHITECT, AgentStatus.IDLE, null)
        todo.forEach { setStatus(it.agentRole, AgentStatus.WORKING, "Mengerjakan...") }

        coroutineScope {
            todo.map { a ->
                async {
                    try {
                        val out = llmGate.withPermit {
                            llmCall(
                                "You are the ${a.agentRole.name} agent in PixelForge.\n" +
                                    "Project Bible (follow strictly):\n$projectBible\n" +
                                    Workspace.FILE_FORMAT_PROMPT +
                                    "If the project can be built or tested and the workspace has no " +
                                    "file under .github/workflows/, include a GitHub Actions workflow so the build can be verified.\n" +
                                    "Reply in the same language as the user's command.",
                                "User command: ${plan.userCommand}\n\nApproved plan:\n${plan.architectPlan}\n\n" +
                                    "Your report: ${a.report}\n\n" +
                                    "Now do your part. Deliver the concrete output (files, findings, or text)."
                            )
                        }
                        onResult(a.agentRole, out)
                        _unfinished.update { l -> l.filter { it.id != a.id } }
                        persist()
                        setStatus(a.agentRole, AgentStatus.DONE, "Selesai ✓")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setStatus(a.agentRole, AgentStatus.PAUSED, "⏸ Tertunda")
                        onFailure(a.agentRole, e.message ?: e.javaClass.simpleName)
                    }
                }
            }.awaitAll()
        }
        val done = _unfinished.value.isEmpty()
        _currentPlan.update { it?.copy(status = if (done) TaskStatus.COMPLETED else TaskStatus.IN_PROGRESS) }
        persist()
        return done
    }

    fun rejectAll() {
        _pendingApprovals.value = emptyList()
        _unfinished.value = emptyList()
        _currentPlan.update { it?.copy(status = TaskStatus.REJECTED) }
        persist()
        resetAgents()
    }

    fun resetAgents() {
        _agents.update { list -> list.map { it.copy(status = AgentStatus.IDLE, bubbleText = null) } }
    }

    /** Kehidupan kantor: agent yang menganggur jalan-jalan & ngomong sendiri. */
    fun idleTick() {
        _agents.update { list ->
            list.map { a ->
                if (a.status != AgentStatus.IDLE) a
                else a.copy(
                    positionX = if (Math.random() > 0.5) wander(a.positionX, 0.02f, 0.78f) else a.positionX,
                    positionY = if (Math.random() > 0.5) wander(a.positionY, 0.05f, 0.80f) else a.positionY,
                    bubbleText = if (Math.random() > 0.65) IDLE_BUBBLES.random() else null
                )
            }
        }
    }

    private fun wander(v: Float, min: Float, max: Float) =
        (v + (Math.random().toFloat() - 0.5f) * 0.12f).coerceIn(min, max)

    private fun setStatus(role: AgentRole, status: AgentStatus, bubble: String?) {
        _agents.update { list ->
            list.map { if (it.role == role) it.copy(status = status, bubbleText = bubble) else it }
        }
    }

    private fun determineRelevantRoles(command: String): List<AgentRole> {
        val c = command.lowercase()
        fun has(vararg k: String) = k.any { c.contains(it) }
        val roles = mutableListOf<AgentRole>()
        if (has("code", "kode", "fitur", "feature", "implement", "buat", "app", "aplikasi", "api", "login")) {
            roles += listOf(AgentRole.CODER, AgentRole.AUDITOR)
        }
        if (has("bug", "error", "crash", "debug", "fix", "perbaiki")) roles += AgentRole.DEBUGGER
        if (has("audit", "review", "security", "keamanan")) roles += AgentRole.AUDITOR
        if (has("logo", "design", "desain", "content", "konten", "kreatif", "creative")) roles += AgentRole.CREATIVE
        if (has("bisnis", "business", "plan", "rencana", "strategi", "pasar", "market")) roles += AgentRole.BUSINESS
        if (roles.isEmpty()) roles += AgentRole.GENERAL
        return roles.distinct()
    }

    private companion object {
        val IDLE_BUBBLES = listOf("Ngopi dulu ☕", "Stretch...", "Hmm...", "Cek email", "Zzz")
    }
}
