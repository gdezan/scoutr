package dev.scoutr.app.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.scoutr.app.ScoutrApp
import dev.scoutr.app.notify.NotificationPresenter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device integration for the errored ping: drives the real
 * [FcmPingHandler] — real host registry, real API client, real presenter,
 * real NotificationManager — the way [ScoutrMessagingService] does, minus
 * Google's delivery. The bridge must be live and serving a pane whose last
 * transcript record is a failed model call; pass its ids via instrumentation
 * args (`paneId`, `hostId`, `generation`). The fetch may fail; the degraded
 * path posts too, so a needs-you notification is expected either way.
 */
@RunWith(AndroidJUnit4::class)
class ErroredPingDeviceTest {

    @Test
    fun erroredPingPostsNeedsYouNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val paneId = arguments.getString("paneId") ?: "w83:p46"
        val hostId = arguments.getString("hostId")
        val generation = arguments.getString("generation")?.toLongOrNull()

        val container = ScoutrApp.container(context)
        val profiles = container.hostRegistry.snapshot().profiles
        Log.i(TAG, "registry profiles=${profiles.map { it.hostId to it.profileGeneration }}")
        val binding = hostId?.let { container.currentHostBinding(it) }
        Log.i(TAG, "binding=$binding")

        if (binding != null) {
            runBlocking {
                runCatching {
                    val sessions = container.hostClients.api(binding).agents().agents
                    Log.i(TAG, "agents=${sessions.map { it.live?.paneId }} containsPane=${sessions.any { it.live?.paneId == paneId }}")
                }.onFailure { Log.w(TAG, "direct agents() probe failed", it) }
            }
        }

        Log.i(TAG, "slotActive=${binding?.let { container.hostWorkCoordinator.isActive(it) }}")
        val data = buildMap {
            put("kind", "errored")
            put("paneId", paneId)
            if (hostId != null) put("hostId", hostId)
            if (generation != null) put("profileGeneration", generation.toString())
        }

        runBlocking {
            FcmPingHandler(
                presenter = container.notifications,
                registry = container.hostRegistry,
                hostClients = container.hostClients,
                isForegrounded = { false },
                isRetiring = container.pushRegistrations::isRetiring,
                workCoordinator = container.hostWorkCoordinator,
            ).handle(data)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = manager.activeNotifications
        Log.i(TAG, "active=${active.map { it.notification.channelId to it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) }}")
        val needsYou = active.any { it.notification.channelId == NotificationPresenter.CHANNEL_NEEDS_YOU }
        assertTrue("expected a needs-you notification after an errored ping", needsYou)
    }

    private companion object {
        const val TAG = "ErroredDeviceTest"
    }
}
