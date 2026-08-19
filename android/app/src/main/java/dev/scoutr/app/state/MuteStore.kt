package dev.scoutr.app.state

import android.content.Context

/**
 * Panes the user has told Scoutr to stop interrupting for.
 *
 * Muting is keyed on the pane, not the agent or the workspace: the thing the
 * user silenced is *this* run, and a pane id dies with it. That is also what
 * makes the mute self-cleaning — [prune] drops every id the bridge no longer
 * reports, so a mute never outlives the thing it was about and there is no
 * settings screen to un-mute from.
 *
 * Read from the FCM service thread and written from a broadcast receiver;
 * SharedPreferences is the synchronization.
 */
class MuteStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isMuted(paneId: String): Boolean = paneId in muted()

    fun mute(paneId: String) {
        prefs.edit().putStringSet(KEY_PANES, muted() + paneId).commit()
    }

    /** Forget mutes for panes the bridge no longer reports. */
    fun prune(livePaneIds: Set<String>) {
        val current = muted()
        val kept = current.filterTo(mutableSetOf()) { it in livePaneIds }
        if (kept.size != current.size) prefs.edit().putStringSet(KEY_PANES, kept).commit()
    }

    // Defensive copy: SharedPreferences hands back its own instance, and
    // mutating it would corrupt the cached value without ever persisting.
    private fun muted(): Set<String> = prefs.getStringSet(KEY_PANES, emptySet())?.toSet().orEmpty()

    internal companion object {
        const val FILE = "scoutr_mutes"
        const val KEY_PANES = "mutedPaneIds"
    }
}
