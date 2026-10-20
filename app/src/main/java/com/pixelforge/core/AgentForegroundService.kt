package com.pixelforge.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pixelforge.MainActivity
import com.pixelforge.PixelForgeApp
import com.pixelforge.R

/**
 * Basic foreground service so agents can keep working while app is in background.
 * Expand later with real WorkManager integration.
 */
class AgentForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("PixelForge agents are working...")
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PixelForgeApp.CHANNEL_AGENT)
            .setContentTitle("PixelForge")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_manage) // replace with real icon later
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
