package com.pixelforge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean
)

@Entity(tableName = "project_bibles")
data class ProjectBibleEntity(
    @PrimaryKey val projectId: String,
    val content: String,
    val updatedAt: Long
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val agentId: String?,
    val role: String,
    val content: String,
    val type: String,
    val timestamp: Long
)

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val role: String,
    val name: String,
    val status: String,
    val currentTaskId: String?,
    val positionX: Float,
    val positionY: Float,
    val bubbleText: String?,
    val lastActivity: Long
)
