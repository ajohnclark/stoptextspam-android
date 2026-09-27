package com.stoptextspam.util

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.BlockedNumberContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.stoptextspam.MainActivity
import com.stoptextspam.StopTextSpamApp

object NumberBlocker {

    private const val TAG = "NumberBlocker"

    /**
     * Attempts to block a number via the system BlockedNumberContract.
     * This only works if the app is the default SMS or dialer app.
     * Returns true if blocked, false if not (SecurityException).
     */
    fun blockNumber(context: Context, number: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
            }
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                values
            )
            Log.i(TAG, "Blocked a spam number")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot block number — not default SMS/dialer app")
            false
        }
    }

    fun showBlockedNotification(context: Context, sender: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, StopTextSpamApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Spam detected")
            .setContentText("Open StopTextSpam to review")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                sender.hashCode(),
                notification
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post notification — missing POST_NOTIFICATIONS permission")
        }
    }

}
