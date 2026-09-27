package com.stoptextspam.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object ContactHelper {

    fun isKnownContact(context: Context, phoneNumber: String): Boolean {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { cursor ->
                cursor.moveToFirst()
            } ?: true // Unknown lookup status: do not treat a private contact as a stranger.
        } catch (e: Exception) {
            true
        }
    }
}
