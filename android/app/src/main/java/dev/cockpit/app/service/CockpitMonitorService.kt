package dev.cockpit.app.service

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
import dev.cockpit.app.MainActivity
import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.data.NtfyMessage
import dev.cockpit.app.net.NtfyClient
import dev.cockpit.app.state.MonitoringStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Opt-in foreground service that keeps the ntfy poll alive while the app is
 * backgrounded. Pushed blocked/done events become notifications that deep-link
 * to the exact session and carry an inline Reply action that steers the pane.
 * Without it, pushes only surface on the next app launch.
 */
class CockpitMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: MonitoringStore
    private var pollJob: Job? = null

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
                val client = NtfyClient(
                    OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build(),
                )
                var lastId = store.ntfyCursor
                while (isActive) {
                    try {
                        client.messages(saved.ntfyUrl, saved.ntfyTopic, initialSince = lastId)
                            .collect { message ->
                                store.ntfyCursor = message.id
                                if (message.paneId != null) showEventNotification(message)
                            }
                    } catch (_: Exception) {
                        // ntfy may be briefly unreachable; retry on the next loop.
                    }
                    delay(30_000)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
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
            .setContentTitle("Cockpit monitoring")
            .setContentText("Watching agents for blocked / done events")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** Heads-up event notification: deep link to the session + inline reply. */
    private fun showEventNotification(message: NtfyMessage) {
        val paneId = message.paneId ?: return
        val status = if (message.title?.contains("needs you") == true) "blocked" else "working"
        val deepLink = cockpitChatUri(paneId, status)
        val contentIntent = Intent(this, MainActivity::class.java).apply {
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
                if (status == "blocked") {
                    addAction(NotificationReplyReceiver.replyAction(this@CockpitMonitorService, paneId))
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
        private const val CHANNEL_MONITOR = "cockpit_monitor"
        private const val CHANNEL_AGENTS = "agents"
        private const val NOTIF_ID_MONITOR = 1
    }
}
