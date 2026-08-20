package dev.scoutr.app.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.scoutr.app.ScoutrApp
import dev.scoutr.app.state.ForegroundTracker
import kotlinx.coroutines.runBlocking

/**
 * System wake for a contentless FCM ping.
 *
 * FCM delivers data-only messages here even when the app is backgrounded or
 * force-stopped. The service posts the device token on rotation and hands
 * every ping to [FcmPingHandler]; it never reads a `notification` block.
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
                api = container.bridge,
                isForegrounded = { ForegroundTracker.isForegrounded },
            ).handle(message.data)
        }
    }
}
