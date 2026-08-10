package dev.cockpit.app.state

import android.content.Context

/**
 * Opt-in background monitoring: when enabled, a foreground service keeps the
 * ntfy poll alive while the app is backgrounded so blocked/done events reach
 * the notification shade with a deep link and an inline reply. Disabled by
 * default; the toggle lives in Settings.
 */
class MonitoringStore(context: Context) {

    private val prefs = context.getSharedPreferences("cockpit_monitoring", Context.MODE_PRIVATE)

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
