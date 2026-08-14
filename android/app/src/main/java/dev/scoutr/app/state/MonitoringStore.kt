package dev.scoutr.app.state

import android.content.Context

/**
 * Opt-in, time-bounded background monitoring: a foreground service keeps the
 * ntfy poll alive while the app is backgrounded so blocked/done events reach
 * the notification shade. Android 15 ends a data-sync session after six
 * background hours; the service clears this flag when that timeout fires.
 */
class MonitoringStore(context: Context) {

    private val prefs = context.getSharedPreferences("scoutr_monitoring", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** Last ntfy message id the service has shown, so re-polling never repeats. */
    var ntfyCursor: String?
        get() = prefs.getString(KEY_CURSOR, null)
        set(value) {
            prefs.edit().putString(KEY_CURSOR, value).apply()
        }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_CURSOR = "ntfyCursor"
    }
}
