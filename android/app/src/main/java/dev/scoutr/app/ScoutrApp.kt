package dev.scoutr.app

import android.app.Application
import android.content.Context
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.SharedPreferencesLauncherSettingsStore
import dev.scoutr.app.data.SharedPreferencesSessionCatalogStore
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.net.BridgeClient
import dev.scoutr.app.net.PerformanceCounters
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.net.TerminalSocketClient
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TopologyFeed
import dev.scoutr.app.net.TopologyFeedClient
import dev.scoutr.app.notify.NotificationPresenter
import dev.scoutr.app.state.ForegroundTracker
import dev.scoutr.app.state.MuteStore
import dev.scoutr.app.ui.theme.TerminalPalette
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        ForegroundTracker.install(this) { container.reconcileNotifications() }
        requestCurrentFcmToken()
    }

    private fun requestCurrentFcmToken() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    val token = task.result ?: return@addOnCompleteListener
                    if (task.isSuccessful) container.registerFcmToken(token)
                }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token unavailable", e)
        }
    }

    companion object {
        private const val TAG = "ScoutrApp"

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
    val performanceCounters = PerformanceCounters()
    val muteStore = MuteStore(appContext)
    val notifications = NotificationPresenter(appContext, muteStore)

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

    /** Slice 6: terminal route seams (one active pane socket + route-scoped topology feed). */
    val terminalTransport: TerminalTransport = TerminalSocketClient(okHttp, performanceCounters = performanceCounters)
    val terminalTopologyFeedFactory = TopologyFeed.Factory { listener ->
        TopologyFeedClient(okHttp, connectionStore, listener, performanceCounters = performanceCounters)
    }

    @Volatile
    private var cachedFcmToken: String? = null

    /**
     * Drop the saved pairing (Settings → Forget).
     *
     * Device preferences deliberately survive: launcher, terminal, catalog,
     * review, and appearance are how the user likes the app, not who they
     * paired with. The caller still owns the UI half — stopping the board
     * view model and resetting navigation to Connect.
     */
    fun forgetConnection() {
        connectionStore.clear()
    }

    /**
     * POST this phone's FCM device token to `/api/devices`. Cached so pairing
     * after a token arrives can register without asking Firebase again.
     */
    fun registerFcmToken(token: String) {
        cachedFcmToken = token
        if (connectionStore.saved == null) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                bridge.registerDevice(token)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "FCM device registration failed", e)
            }
        }
    }

    fun registerCachedFcmToken() {
        cachedFcmToken?.let(::registerFcmToken)
    }

    /**
     * Bring the shade back in line with the bridge whenever the user opens
     * Scoutr. A `resolve` ping can be dropped — FCM makes no delivery promise
     * — and the resulting notification would otherwise be unclearable. Mutes
     * are pruned in the same pass, since this is the one moment the app knows
     * which panes still exist.
     */
    fun reconcileNotifications() {
        if (connectionStore.saved == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val sessions = try {
                bridge.agents().agents
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // Offline is the common case here; the next foregrounding retries.
                Log.w(TAG, "notification reconcile failed", e)
                return@launch
            }
            val live = sessions.mapNotNull { it.live?.paneId }.toSet()
            notifications.cancelAllExcept(sessions.filter { it.blocked }.mapNotNull { it.live?.paneId }.toSet())
            muteStore.prune(live)
        }
    }

    private companion object {
        const val TAG = "ScoutrApp"
    }
}
