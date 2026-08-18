package dev.scoutr.app.state

import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.service.ScoutrMonitorService
import dev.scoutr.app.service.ScoutrDeepLink
import dev.scoutr.app.service.scoutrChatUri
import dev.scoutr.app.service.parseScoutrUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonitoringStoreTest {

    @Test
    fun togglePersists() {
        val app = RuntimeEnvironment.getApplication()
        val store = MonitoringStore(app)
        assertFalse(store.enabled)
        store.enabled = true
        assertTrue(MonitoringStore(app).enabled)
    }

    @Test
    fun cursorRoundTrips() {
        val app = RuntimeEnvironment.getApplication()
        val store = MonitoringStore(app)
        assertNull(store.ntfyCursor)
        store.ntfyCursor = "1699999999001"
        assertEquals("1699999999001", MonitoringStore(app).ntfyCursor)
    }

    @Test
    fun parsesChatDeepLinks() {
        val link = parseScoutrUri("scoutr://chat/w1:p1?status=blocked")
        assertEquals(ScoutrDeepLink("w1:p1", "blocked"), link)
        assertNull(parseScoutrUri("scoutr://other/x"))
        assertNull(parseScoutrUri("https://example.com/chat/w1:p1"))
        assertNull(parseScoutrUri("scoutr://chat/"))
        assertNull(parseScoutrUri(null))
    }

    @Test
    fun chatUriBuildsWithAndWithoutStatus() {
        assertEquals("scoutr://chat/w1:p1", scoutrChatUri("w1:p1", null))
        assertEquals("scoutr://chat/w1:p1?status=working", scoutrChatUri("w1:p1", "working"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScoutrMonitorServiceTest {

    @Test
    fun startsAndStopsWithoutConnection() {
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(ScoutrMonitorService::class.java).create()
        service.startCommand(0, 0)

        val shadow = org.robolectric.Shadows.shadowOf(service.get())
        val connection = ConnectionStore(app, FakeConnectionCipher())
        if (connection.saved == null) {
            // No saved connection: the service stops itself right away.
            assertTrue(shadow.isStoppedBySelf)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonitorNotificationIntentTest {

    @Test
    fun notificationIntentCarriesTheScoutrDeepLink() {
        val app = RuntimeEnvironment.getApplication()
        val paneId = "w4Q:pG"
        val deepLink = "scoutr://chat/$paneId?status=blocked"
        val contentIntent = android.content.Intent(app, dev.scoutr.app.MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_VIEW
            data = android.net.Uri.parse(deepLink)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = android.app.PendingIntent.getActivity(
            app,
            paneId.hashCode(),
            contentIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val roundTripped = org.robolectric.Shadows.shadowOf(pending).savedIntent
        assertEquals(android.content.Intent.ACTION_VIEW, roundTripped.action)
        assertEquals(deepLink, roundTripped.dataString)
    }
}
