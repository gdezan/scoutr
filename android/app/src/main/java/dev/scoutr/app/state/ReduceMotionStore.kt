package dev.scoutr.app.state

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirrors the system "Remove animations" setting (animator duration scale 0)
 * into a StateFlow. The app reads it once at startup and stays in sync while
 * alive; a later settings screen could layer a per-app override on top.
 */
class ReduceMotionStore(context: Context) {

    private val contentResolver = context.contentResolver
    private val _reduceMotion = MutableStateFlow(readScale(context))
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            _reduceMotion.value = readScale(context)
        }
    }

    init {
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
    }

    fun close() {
        contentResolver.unregisterContentObserver(observer)
    }

    private fun readScale(context: Context): Boolean =
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (_: SecurityException) {
            false
        }
}
