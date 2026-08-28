package dev.scoutr.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.scoutr.app.ScoutrApp

/**
 * The shade's half of the update controls: Cancel on the ongoing progress
 * notification.
 *
 * Only Cancel lives here, because only Cancel is safe from a broadcast — it
 * merely tears work down. Resume would have to start a `dataSync` foreground
 * service, which the platform refuses from the background, and a notification
 * action does not bring an Activity forward on its own; Resume is therefore an
 * Activity PendingIntent built by NotificationPresenter instead.
 */
class UpdateActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Cancelling never touches the service directly: startForegroundService
        // from a background broadcast is exactly what the platform forbids. The
        // service watches the controller's state and stops itself once this
        // drops it back to Idle.
        if (intent.action == ACTION_CANCEL) ScoutrApp.container(context).appUpdates.cancel()
    }

    companion object {
        const val ACTION_CANCEL = "dev.scoutr.app.update.action.CANCEL"

        fun cancelAction(context: Context): NotificationCompat.Action {
            val intent = Intent(context, UpdateActionReceiver::class.java).setAction(ACTION_CANCEL)
            val pending = PendingIntent.getBroadcast(
                context,
                ACTION_CANCEL.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                pending,
            ).build()
        }
    }
}
