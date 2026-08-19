package dev.scoutr.app.state

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether the user is currently looking at Scoutr.
 *
 * A push that arrives while the board is open would post a notification for
 * something already on screen, so the FCM path asks here first. The counter is
 * of *started* activities rather than resumed ones: a partially covered
 * activity (a dialog, the shade pulled down) still counts as the user being in
 * the app.
 *
 * [onEnterForeground] fires on the transition, not on every start, and is
 * where the reconcile against the bridge hangs — the backstop for a resolve
 * ping that never arrived.
 */
object ForegroundTracker {

    private var startedActivities = 0

    val isForegrounded: Boolean get() = startedActivities > 0

    fun install(application: Application, onEnterForeground: () -> Unit) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) onEnterForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
