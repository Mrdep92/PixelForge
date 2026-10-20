package com.pixelforge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.room.Room
import com.pixelforge.core.SecureStore
import com.pixelforge.data.local.AppDatabase

class PixelForgeApp : Application() {

    val db: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "pixelforge.db").build()
    }
    val store: SecureStore by lazy { SecureStore(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_AGENT,
                "Agent Activity",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when agents need approval or finish tasks"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_AGENT = "pixelforge_agent_channel"
    }
}
