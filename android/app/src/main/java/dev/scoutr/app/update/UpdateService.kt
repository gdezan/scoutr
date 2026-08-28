package dev.scoutr.app.update

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.scoutr.app.ScoutrApp
import dev.scoutr.app.notify.NotificationPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive for the length of one self-update.
 *
 * A host build plus a multi-megabyte download over a slow link runs for
 * minutes, which is far longer than the system will keep a backgrounded
 * process around for. The service does no update work of its own: it holds the
 * process open, mirrors [AppUpdateController.state] into the shade, and stops
 * as soon as the pipeline stops running.
 *
 * Typed `dataSync`, which Android forbids starting from the background. Every
 * start path here therefore originates in a foreground context — the Settings
 * button, or a notification action that brings an Activity forward first.
 * Do not add a start path that does not.
 */
class UpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mirror: Job? = null

    /**
     * The most recent start command. `stopSelf(startId)` is a no-op when a
     * newer one has arrived, which is what keeps a terminal state from an
     * already-finished run from tearing down the run that replaced it.
     */
    private var lastStartId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val container = ScoutrApp.container(this)
        val controller = container.appUpdates

        // First act, before any work is dispatched: the system kills a service
        // started with startForegroundService that does not post promptly.
        startInForeground(container.notifications.updateProgressNotification(controller.state.value))

        container.startUpdate()
        // A start that resolved no host leaves the controller Idle; the mirror
        // below sees that immediately and stops the service.

        if (mirror == null) {
            mirror = scope.launch {
                val notifications = container.notifications
                controller.state.collect {
                    // Re-read instead of using the emitted value: a resume can
                    // land between emission and handling, and acting on the
                    // stale one would strand the new transfer without
                    // foreground protection.
                    val current = controller.state.value
                    if (current.isRunning()) {
                        startInForeground(notifications.updateProgressNotification(current))
                    } else {
                        // Ready, Installing, Failed, and Idle are all terminal
                        // for the *transfer*; whatever comes next is a
                        // notification or a screen, not a service.
                        stop()
                    }
                }
            }
        }
        // Not sticky: a restart with a null intent would be a background start
        // of a dataSync service, which the platform forbids anyway.
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: android.app.Notification) {
        val id = NotificationPresenter.UPDATE_PROGRESS_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Guarded by the start id: if a newer command arrived while this
        // terminal state was being handled, the service stays up for it.
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        mirror = null
        scope.cancel()
        super.onDestroy()
    }

    private fun UpdateState.isRunning(): Boolean =
        this is UpdateState.Building || this is UpdateState.Downloading

    companion object {
        /** Callable only from a foreground context — see the class docs. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, UpdateService::class.java))
        }
    }
}
