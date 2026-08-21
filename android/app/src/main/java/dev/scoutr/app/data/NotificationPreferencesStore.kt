package dev.scoutr.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Device-global notification preferences — whether a push should ring/display.
 *
 * Two toggles: "needs you" (blocked) and "finished" (done). Both survive
 * Forget, like AppearancePreferencesStore, because they describe how the user
 * likes the device to behave, not who it's paired with. The bridge already
 * separates the two pings (`blocked` vs `done`) on distinct FCM kinds; these
 * flags gate only the local post, so a disabled toggle is cheap — the wake fetch
 * still runs but posts nothing.
 */
class NotificationPreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var blockedEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCKED, DEFAULT_BLOCKED)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKED, value).apply()

    var doneEnabled: Boolean
        get() = prefs.getBoolean(KEY_DONE, DEFAULT_DONE)
        set(value) = prefs.edit().putBoolean(KEY_DONE, value).apply()

    companion object {
        const val FILE = "scoutr_notifications"

        const val DEFAULT_BLOCKED = true
        const val DEFAULT_DONE = false

        private const val KEY_BLOCKED = "notificationsBlockedEnabled"
        private const val KEY_DONE = "notificationsDoneEnabled"
    }
}
