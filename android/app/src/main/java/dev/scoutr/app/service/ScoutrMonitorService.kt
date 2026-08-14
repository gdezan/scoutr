package dev.scoutr.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.content.Context
import dev.scoutr.app.ScoutrApp
import dev.scoutr.app.MainActivity
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.NtfyMessage
import dev.scoutr.app.net.NtfyClient
import dev.scoutr.app.state.MonitoringStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
/**
 * Opt-in, time-bounded foreground service that keeps the ntfy poll alive
 * while the app is backgrounded. Pushed blocked/done events become
 * notifications that deep-link to the exact session and carry an inline Reply
 * action that steers the pane. Android 15 stops data-sync services after six
 * background hours, and [onTimeout] ends this session without restarting it.
 */
class ScoutrMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: MonitoringStore
    private var pollJob: Job? = null

    /** Test seam: the shared container's client by default (one HTTP stack). */
    internal var ntfyClientFactory: (Context) -> NtfyClient = { ScoutrApp.container(it).ntfy }

    override fun onCreate() {
        super.onCreate()
        store = MonitoringStore(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val saved = ConnectionStore(this).saved
        if (saved == null || saved.ntfyUrl == null || saved.ntfyTopic == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pollJob == null) {
            pollJob = scope.launch {
                val client = ntfyClientFactory(this@ScoutrMonitorService)
                while (isActive) {
                    try {
                        // The null-guard above smart-casts saved/ntfyUrl/ntfyTopic
                        // into this lambda, so the non-null overload is safe.
                        pollOnce(client, store, saved.ntfyUrl, saved.ntfyTopic) { message ->
                            showEventNotification(message)
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        // ntfy may be briefly unreachable; retry on the next loop.
                    }
                    delay(30_000)
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 can time out data-sync foreground services after six hours
     * of background use. Stop immediately and clear the opt-in so the next
     * app launch does not silently restart an expired monitoring session.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopAfterForegroundServiceTimeout()
    }

    override fun onTimeout(startId: Int) {
        stopAfterForegroundServiceTimeout()
    }

    private fun stopAfterForegroundServiceTimeout() {
        pollJob?.cancel()
        pollJob = null
        store.enabled = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        store.enabled = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_MONITOR,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID_MONITOR, notification)
        }
    }

    private fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Scoutr monitoring")
            .setContentText("Watching agents for blocked / done events (up to six hours on Android 15+)")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** Heads-up event notification: deep link to the session + inline reply. */
    private fun showEventNotification(message: NtfyMessage) {
        // ntfy drops custom JSON fields, so the deep link arrives in its
        // documented 'click' URL; paneId (when present) drives the reply action.
        // The click string is untrusted payload: validate + rebuild it exactly
        // like MainActivity's entry path, and never hand a foreign URI to the
        // launcher. An invalid click falls back to the raw paneId; with
        // neither, the notification is skipped (matches the old ?: return).
        val link = resolveNotificationLink(message.click, message.paneId, statusForTitle(message.title)) ?: return
        val deepLink = link.uri
        val paneId = link.paneId
        val status = statusForTitle(message.title)
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(deepLink)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            paneId.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_AGENTS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(message.title ?: "Agent needs you")
            .setContentText(message.message ?: "An agent is waiting for input")
            .setPriority(if (status == "blocked") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            // Inline reply only makes sense when the agent is waiting on input;
            // a finished agent has nothing to steer into.
            .apply {
                if (status == "blocked" && paneId != null) {
                    addAction(NotificationReplyReceiver.replyAction(this@ScoutrMonitorService, paneId))
                }
            }
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(message.id.hashCode(), notification)
    }


    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENTS,
                "Agents",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Agents that need your attention" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR,
                "Monitoring",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Background monitoring status" },
        )
    }

    companion object {
        private const val CHANNEL_MONITOR = "scoutr_monitor"
        private const val CHANNEL_AGENTS = "agents"
        private const val NOTIF_ID_MONITOR = 1

        /** One poll cycle: read the persisted cursor, fetch, and advance it.
         *  The cursor is read at the top of every call, so a retried loop
         *  never re-fetches (and re-notifies) from a stale snapshot — the
         *  original bug read it once before the loop and froze it forever. */
        internal suspend fun pollOnce(
            client: NtfyClient,
            store: MonitoringStore,
            ntfyUrl: String,
            ntfyTopic: String,
            notify: (NtfyMessage) -> Unit,
        ) {
            val lastId = store.ntfyCursor
            client.messages(ntfyUrl, ntfyTopic, initialSince = lastId).collect { message ->
                store.ntfyCursor = message.id
                if (message.paneId != null) notify(message)
            }
        }
    }
}
