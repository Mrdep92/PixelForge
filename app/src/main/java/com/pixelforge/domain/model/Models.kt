package com.pixelforge.domain.model

import java.util.UUID

enum class AgentRole {
    ARCHITECT,
    CODER,
    AUDITOR,
    DEBUGGER,
    CREATIVE,
    BUSINESS,
    GENERAL
}

enum class AgentStatus {
    IDLE,
    THINKING,
    REPORTING,
    WORKING,
    WAITING_APPROVAL,
    PAUSED,
    DONE
}

enum class TaskStatus {
    PENDING,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

enum class MessageType {
    USER,
    AGENT,
    SYSTEM,
    APPROVAL_REQUEST,
    BUBBLE
}

data class Agent(
    val id: String = UUID.randomUUID().toString(),
    val role: AgentRole,
    val name: String,
    val status: AgentStatus = AgentStatus.IDLE,
    val currentTaskId: String? = null,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val bubbleText: String? = null,
    val lastActivity: Long = System.currentTimeMillis()
)

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

data class ProjectBible(
    val projectId: String,
    val content: String, // Markdown
    val updatedAt: Long = System.currentTimeMillis()
)

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val agentId: String? = null,
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val type: MessageType = MessageType.USER,
    val timestamp: Long = System.currentTimeMillis()
)

data class TaskPlan(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val userCommand: String,
    val architectPlan: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)

data class ApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val taskPlanId: String,
    val agentId: String,
    val agentRole: AgentRole,
    val report: String,
    val estimatedSteps: List<String> = emptyList(),
    val status: TaskStatus = TaskStatus.AWAITING_APPROVAL,
    val createdAt: Long = System.currentTimeMillis()
)

data class OfficeEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "meeting", "bug_alert", "coffee", etc
    val message: String,
    val agentIds: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)
