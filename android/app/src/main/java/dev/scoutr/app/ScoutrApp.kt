package dev.scoutr.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.SharedPreferencesLauncherSettingsStore
import dev.scoutr.app.data.SharedPreferencesSessionCatalogStore
import dev.scoutr.app.data.NtfyMessage
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.net.BridgeClient
import dev.scoutr.app.net.PerformanceCounters
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.net.NtfyClient
import dev.scoutr.app.net.TerminalSocketClient
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TopologyFeed
import dev.scoutr.app.net.TopologyFeedClient
import dev.scoutr.app.service.ScoutrMonitorService
import dev.scoutr.app.service.resolveNotificationLink
import dev.scoutr.app.service.statusForTitle
import dev.scoutr.app.state.MonitoringStore
import dev.scoutr.app.ui.theme.TerminalPalette
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Minimal manual DI: the app container owns the singletons the view models need.
 * Tests replace [container] with fakes.
 */
class ScoutrApp : Application() {

    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        // Terminal emulators copy the vendored scheme when created, so install
        // the gdezan-material palette before any session can exist.
        TerminalPalette.install()
        container = AppContainer(this)
    }

    companion object {
        /** Container access for services/receivers; safe on cold start because
         *  Application.onCreate always runs before any component. */
        fun container(context: Context): AppContainer =
            (context.applicationContext as ScoutrApp).container
    }
}

class AppContainer(application: Application) {

    private val appContext: Context = application

    val connectionStore = ConnectionStore(appContext)
    val launcherSettingsStore = SharedPreferencesLauncherSettingsStore(appContext)
    val sessionCatalogStore = SharedPreferencesSessionCatalogStore(appContext)
    val terminalPreferences = TerminalPreferencesStore(appContext)
    val monitoringStore = MonitoringStore(appContext)
    val performanceCounters = PerformanceCounters()

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val bridge: ScoutrApi = BridgeClient(
        okHttp = okHttp,
        connectionStore = connectionStore,
        performanceCounters = performanceCounters,
    )

    val ntfy = NtfyClient(okHttp)

    /** Slice 6: terminal route seams (one active pane socket + route-scoped topology feed). */
    val terminalTransport: TerminalTransport = TerminalSocketClient(okHttp, performanceCounters = performanceCounters)
    val terminalTopologyFeedFactory = TopologyFeed.Factory { listener ->
        TopologyFeedClient(okHttp, connectionStore, listener, performanceCounters = performanceCounters)
    }

    init {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENTS,
                "Agents",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Agents that need your attention" },
        )
    }

    /**
     * Drop the saved pairing (Settings → Forget). Monitoring goes off first so
     * the foreground service cannot re-read the pairing on its way down, and
     * the token is gone before anything else observes the store.
     *
     * Device preferences deliberately survive: launcher, terminal, catalog,
     * review, and appearance are how the user likes the app, not who they
     * paired with. The caller still owns the UI half — stopping the board
     * view model and resetting navigation to Connect.
     */
    fun forgetConnection() {
        monitoringStore.enabled = false
        appContext.stopService(Intent(appContext, ScoutrMonitorService::class.java))
        connectionStore.clear()
    }

    /** Show a heads-up notification for a pushed agent event. */
    fun showAgentNotification(message: NtfyMessage) {
        // The click string is untrusted ntfy payload: validate + rebuild it
        // exactly like the service's notification path, so a foreign URI is
        // never handed to the launcher.
        val link = resolveNotificationLink(message.click, message.paneId, statusForTitle(message.title))
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            link?.let { data = android.net.Uri.parse(it.uri) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_AGENTS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(message.title ?: "Agent needs you")
            .setContentText(message.message ?: "An agent is waiting for input")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(message.id.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL_AGENTS = "agents"
    }
}
