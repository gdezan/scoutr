package dev.scoutr.app.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.scoutr.app.ScoutrApp
import dev.scoutr.app.state.ForegroundTracker
import kotlinx.coroutines.runBlocking

/**
 * System wake for a contentless FCM ping. The service never uses the
 * singleton bridge compatibility property: every qualified message resolves
 * its API through the host client factory after the generation gate.
 */
class ScoutrMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        ScoutrApp.container(this).registerFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val container = ScoutrApp.container(this)
        // Stay on FCM's worker thread until the wake fetch (and its retries)
        // finish; returning early lets the system reclaim the process.
        runBlocking {
            FcmPingHandler(
                presenter = container.notifications,
                registry = container.hostRegistry,
                hostClients = container.hostClients,
                isForegrounded = { ForegroundTracker.isForegrounded },
                isRetiring = container.pushRegistrations::isRetiring,
                workCoordinator = container.hostWorkCoordinator,
            ).handle(message.data)
        }
    }
}
