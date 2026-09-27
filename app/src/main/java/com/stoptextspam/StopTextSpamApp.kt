package com.stoptextspam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.stoptextspam.data.SpamDatabase

class StopTextSpamApp : Application() {

    val database: SpamDatabase by lazy { SpamDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Spam Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications when spam messages are detected and blocked"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "spam_alerts"
    }
}
