package com.pixelforge.data.local.dao

import androidx.room.*
import com.pixelforge.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProject(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE projectId = :projectId ORDER BY timestamp ASC")
    fun getMessages(projectId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE projectId = :projectId")
    suspend fun clearProject(projectId: String)
}

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents")
    fun getAllAgents(): Flow<List<AgentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(agents: List<AgentEntity>)

    @Update
    suspend fun update(agent: AgentEntity)
}

@Dao
interface BibleDao {
    @Query("SELECT * FROM project_bibles WHERE projectId = :projectId")
    suspend fun get(projectId: String): ProjectBibleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bible: ProjectBibleEntity)
}
