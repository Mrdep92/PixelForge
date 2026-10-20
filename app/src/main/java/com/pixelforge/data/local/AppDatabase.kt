package com.pixelforge.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.pixelforge.data.local.dao.AgentDao
import com.pixelforge.data.local.dao.BibleDao
import com.pixelforge.data.local.dao.MessageDao
import com.pixelforge.data.local.dao.ProjectDao
import com.pixelforge.data.local.entity.*

@Database(
    entities = [
        ProjectEntity::class,
        ProjectBibleEntity::class,
        MessageEntity::class,
        AgentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun messageDao(): MessageDao
    abstract fun agentDao(): AgentDao
    abstract fun bibleDao(): BibleDao
}
