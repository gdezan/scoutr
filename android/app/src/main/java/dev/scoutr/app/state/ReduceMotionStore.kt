package dev.scoutr.app.state

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.scoutr.app.data.AppearancePreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Combines Android's system "Remove animations" setting with the app-level
 * Settings preference into one motion decision. Either setting enables reduced
 * motion, and both sources are observed while the app is alive.
 */
class ReduceMotionStore(context: Context) {

    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val appearancePreferences = AppearancePreferencesStore(appContext)
    private val appearancePrefs = appContext.getSharedPreferences(
        AppearancePreferencesStore.FILE,
        Context.MODE_PRIVATE,
    )
    private val _reduceMotion = MutableStateFlow(readReduceMotion())
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    private val systemObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh()
        }
    }

    private val appearanceObserver = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refresh()
    }

    init {
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            systemObserver,
        )
        appearancePrefs.registerOnSharedPreferenceChangeListener(appearanceObserver)
    }

    fun close() {
        contentResolver.unregisterContentObserver(systemObserver)
        appearancePrefs.unregisterOnSharedPreferenceChangeListener(appearanceObserver)
    }

    private fun refresh() {
        _reduceMotion.value = readReduceMotion()
    }

    private fun readReduceMotion(): Boolean =
        appearancePreferences.reduceMotionEnabled || readSystemReduceMotion()

    private fun readSystemReduceMotion(): Boolean =
        try {
            Settings.Global.getFloat(
                appContext.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (_: SecurityException) {
            false
        }
}
