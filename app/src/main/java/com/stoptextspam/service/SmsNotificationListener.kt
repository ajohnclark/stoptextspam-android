package com.stoptextspam.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.stoptextspam.util.PrefsManager
import com.stoptextspam.util.ContactHelper
import com.stoptextspam.util.NumberBlocker
import com.stoptextspam.data.SpamDatabase
import com.stoptextspam.data.SpamMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SmsNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val recordedPhotos = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val TAG = "SmsNotifListener"
        private const val SNOOZE_DURATION_MS = 15_000L // 15s — covers primary + fallback API

        private val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"
        )

        // Service instance for cancelling snoozed notifications from SmsReceiver
        var instance: SmsNotificationListener? = null
            private set

        // Senders confirmed as spam by the classifier
        val confirmedSpamSenders = ConcurrentHashMap.newKeySet<String>()

        // Notification keys currently snoozed (to detect re-post after snooze expires)
        private val snoozedKeys = ConcurrentHashMap.newKeySet<String>()

        // Sender (notification title) → sbn.key for active snoozes
        private val snoozedSenderKeys = ConcurrentHashMap<String, String>()

        /**
         * Called by SmsReceiver when spam is confirmed.
         * Tries to cancel the snoozed notification immediately (before snooze expires).
         */
        fun onSpamConfirmed(senderNumber: String) {
            confirmedSpamSenders.add(senderNumber)

            val inst = instance ?: run {
                Log.d(TAG, "No listener instance — spam will be caught on unsnooze")
                return
            }

            // Find and cancel matching snoozed notification
            val keysToRemove = snoozedSenderKeys.entries.filter { (sender, _) ->
                matchesSender(sender, senderNumber)
            }
            for ((sender, key) in keysToRemove) {
                try {
                    inst.cancelNotification(key)
                    snoozedKeys.remove(key)
                    snoozedSenderKeys.remove(sender)
                    Log.d(TAG, "Cancelled snoozed spam notification")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cancel snoozed notification")
                }
            }
        }

        /**
         * Match phone numbers without conflating unrelated last-four-digit suffixes.
         */
        private fun matchesSender(notifTitle: String, phoneNumber: String): Boolean {
            return android.telephony.PhoneNumberUtils.compare(notifTitle, phoneNumber)
        }

        /**
         * Google Messages shows contact names (letters) for saved contacts,
         * and phone numbers (digits) for unknown senders.
         */
        private fun looksLikePhoneNumber(title: String): Boolean {
            val stripped = title.replace(Regex("[+\\-()\\s.]"), "")
            if (stripped.isEmpty()) return false
            val digitCount = stripped.count { it.isDigit() }
            return digitCount >= 3 && digitCount.toFloat() / stripped.length >= 0.7f
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.d(TAG, "Notification listener disconnected")
        requestRebind(ComponentName(this, SmsNotificationListener::class.java))
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = PrefsManager(this)
        if (!prefs.isEnabled()) return
        if (sbn.packageName !in SMS_PACKAGES) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val sender = title.trim()

        // Title has letters → contact name (Google Messages resolved it) → never touch
        if (!looksLikePhoneNumber(sender)) {
            Log.d(TAG, "Contact name detected — skipping")
            return
        }

        // A numeric notification title alone is not proof that this is a stranger.
        if (ContactHelper.isKnownContact(this, sender)) return

        val style = androidx.core.app.NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn.notification)
        val messages = style?.messages.orEmpty()
        // Group notifications cannot safely be attributed to the title's number.
        if (style?.isGroupConversation == true) return
        if (MessageRules.photoIsSpam(false, messages.map { it.dataMimeType })) {
            // No image download or AI call: dismiss as soon as attachment metadata arrives.
            cancelNotification(sbn.key)
            onSpamConfirmed(sender)
            val photo = messages.last { it.dataMimeType?.startsWith("image/", true) == true }
            val identity = "${sbn.key}:${photo.timestamp}:${photo.dataUri}"
            if (recordedPhotos.add(identity)) {
                scope.launch {
                    try {
                        val reason = "Photo from an unknown sender"
                        SpamDatabase.getInstance(applicationContext).spamMessageDao().insert(
                            SpamMessage(sender = sender, body = "[Photo]", reason = reason,
                                timestamp = photo.timestamp.takeIf { it > 0 } ?: sbn.postTime)
                        )
                        PrefsManager(applicationContext).recordClassification(reason)
                        NumberBlocker.blockNumber(applicationContext, sender)
                    } catch (e: Exception) {
                        recordedPhotos.remove(identity)
                        Log.e(TAG, "Could not save photo spam event")
                    }
                }
            }
            return
        }

        // Notification re-appearing after snooze expired
        if (snoozedKeys.remove(sbn.key)) {
            snoozedSenderKeys.entries.removeIf { it.value == sbn.key }
            if (confirmedSpamSenders.any { matchesSender(sender, it) }) {
                cancelNotification(sbn.key)
                Log.d(TAG, "Spam confirmed during snooze — cancelled")
            } else {
                Log.d(TAG, "Not spam — letting notification through")
            }
            return
        }

        // Already known spam sender (repeat offender) → dismiss immediately
        if (confirmedSpamSenders.any { matchesSender(sender, it) }) {
            cancelNotification(sbn.key)
            Log.d(TAG, "Known spam sender — dismissed")
            return
        }

        // Without a key, leave ordinary messages visible without a classification delay.
        if (prefs.getApiKey().isBlank()) return

        // Unknown sender, first time → snooze to suppress ding while classifier runs
        try {
            snoozeNotification(sbn.key, SNOOZE_DURATION_MS)
            snoozedKeys.add(sbn.key)
            snoozedSenderKeys[sender] = sbn.key
            Log.d(TAG, "Snoozed notification from unknown sender")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to snooze notification")
        }
    }
}
