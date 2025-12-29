package com.vndict

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class VNDictApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "vndict_overlay_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for OCR overlay service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
