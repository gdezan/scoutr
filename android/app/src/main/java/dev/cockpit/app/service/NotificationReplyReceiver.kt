package dev.cockpit.app.service

import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.cockpit.app.CockpitApp
import dev.cockpit.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the inline "Reply" action on a monitor notification: steers the
 * given pane with the typed text through the bridge. Runs off the main
 * thread via goAsync; failures are silent (the notification already served
 * its purpose — catching the user's attention).
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val paneId = intent.getStringExtra(EXTRA_PANE_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)
            ?.toString()
            ?.trim()
        if (text.isNullOrBlank()) return

        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // The container's client: same OkHttp pool, same stored
                // connection the app is paired with.
                val app = context.applicationContext as CockpitApp
                app.container.bridge.steer(paneId, text)
            } catch (_: Exception) {
                // Reply is best-effort from the notification shade.
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PANE_ID = "cockpit.paneId"
        const val KEY_REPLY = "reply"

        fun replyAction(context: Context, paneId: String): NotificationCompat.Action {
            val intent = Intent(context, NotificationReplyReceiver::class.java)
                .putExtra(EXTRA_PANE_ID, paneId)
            val pending = PendingIntent.getBroadcast(
                context,
                paneId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val remoteInput = RemoteInput.Builder(KEY_REPLY)
                .setLabel("Steer the agent…")
                .build()
            return NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                "Reply",
                pending,
            ).addRemoteInput(remoteInput).build()
        }
    }
}
