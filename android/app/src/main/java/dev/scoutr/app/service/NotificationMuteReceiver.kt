package dev.scoutr.app.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.scoutr.app.ScoutrApp

/**
 * The "Mute this agent" action. Silences further notifications for one pane
 * and clears the one on screen, so a single tap from the shade both answers
 * the current interruption and prevents the next.
 */
class NotificationMuteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val paneId = intent.getStringExtra(EXTRA_PANE_ID) ?: return
        val container = ScoutrApp.container(context)
        container.muteStore.mute(paneId)
        container.notifications.cancel(paneId)
    }

    companion object {
        const val EXTRA_PANE_ID = "scoutr.paneId"

        fun muteAction(context: Context, paneId: String): NotificationCompat.Action {
            val intent = Intent(context, NotificationMuteReceiver::class.java)
                .putExtra(EXTRA_PANE_ID, paneId)
            val pending = PendingIntent.getBroadcast(
                context,
                paneId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Action.Builder(
                android.R.drawable.ic_lock_silent_mode,
                "Mute this agent",
                pending,
            ).build()
        }
    }
}
