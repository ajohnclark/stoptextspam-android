package com.stoptextspam.util

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("stoptextspam", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun recordSmsReceived() = prefs.edit().putLong("last_sms", System.currentTimeMillis()).apply()
    fun lastSmsReceived(): Long = prefs.getLong("last_sms", 0L)
    fun recordClassification(status: String) = prefs.edit()
        .putString("last_classification", status)
        .putLong("last_classification_time", System.currentTimeMillis()).apply()
    fun lastClassification(): String = prefs.getString("last_classification", "No classification recorded")!!
    fun lastClassificationTime(): Long = prefs.getLong("last_classification_time", 0L)

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    fun setApiKey(key: String) = prefs.edit().putString(KEY_API_KEY, key).apply()

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_API_KEY = "api_key"
    }
}
