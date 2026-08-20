package dev.scoutr.app.service

import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.scoutr.app.ScoutrApp
import dev.scoutr.app.MainActivity
import dev.scoutr.app.net.ScoutrApi
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
        pendingResultSignal?.complete(result)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                bridgeProvider(context).steer(paneId, text)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                // Reply is best-effort from the notification shade, but a
                // swallowed failure hides a broken chain — leave a logcat trail.
                android.util.Log.w(TAG, "notification reply to $paneId failed", e)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val EXTRA_PANE_ID = "scoutr.paneId"
        const val KEY_REPLY = "reply"
        private const val TAG = "ScoutrReply"

        /** Test seam: the container's client by default (one HTTP stack). */
        internal var bridgeProvider: (Context) -> ScoutrApi =
            { ScoutrApp.container(it).bridge }

        /** Test hook: completed with the goAsync() result of the next onReceive,
         *  so tests can await finish() through the Robolectric shadow. Null in
         *  production, so no framework state is ever retained. */
        internal var pendingResultSignal:
            kotlinx.coroutines.CompletableDeferred<android.content.BroadcastReceiver.PendingResult>? = null

        fun replyAction(context: Context, paneId: String): NotificationCompat.Action {
            val intent = Intent(context, NotificationReplyReceiver::class.java)
                .putExtra(EXTRA_PANE_ID, paneId)
            // RemoteInput fills extras into this intent; FLAG_IMMUTABLE makes
            // NotificationManager reject the whole notify() with
            // "PendingIntents attached to actions with remote inputs must be mutable".
            val pending = PendingIntent.getBroadcast(
                context,
                paneId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
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
