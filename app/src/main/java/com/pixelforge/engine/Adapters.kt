package com.pixelforge.engine

import com.google.gson.Gson
import com.pixelforge.core.StateStore
import com.pixelforge.routing.LlmRouter
import com.pixelforge.workspace.Workspace

/** Menghubungkan mesin ke gerbang LLM aplikasi (kolam penyedia, jatah, filter rahasia). */
class RouterLlm(private val router: LlmRouter) : Llm {
    override suspend fun call(system: String, user: String): String = router.chat(system, user)
}

/** Menghubungkan mesin ke folder project di penyimpanan aplikasi. */
class WorkspaceStore(private val ws: Workspace) : FileStore {
    override fun read(path: String): String? = if (ws.exists(path)) ws.read(path) else null
    override fun write(path: String, content: String) {
        ws.write(listOf(Workspace.FileOut(path, content)))
    }
    override fun all(): Map<String, String> = ws.readAll()
    override fun delete(path: String) { ws.delete(path) }
}

/** Log kejadian yang tersimpan di penyimpanan aplikasi, supaya run bisa dilanjutkan setelah aplikasi ditutup. */
class PersistentEventLog(private val store: StateStore, private val key: String) : EventLog {
    private val mem = InMemoryEventLog.fromLines(store.loadRaw(key).orEmpty())
    override fun append(e: Event) {
        mem.append(e)
        store.saveRaw(key, mem.toLines())
    }
    override fun all(): List<Event> = mem.all()
}

/** Keadaan alur per project, disimpan sebagai JSON. */
object FlowStateStore {
    private val gson = Gson()
    fun load(store: StateStore, projectId: String): FlowState =
        runCatching { store.loadRaw("flow:$projectId")?.let { gson.fromJson(it, FlowState::class.java) } }.getOrNull() ?: FlowState()

    fun save(store: StateStore, projectId: String, s: FlowState) = store.saveRaw("flow:$projectId", gson.toJson(s))
}
