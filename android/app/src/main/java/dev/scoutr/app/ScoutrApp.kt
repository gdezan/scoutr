package dev.scoutr.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.FcmTokenStore
import dev.scoutr.app.data.HostMigrationCoordinator
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.UpdateHostDisposition
import dev.scoutr.app.data.NotificationPreferencesStore
import dev.scoutr.app.data.SharedPreferencesLauncherSettingsStore
import dev.scoutr.app.data.SharedPreferencesSessionCatalogStore
import dev.scoutr.app.data.SessionSnapshotStore
import dev.scoutr.app.data.TerminalPreferencesStore
import dev.scoutr.app.net.DefaultHostClientFactory
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.HostLifecycleCoordinator
import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.HostWorkCoordinator
import dev.scoutr.app.net.PerformanceCounters
import dev.scoutr.app.net.TerminalOpenRequest
import dev.scoutr.app.net.TerminalSocket
import dev.scoutr.app.net.TerminalSocketClient
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TerminalTransportListener
import dev.scoutr.app.net.TopologyFeed
import dev.scoutr.app.net.TopologyFeedClient
import dev.scoutr.app.notify.NotificationPresenter
import dev.scoutr.app.service.PushRegistrationManager
import dev.scoutr.app.state.ForegroundTracker
import dev.scoutr.app.state.MuteStore
import dev.scoutr.app.ui.theme.TerminalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import dev.scoutr.app.update.ApkInstaller
import dev.scoutr.app.update.AppUpdateController
import dev.scoutr.app.update.UpdateStaging
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ScoutrApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        TerminalPalette.install()
        container = AppContainer(this)
        ForegroundTracker.install(this) { container.onForeground() }
        requestCurrentFcmToken()
    }

    private fun requestCurrentFcmToken() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    val token = task.result ?: return@addOnCompleteListener
                    if (task.isSuccessful) container.registerFcmToken(token)
                }
        } catch (error: Exception) {
            Log.w(TAG, "FCM token unavailable", error)
        }
    }

    companion object {
        private const val TAG = "ScoutrApp"
        fun container(context: Context): AppContainer =
            (context.applicationContext as ScoutrApp).container
    }
}

/** Process-wide dependency graph. Every network client is bound to an immutable host identity. */
class AppContainer(application: Application) {
    private val appContext: Context = application
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Legacy preferences are read only by the startup migration coordinator. */
    private val legacyConnectionStore = ConnectionStore(appContext)
    val hostRegistry = HostRegistryStore(appContext)
    val launcherSettingsStore = SharedPreferencesLauncherSettingsStore(
        appContext,
        writeIfRegistered = hostRegistry::writeIfRegistered,
    )
    val sessionCatalogStore = SharedPreferencesSessionCatalogStore(appContext)
    val sessionSnapshots = SessionSnapshotStore(appContext.filesDir)
    val terminalPreferences = TerminalPreferencesStore(appContext, hostRegistry::writeIfRegistered)
    val performanceCounters = PerformanceCounters()
    val muteStore = MuteStore(appContext)
    val notificationPreferencesStore = NotificationPreferencesStore(appContext)
    val notifications = NotificationPresenter(appContext, muteStore, notificationPreferencesStore)
    /** Shared Board/Sessions host filter; null means All hosts. Process-local only. */
    val hostFilter = dev.scoutr.app.state.HostFilterStore()
    /** Process-local per-host reachability/compatibility status. */
    val hostStatus: dev.scoutr.app.state.HostStatusRepository
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private lateinit var concreteHostClients: DefaultHostClientFactory
    private val topologyFeeds = ConcurrentHashMap<String, MutableSet<RestartableTopologyFeed>>()
    val hostClients: HostClientFactory
    val hostWorkCoordinator: HostWorkCoordinator
    val retiringHostIds: kotlinx.coroutines.flow.StateFlow<Set<String>>
        get() = concreteHostClients.coordinator().retiredHosts
    val removingHostIds: kotlinx.coroutines.flow.StateFlow<Set<String>>
        get() = hostLifecycle.removingHostIds

    init {
        concreteHostClients = DefaultHostClientFactory(
            okHttp = okHttp,
            registry = hostRegistry,
            performanceCounters = performanceCounters,
            terminalFactory = { hostId -> hostBoundTerminal(hostId) },
            topologyFactory = { hostId -> TopologyFeed.Factory { listener -> hostBoundTopology(hostId, listener) } },
        )
        hostClients = concreteHostClients
        hostWorkCoordinator = concreteHostClients.work()
        hostStatus = dev.scoutr.app.state.HostStatusRepository(
            clients = hostClients,
            bindingFor = ::currentHostBinding,
            work = hostWorkCoordinator,
        )
    }

    val pushRegistrations: PushRegistrationManager
    val hostLifecycle: HostLifecycleCoordinator
    val migration: HostMigrationCoordinator

    /** Cleanup and migration resume before the push observer can launch requests. */
    init {
        hostLifecycle = HostLifecycleCoordinator(
            registry = hostRegistry,
            hostClients = hostClients,
            connections = concreteHostClients.coordinator(),
            cleanupLocal = ::cleanupHostLocalState,
            copyRetainedMetadata = { from, to ->
                sessionCatalogStore.copyRetainedMetadata(from, to, confirmed = true)
            },
            onActivated = ::restartHostTransports,
        )
        // A crash can happen after the registry removes credentials but before
        // notification/mute/terminal cleanup completes. Resume those tombstones
        // before migration or UI observers can create new host work.
        hostLifecycle.resumePendingCleanup()

        migration = HostMigrationCoordinator(
            registry = hostRegistry,
            legacyStore = legacyConnectionStore,
            sessionCatalog = sessionCatalogStore,
            hostClients = hostClients,
            scope = applicationScope,
            lifecycle = hostLifecycle,
            terminalPreferences = terminalPreferences,
            launcherSettings = launcherSettingsStore,
            adoptLegacyReview = { hostId ->
                dev.scoutr.app.state.ReviewStore(appContext, hostId).adoptLegacyPath(hostId)
            },
            adoptLegacyMutes = muteStore::adoptLegacyMutes,
            clearLegacyNotifications = notifications::cancelLegacy,
        )

        pushRegistrations = PushRegistrationManager(
            hostRegistry,
            FcmTokenStore(appContext),
            hostClients,
            applicationScope,
        )
        hostLifecycle.attachPushRegistrations(pushRegistrations)
        // A fresh process has no live connection slots, so every host starts
        // inactive. Restore them: a background FCM wake-up (dead or killed
        // process — exactly when pushes matter) must be able to resolve a
        // target and fetch before any foreground reconcile runs. Retirement
        // is also in-memory, so a restart legitimately resets to clean slate.
        hostRegistry.snapshot().profiles.forEach(hostLifecycle::activate)
    }


    fun forgetHost(hostId: String, updateHostDisposition: UpdateHostDisposition? = null) {
        applicationScope.launch {
            runCatching { hostLifecycle.forget(hostId, updateHostDisposition) }
                .onFailure { Log.w(TAG, "Could not forget host $hostId", it) }
        }
    }

    fun registerFcmToken(token: String) = pushRegistrations.updateToken(token)
    fun registerCachedFcmToken() = Unit

    /**
     * Process-wide owner of the self-update, so a build plus a multi-megabyte
     * download outlives the Settings screen that started it.
     */
    val appUpdates = AppUpdateController(
        scope = applicationScope,
        work = hostWorkCoordinator,
        notifications = notifications,
        staging = UpdateStaging(File(appContext.filesDir, "update")),
        installer = ApkInstaller.forContext(appContext),
        installedVersionCode = installedVersionCode(),
    ).apply {
        rehydrate()
        // The install session reports back long after — and from anywhere but —
        // the screen that started it, so the outcome is routed process-wide
        // rather than only while Settings happens to be composed.
        applicationScope.launch {
            ApkInstaller.outcome.collect { outcome ->
                if (outcome != null) {
                    onInstallOutcome(outcome)
                    ApkInstaller.clearOutcome()
                }
            }
        }
    }

    /**
     * The host the update pipeline may talk to right now, or null when updates
     * are disabled, no host is chosen, or the chosen one is not connectable.
     * Resolved here rather than passed in, because the foreground service has
     * no composition to read it from.
     */
    private fun updateBinding(): HostConnectionBinding? {
        val state = hostRegistry.snapshot()
        if (!state.inAppUpdatesEnabled) return null
        val hostId = state.updateHostId ?: return null
        return currentHostBinding(hostId)
    }

    /**
     * This app's own versionCode, so [AppUpdateController.rehydrate] can tell
     * a staged APK it could actually install from one the system would
     * reject as a downgrade.
     */
    private fun installedVersionCode(): Int = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    fun startUpdate() {
        val binding = updateBinding() ?: return
        appUpdates.start(hostClients.api(binding), binding)
    }

    fun currentHostBinding(hostId: String): HostConnectionBinding? =
        concreteHostClients.coordinator().currentBinding(hostId)

    fun isHostRetiring(hostId: String): Boolean =
        concreteHostClients.coordinator().isRetired(hostId)

    /** Resolves host-qualified terminal preferences without exposing ConnectionStore to routes. */
    fun terminalPreferencesForHost(hostId: String): TerminalPreferencesStore.ConnectionPreferences {
        check(hostRegistry.credentials(hostId) != null) { "Host credentials unavailable: $hostId" }
        return terminalPreferences.forHost(hostId)
    }

    fun reviewStoreForHost(hostId: String): dev.scoutr.app.state.ReviewStore =
        dev.scoutr.app.state.ReviewStore(appContext, hostId, hostRegistry::writeIfRegistered)

    fun onForeground() {
        migration.retry()
        pushRegistrations.registerAllCurrent()
        reconcileNotifications()
    }

    fun reconcileNotifications() {
        // Done notifications auto-clear on foreground even when every host is offline.
        notifications.cancelAllDone()
        hostRegistry.snapshot().profiles.forEach { reconcileNotifications(it.hostId) }
    }

    private fun reconcileNotifications(hostId: String) {
        val binding = concreteHostClients.coordinator().currentBinding(hostId) ?: return
        if (!concreteHostClients.work().isActive(binding)) return
        applicationScope.launch {
            val sessions = try {
                hostClients.api(binding).agents().agents
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "notification reconcile failed", error)
                return@launch
            }
            concreteHostClients.work().withActive(binding) {
                val current = hostRegistry.snapshot().profiles.firstOrNull { it.hostId == hostId }
                if (current?.connectionRevision != binding.connectionRevision ||
                    pushRegistrations.isRetiring(hostId)
                ) return@withActive
                val live = sessions.mapNotNull { it.live?.paneId }.toSet()
                notifications.cancelAllExcept(
                    current.hostId,
                    current.profileGeneration,
                    sessions.filter { it.blocked }.mapNotNull { it.live?.paneId }.toSet(),
                )
                muteStore.prune(current.hostId, live)
            }
        }
    }

    private fun hostBoundTerminal(binding: HostConnectionBinding): TerminalTransport {
        val hostId = binding.hostId
        val delegate = TerminalSocketClient(okHttp, performanceCounters = performanceCounters)
        return object : TerminalTransport {
            override fun open(request: TerminalOpenRequest, listener: TerminalTransportListener): TerminalSocket {
                val pending = VerifiedTerminalSocket()
                applicationScope.launch(Dispatchers.IO) {
                    try {
                        concreteHostClients.coordinator().withVerifiedBinding(binding) { verified ->
                            val opened = delegate.open(verified, request, listener)
                            if (!concreteHostClients.work().registerCloser(verified) {
                                    opened.cancel()
                                    listener.onFailure(IOException("Host binding refreshed: $hostId"))
                                }
                            ) {
                                opened.cancel()
                                throw IOException("Host binding is retired: $hostId")
                            }
                            pending.attach(opened)
                        }
                    } catch (cancelled: CancellationException) {
                        listener.onFailure(IOException("Host binding retired: $hostId", cancelled))
                        throw cancelled
                    } catch (error: Exception) {
                        listener.onFailure(error as? IOException ?: IOException("Terminal open failed", error))
                    }
                }
                return pending
            }
        }
    }

    private fun hostBoundTopology(binding: HostConnectionBinding, listener: TopologyFeed.Listener): TopologyFeed {
        val hostId = binding.hostId
        val feed = RestartableTopologyFeed(
            connectionRevision = binding.connectionRevision,
            delegate = TopologyFeedClient(
                okHttp = okHttp,
                listener = listener,
                performanceCounters = performanceCounters,
                bindingProvider = { binding },
                bindingGate = concreteHostClients.coordinator(),
                workCoordinator = concreteHostClients.work(),
            ),
        )
        topologyFeeds.computeIfAbsent(hostId) { ConcurrentHashMap.newKeySet() }.add(feed)
        return feed
    }

    private fun restartHostTransports(hostId: String) {
        val connectionRevision = concreteHostClients.coordinator()
            .currentBinding(hostId)?.connectionRevision
        topologyFeeds[hostId]?.toList()?.forEach { it.restartIfStarted(connectionRevision) }
    }
    private fun cleanupHostLocalState(hostId: String) {
        notifications.cancelHost(hostId)
        notifications.cancelLegacy()
        muteStore.clearHost(hostId)
        terminalPreferences.clearHost(hostId)
        launcherSettingsStore.clearHost(hostId)
        dev.scoutr.app.state.ReviewStore(appContext, hostId).clearHost(hostId)
        sessionSnapshots.clear(hostId)
        hostStatus.remove(hostId)
        hostFilter.resetIfSelected(hostId)
    }

    private fun defaultHostId(): String? = hostRegistry.snapshot().defaultHostId

    private companion object {
        const val TAG = "ScoutrApp"
    }
}

/** Defers terminal socket creation until the host identity gate succeeds. */
private class VerifiedTerminalSocket : TerminalSocket {
    private enum class PendingClose { Release, Cancel }

    private var delegate: TerminalSocket? = null
    private var pendingClose: PendingClose? = null

    @Synchronized
    fun attach(socket: TerminalSocket) {
        when (pendingClose) {
            PendingClose.Release -> socket.release()
            PendingClose.Cancel -> socket.cancel()
            null -> delegate = socket
        }
    }

    @Synchronized
    override fun sendInput(bytes: ByteArray): Boolean = delegate?.sendInput(bytes) ?: false

    @Synchronized
    override fun resize(cols: Int, rows: Int): Boolean = delegate?.resize(cols, rows) ?: false

    @Synchronized
    override fun release() {
        delegate?.release() ?: run { pendingClose = PendingClose.Release }
    }

    @Synchronized
    override fun cancel() {
        delegate?.cancel() ?: run { pendingClose = PendingClose.Cancel }
    }
}

/** Tracks route ownership so a credential refresh can restart only live feeds. */
private class RestartableTopologyFeed(
    private val connectionRevision: Long,
    private val delegate: TopologyFeed,
) : TopologyFeed {
    @Volatile private var started = false

    @Synchronized
    override fun start(): Boolean {
        val result = delegate.start()
        started = result
        return result
    }

    @Synchronized
    override fun stop() {
        started = false
        delegate.stop()
    }

    @Synchronized
    fun restartIfStarted(currentGeneration: Long?) {
        if (!started) return
        delegate.stop()
        started = currentGeneration == connectionRevision && delegate.start()
    }
}
