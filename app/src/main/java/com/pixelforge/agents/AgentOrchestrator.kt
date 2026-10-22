package com.pixelforge.agents

import com.pixelforge.core.StateStore
import com.pixelforge.domain.model.*
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
 * Flow hemat kuota (v0.5):
 * 1. Satu panggilan Architect → rencana terstruktur + tugas tiap agen
 * 2. User Approve
 * 3. Pipeline: Coder → Auditor (max 1 putaran perbaikan) + agen lain opsional
 *
 * Agent yang gagal (limit/jaringan) tidak hilang: tetap di daftar `unfinished`.
 */
class AgentOrchestrator(
    private val store: StateStore? = null,
    private val projectId: String = "default"
) {
    private val key = "orch:$projectId"

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
        val s = store?.load(key, SavedOrch::class.java)
            ?: if (projectId == "default") store?.load("orch", SavedOrch::class.java) else null
        if (s != null) {
            _currentPlan.value = s.plan
            _pendingApprovals.value = s.pending ?: emptyList()
            _unfinished.value = s.unfinished ?: emptyList()
            _pendingApprovals.value.forEach { setStatus(it.agentRole, AgentStatus.WAITING_APPROVAL, null) }
            _unfinished.value.forEach { setStatus(it.agentRole, AgentStatus.PAUSED, "⏸ Tertunda") }
        }
    }

    private fun persist() {
        store?.save(key, SavedOrch(_currentPlan.value, _pendingApprovals.value, _unfinished.value))
    }

    fun announce(role: AgentRole, status: AgentStatus, bubble: String?) = setStatus(role, status, bubble)

    fun hasWork(): Boolean = _pendingApprovals.value.isNotEmpty() || _unfinished.value.isNotEmpty()

    private fun createDefaultAgents(): List<Agent> = listOf(
        Agent(role = AgentRole.ARCHITECT, name = "Arch", positionX = 0.08f, positionY = 0.08f),
        Agent(role = AgentRole.CODER, name = "Code", positionX = 0.40f, positionY = 0.15f),
        Agent(role = AgentRole.AUDITOR, name = "Audit", positionX = 0.72f, positionY = 0.08f),
        Agent(role = AgentRole.DEBUGGER, name = "Debug", positionX = 0.08f, positionY = 0.42f),
        Agent(role = AgentRole.CREATIVE, name = "Creat", positionX = 0.40f, positionY = 0.50f),
        Agent(role = AgentRole.BUSINESS, name = "Biz", positionX = 0.72f, positionY = 0.42f),
        Agent(role = AgentRole.GENERAL, name = "Gen", positionX = 0.40f, positionY = 0.78f),
        Agent(role = AgentRole.REPO, name = "Repo", positionX = 0.72f, positionY = 0.78f)
    )

    /**
     * Langkah 1: **satu** panggilan LLM.
     * Architect mengeluarkan rencana + daftar tugas terstruktur.
     * Tidak ada laporan per-agen tambahan → hemat kuota besar.
     */
    suspend fun submitCommand(
        projectId: String,
        command: String,
        context: String,
        llmCall: LlmCall
    ): TaskPlan {
        resetAgents()
        _unfinished.value = emptyList()
        setStatus(AgentRole.ARCHITECT, AgentStatus.THINKING, "Menyusun rencana...")

        val system = """
            You are the Architect of PixelForge, a mobile multi-agent office.
            Reply ONLY with a valid JSON object (no markdown fences, no extra text).
            Language of all text fields must match the user's command language.

            Schema:
            {
              "plan": "short step-by-step plan (max 120 words)",
              "tasks": [
                {"role": "CODER"|"AUDITOR"|"DEBUGGER"|"CREATIVE"|"BUSINESS"|"GENERAL", "task": "what this agent must do", "files": ["optional/path"]}
              ],
              "estimated_calls": 3
            }

            Rules:
            - Prefer CODER + AUDITOR for any coding work.
            - AUDITOR task must say it will review the Coder output.
            - Keep estimated_calls realistic (1 plan already used + 1 per agent that will run + optional 1 fix round).
            - Max 4 tasks.
        """.trimIndent()

        val user = buildString {
            if (context.isNotBlank()) append("Context:\n$context\n\n")
            append("User command: $command")
        }

        val raw = llmCall(system, user)
        val parsed = parseArchitectJson(raw, command)

        val plan = TaskPlan(
            projectId = projectId,
            userCommand = command,
            architectPlan = parsed.planText,
            tasks = parsed.tasks,
            estimatedCalls = parsed.estimatedCalls,
            status = TaskStatus.AWAITING_APPROVAL
        )
        _currentPlan.value = plan

        // Buat ApprovalRequest dari tasks (tanpa LLM tambahan)
        val approvals = parsed.tasks.map { t ->
            val agent = _agents.value.firstOrNull { it.role == t.role }
                ?: _agents.value.first { it.role == AgentRole.GENERAL }
            ApprovalRequest(
                taskPlanId = plan.id,
                agentId = agent.id,
                agentRole = t.role,
                report = t.task,
                estimatedSteps = t.files
            )
        }.ifEmpty {
            // Fallback jika parsing gagal total
            listOf(
                ApprovalRequest(
                    taskPlanId = plan.id,
                    agentId = _agents.value.first { it.role == AgentRole.GENERAL }.id,
                    agentRole = AgentRole.GENERAL,
                    report = parsed.planText.take(200)
                )
            )
        }

        _pendingApprovals.value = approvals
        persist()
        setStatus(AgentRole.ARCHITECT, AgentStatus.WAITING_APPROVAL, "Menunggu approval")
        approvals.forEach { setStatus(it.agentRole, AgentStatus.WAITING_APPROVAL, null) }
        return plan
    }

    /**
     * Langkah 2: eksekusi setelah Approve.
     * Pipeline: Coder dulu → Auditor (dengan hasil Coder) → max 1 putaran perbaikan.
     * Agen lain (Creative, Business, …) jalan paralel setelah pipeline inti.
     */
    suspend fun execute(
        context: String,
        runner: suspend (AgentRole, String, String) -> String,
        onResult: suspend (AgentRole, String) -> Unit,
        onFailure: suspend (AgentRole, String) -> Unit
    ): Boolean {
        val plan = _currentPlan.value ?: return true
        if (_pendingApprovals.value.isNotEmpty()) {
            _unfinished.value = _pendingApprovals.value
            _pendingApprovals.value = emptyList()
        }
        val todo = _unfinished.value.toMutableList()
        if (todo.isEmpty()) return true

        _currentPlan.value = plan.copy(status = TaskStatus.IN_PROGRESS)
        persist()
        setStatus(AgentRole.ARCHITECT, AgentStatus.IDLE, null)

        val coderReq = todo.find { it.agentRole == AgentRole.CODER }
        val auditorReq = todo.find { it.agentRole == AgentRole.AUDITOR }
        val others = todo.filter { it.agentRole != AgentRole.CODER && it.agentRole != AgentRole.AUDITOR }

        var coderOutput: String? = null

        // --- Pipeline Coder → Auditor (max 1 fix) ---
        if (coderReq != null) {
            try {
                setStatus(AgentRole.CODER, AgentStatus.WORKING, "Coding...")
                val taskText = plan.tasks.find { it.role == AgentRole.CODER }?.task ?: coderReq.report
                coderOutput = llmGate.withPermit {
                    runner(
                        AgentRole.CODER,
                        "You are the CODER agent in PixelForge.\nContext:\n$context\nReply in the same language as the user.",
                        "Command: ${plan.userCommand}\n\nPlan:\n${plan.architectPlan}\n\nYour task: $taskText\n\nDeliver concrete output (code, files, or clear steps)."
                    )
                }
                onResult(AgentRole.CODER, coderOutput)
                _unfinished.update { l -> l.filter { it.id != coderReq.id } }
                persist()
                setStatus(AgentRole.CODER, AgentStatus.DONE, "Selesai ✓")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(AgentRole.CODER, AgentStatus.PAUSED, "⏸ Tertunda")
                onFailure(AgentRole.CODER, e.message ?: e.javaClass.simpleName)
            }
        }

        if (auditorReq != null && coderOutput != null) {
            try {
                setStatus(AgentRole.AUDITOR, AgentStatus.WORKING, "Review...")
                val auditTask = plan.tasks.find { it.role == AgentRole.AUDITOR }?.task ?: auditorReq.report
                val auditOut = llmGate.withPermit {
                    runner(
                        AgentRole.AUDITOR,
                        "You are the AUDITOR agent. Review the Coder output strictly.\nContext:\n$context\nReply in the same language as the user.",
                        "Command: ${plan.userCommand}\n\nPlan:\n${plan.architectPlan}\n\nYour task: $auditTask\n\nCoder output:\n$coderOutput\n\n" +
                            "If the work is good, say APPROVED and summarize. If it needs a fix, say NEEDS_FIX and list exact changes (max 5 bullets)."
                    )
                }
                onResult(AgentRole.AUDITOR, auditOut)
                _unfinished.update { l -> l.filter { it.id != auditorReq.id } }
                persist()

                val needsFix = auditOut.contains("NEEDS_FIX", ignoreCase = true) ||
                    auditOut.contains("perlu perbaikan", ignoreCase = true)

                if (needsFix && coderReq != null) {
                    // Max 1 putaran perbaikan
                    setStatus(AgentRole.CODER, AgentStatus.WORKING, "Perbaikan...")
                    val fixOut = llmGate.withPermit {
                        runner(
                            AgentRole.CODER,
                            "You are the CODER agent. Apply the Auditor's requested fixes only.\nContext:\n$context",
                            "Original command: ${plan.userCommand}\n\nPrevious output:\n$coderOutput\n\nAuditor feedback:\n$auditOut\n\nDeliver the corrected output."
                        )
                    }
                    onResult(AgentRole.CODER, fixOut)
                    setStatus(AgentRole.CODER, AgentStatus.DONE, "Diperbaiki ✓")
                }
                setStatus(AgentRole.AUDITOR, AgentStatus.DONE, "Selesai ✓")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(AgentRole.AUDITOR, AgentStatus.PAUSED, "⏸ Tertunda")
                onFailure(AgentRole.AUDITOR, e.message ?: e.javaClass.simpleName)
            }
        } else if (auditorReq != null && coderOutput == null) {
            // Tidak ada hasil Coder → Auditor tetap di unfinished
            setStatus(AgentRole.AUDITOR, AgentStatus.PAUSED, "Menunggu Coder")
        }

        // --- Agen lain (paralel, opsional) ---
        if (others.isNotEmpty()) {
            others.forEach { setStatus(it.agentRole, AgentStatus.WORKING, "Mengerjakan...") }
            coroutineScope {
                others.map { a ->
                    async {
                        try {
                            val taskText = plan.tasks.find { it.role == a.agentRole }?.task ?: a.report
                            val out = llmGate.withPermit {
                                runner(
                                    a.agentRole,
                                    "You are the ${a.agentRole.name} agent in PixelForge.\nContext:\n$context\nReply in the same language as the user.",
                                    "Command: ${plan.userCommand}\n\nPlan:\n${plan.architectPlan}\n\nYour task: $taskText\n\nDeliver your output."
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

    private fun setStatus(role: AgentRole, status: AgentStatus, bubble: String?) {
        _agents.update { list ->
            list.map {
                if (it.role == role) it.copy(status = status, bubbleText = bubble, lastActivity = System.currentTimeMillis())
                else it
            }
        }
    }

    // ---------- parsing single-call Architect ----------

    private data class ParsedPlan(
        val planText: String,
        val tasks: List<AgentTask>,
        val estimatedCalls: Int
    )

    private fun parseArchitectJson(raw: String, fallbackCommand: String): ParsedPlan {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        return try {
            // Parsing sederhana tanpa dependency JSON library tambahan (gunakan regex/heuristik)
            val planMatch = Regex(""""plan"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(cleaned)
            val planText = planMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"")
                ?: cleaned.take(400)

            val taskBlocks = Regex(
                """\{[^{}]*"role"\s*:\s*"([A-Z]+)"[^{}]*"task"\s*:\s*"((?:[^"\\]|\\.)*)"[^{}]*\}"""
            ).findAll(cleaned)

            val tasks = taskBlocks.mapNotNull { m ->
                val roleStr = m.groupValues[1]
                val task = m.groupValues[2].replace("\\n", "\n").replace("\\\"", "\"")
                val role = runCatching { AgentRole.valueOf(roleStr) }.getOrNull() ?: return@mapNotNull null
                AgentTask(role = role, task = task)
            }.toList()

            val estMatch = Regex(""""estimated_calls"\s*:\s*(\d+)""").find(cleaned)
            val est = estMatch?.groupValues?.get(1)?.toIntOrNull() ?: (1 + tasks.size.coerceAtLeast(1))

            ParsedPlan(
                planText = planText.ifBlank { "Rencana untuk: $fallbackCommand" },
                tasks = tasks.ifEmpty { listOf(AgentTask(AgentRole.GENERAL, planText.take(200))) },
                estimatedCalls = est.coerceIn(1, 12)
            )
        } catch (e: Exception) {
            ParsedPlan(
                planText = cleaned.take(400).ifBlank { "Rencana untuk: $fallbackCommand" },
                tasks = listOf(AgentTask(AgentRole.GENERAL, cleaned.take(200))),
                estimatedCalls = 2
            )
        }
    }
}
