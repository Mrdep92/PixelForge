package com.pixelforge.engine

import com.google.gson.Gson

/** Satu kejadian dalam run. Run adalah hasil membaca log ini, bukan status di memori. */
data class Event(
    val type: String,
    val ticket: String? = null,
    val detail: String? = null,
    val calls: Int = 0,
    val ts: Long = 0L
) {
    companion object {
        const val BLUEPRINT_APPROVED = "BLUEPRINT_APPROVED"
        const val TICKET_STARTED = "TICKET_STARTED"
        const val TICKET_PASSED = "TICKET_PASSED"
        const val TICKET_FAILED = "TICKET_FAILED"
        const val TICKET_SKIPPED = "TICKET_SKIPPED"
        const val PAUSED = "PAUSED"
        const val BUDGET_STOPPED = "BUDGET_STOPPED"
    }
}

interface EventLog {
    fun append(e: Event)
    fun all(): List<Event>
}

class InMemoryEventLog(initial: List<Event> = emptyList()) : EventLog {
    private val items = initial.toMutableList()
    override fun append(e: Event) { items += e }
    override fun all(): List<Event> = items.toList()

    /** Satu baris JSON per kejadian (untuk disimpan ke disk / Room). */
    fun toLines(): String = items.joinToString("\n") { gson.toJson(it) }

    companion object {
        private val gson = Gson()
        fun fromLines(text: String): InMemoryEventLog =
            InMemoryEventLog(text.lines().filter { it.isNotBlank() }.mapNotNull { runCatching { gson.fromJson(it, Event::class.java) }.getOrNull() })
    }
}

/** Keadaan run yang diturunkan dari log. */
data class RunState(val passed: Set<String>, val failed: Map<String, String>, val calls: Int) {
    companion object {
        fun from(events: List<Event>): RunState {
            val passed = linkedSetOf<String>()
            val failed = linkedMapOf<String, String>()
            var calls = 0
            for (e in events) {
                calls = maxOf(calls, e.calls)
                val t = e.ticket ?: continue
                when (e.type) {
                    Event.TICKET_PASSED -> { passed += t; failed.remove(t) }
                    Event.TICKET_FAILED, Event.TICKET_SKIPPED -> { passed -= t; failed[t] = e.detail ?: "" }
                }
            }
            return RunState(passed, failed, calls)
        }
    }
}
