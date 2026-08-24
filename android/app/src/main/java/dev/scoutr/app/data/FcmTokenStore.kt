package dev.scoutr.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Durable Firebase token so host registration can resume after process restart. */
class FcmTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mutableToken = MutableStateFlow(prefs.getString(KEY_TOKEN, null))
    val token: StateFlow<String?> = mutableToken.asStateFlow()

    fun update(value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) return
        check(prefs.edit().putString(KEY_TOKEN, clean).commit()) { "Could not persist FCM token" }
        mutableToken.value = clean
    }

    private companion object {
        const val FILE = "scoutr_fcm_token"
        const val KEY_TOKEN = "token"
    }
}
