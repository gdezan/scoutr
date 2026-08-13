package dev.scoutr.app.state

import android.content.Context

/** Remembers the last reviewed repo so the review center can offer a quick reopen. */
class ReviewStore(context: Context) {

    private val prefs = context.getSharedPreferences("scoutr_review", Context.MODE_PRIVATE)

    var lastRepoPath: String?
        get() = prefs.getString(KEY_LAST_REPO, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_LAST_REPO, value).apply()
        }

    private companion object {
        const val KEY_LAST_REPO = "lastRepoPath"
    }
}
