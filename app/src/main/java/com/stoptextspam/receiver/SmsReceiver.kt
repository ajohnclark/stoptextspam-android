package com.stoptextspam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.stoptextspam.data.SpamDatabase
import com.stoptextspam.data.SpamMessage
import com.stoptextspam.service.SpamClassifier
import com.stoptextspam.service.SmsNotificationListener
import com.stoptextspam.util.ContactHelper
import com.stoptextspam.util.NumberBlocker
import com.stoptextspam.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = PrefsManager(context)
        if (!prefs.isEnabled()) return
        prefs.recordSmsReceived()

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val pendingResult = goAsync()

        scope.launch {
            try {
                processMessages(context, messages, prefs)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processMessages(
        context: Context,
        messages: Array<android.telephony.SmsMessage>,
        prefs: PrefsManager
    ) {
        // Group multi-part SMS by sender
        val grouped = messages.groupBy { it.originatingAddress ?: "" }

        for ((sender, parts) in grouped) {
            if (sender.isBlank()) continue

            // Skip known contacts — their messages are private and never touched
            if (ContactHelper.isKnownContact(context, sender)) {
                Log.d(TAG, "Known contact — skipping")
                continue
            }

            val body = parts.joinToString("") { it.messageBody ?: "" }
            if (body.isBlank()) continue

            Log.d(TAG, "Classifying message from unknown sender")
            val result = SpamClassifier.classify(sender, body, prefs.getApiKey())
            prefs.recordClassification(if (result.failed) "Classification failed" else if (result.isSpam) "Spam detected" else "Not spam")

            if (result.failed) {
                Log.w(TAG, "Classification failed; message left visible")
                continue
            }

            if (result.isSpam) {
                Log.i(TAG, "Spam detected")

                // Mark sender as confirmed spam — cancels snoozed notification
                SmsNotificationListener.onSpamConfirmed(sender)

                // Save to database
                val db = SpamDatabase.getInstance(context)
                db.spamMessageDao().insert(
                    SpamMessage(
                        sender = sender,
                        body = body,
                        reason = result.reason,
                        timestamp = System.currentTimeMillis()
                    )
                )

                // Try to block (works if we're default SMS/dialer app)
                val blocked = NumberBlocker.blockNumber(context, sender)
                Log.d(TAG, "Block result: $blocked")

                // Show our spam notification
                NumberBlocker.showBlockedNotification(context, sender)
            } else {
                Log.d(TAG, "Message classified as not spam")
                // Don't re-notify — let Google Messages handle it normally
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
