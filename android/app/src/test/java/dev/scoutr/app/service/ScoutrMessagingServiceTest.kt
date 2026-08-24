package dev.scoutr.app.service

import android.app.NotificationManager
import android.content.Context
import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.data.HostRegistryStore
import dev.scoutr.app.data.SessionDescriptor
import dev.scoutr.app.data.SessionLiveAttachment
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TopologyFeed
import dev.scoutr.app.notify.NotificationPresenter
import dev.scoutr.app.state.MuteStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The FCM service is a thin Firebase wrapper. These tests pin the handler
 * it calls: a background `blocked` ping posts the identity notification,
 * a foreground ping posts nothing, a fetch failure degrades after retries,
 * and a `resolve` ping cancels the slot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScoutrMessagingServiceTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var presenter: NotificationPresenter
    private lateinit var api: FakeScoutrApi

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        context.getSharedPreferences(MuteStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(HostRegistryStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        presenter = NotificationPresenter(context, MuteStore(context))
        api = FakeScoutrApi()
        api.agentsResult = Result.success(AgentsResponse(agents = listOf(blocked("w1:p1"))))
    }

    private fun handler(foregrounded: Boolean = false) = FcmPingHandler(
        presenter = presenter,
        api = api,
        isForegrounded = { foregrounded },
        delayMs = { },
    )

    private fun blocked(paneId: String) = SessionDescriptor(
        agentKind = "pi",
        displayName = "pi",
        title = "pi pane",
        cwd = "/home/gdezan/Dev/scoutr",
        live = SessionLiveAttachment(
            paneId = paneId,
            workspaceId = "w1",
            tabId = "t1",
            status = "blocked",
        ),
    )

    private fun slots() = manager.activeNotifications.filter { it.id != NotificationPresenter.SUMMARY_ID }

    @Test
    fun blockedPingWhileBackgroundedPostsIdentityNotification() = runTest {
        handler().handle(
            mapOf(
                FcmPingHandler.KEY_KIND to FcmPingHandler.KIND_BLOCKED,
                FcmPingHandler.KEY_PANE_ID to "w1:p1",
            ),
        )

        assertEquals(1, slots().size)
        assertEquals("pi · scoutr", slots().single().notification.extras.getString("android.title"))
    }

    @Test
    fun blockedPingWhileForegroundedPostsNothing() = runTest {
        handler(foregrounded = true).handle(
            mapOf(
                FcmPingHandler.KEY_KIND to FcmPingHandler.KIND_BLOCKED,
                FcmPingHandler.KEY_PANE_ID to "w1:p1",
            ),
        )

        assertTrue(slots().isEmpty())
        assertTrue(api.calls.none { it.name == "agents" })
    }

    @Test
    fun failingAgentsFetchPostsDegradedAfterRetries() = runTest {
        api.agentsResult = Result.failure(IOException("offline"))

        handler().handle(
            mapOf(
                FcmPingHandler.KEY_KIND to FcmPingHandler.KIND_BLOCKED,
                FcmPingHandler.KEY_PANE_ID to "w1:p1",
            ),
        )

        assertEquals(3, api.calls.count { it.name == "agents" })
        val extras = slots().single().notification.extras
        assertEquals("An agent needs you", extras.getString("android.title"))
        assertEquals("Tap to open Scoutr", extras.getString("android.text"))
    }

    @Test
    fun resolvePingCancelsTheSlot() = runTest {
        presenter.showBlocked(blocked("w1:p1"))
        assertEquals(1, slots().size)

        handler().handle(
            mapOf(
                FcmPingHandler.KEY_KIND to FcmPingHandler.KIND_RESOLVE,
                FcmPingHandler.KEY_PANE_ID to "w1:p1",
            ),
        )

        assertTrue(slots().isEmpty())
    }

    @Test
    fun hostAwareHandlerDiscardsPingWithoutHostIdentityBeforeFetching() = runTest {
        val registry = HostRegistryStore(context, FakeConnectionCipher())
        registry.addOrRefresh("host-a", "https://a.example", "token", ExposureKind.Custom)
        val hostAware = FcmPingHandler(
            presenter = presenter,
            registry = registry,
            hostClients = FakeFactory(api),
            isForegrounded = { false },
            delayMs = { },
        )

        hostAware.handle(
            mapOf(
                FcmPingHandler.KEY_KIND to FcmPingHandler.KIND_BLOCKED,
                FcmPingHandler.KEY_PANE_ID to "w1:p1",
            ),
        )

        assertTrue(api.calls.none { it.name == "agents" })
        assertTrue(slots().isEmpty())
    }

    private class FakeFactory(private val api: ScoutrApi) : HostClientFactory {
        override fun api(hostId: String): ScoutrApi = api
        override fun terminal(hostId: String): TerminalTransport = error("unused")
        override fun topologyFeedFactory(hostId: String): TopologyFeed.Factory = error("unused")
        override fun probe(host: String, token: String): ScoutrApi = error("unused")
    }
}
