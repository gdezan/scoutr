package dev.cockpit.app.state

import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.service.CockpitMonitorService
import dev.cockpit.app.service.CockpitDeepLink
import dev.cockpit.app.service.cockpitChatUri
import dev.cockpit.app.service.parseCockpitUri
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
        val link = parseCockpitUri("cockpit://chat/w1:p1?status=blocked")
        assertEquals(CockpitDeepLink("w1:p1", "blocked"), link)
        assertNull(parseCockpitUri("cockpit://other/x"))
        assertNull(parseCockpitUri("https://example.com/chat/w1:p1"))
        assertNull(parseCockpitUri("cockpit://chat/"))
        assertNull(parseCockpitUri(null))
    }

    @Test
    fun chatUriBuildsWithAndWithoutStatus() {
        assertEquals("cockpit://chat/w1:p1", cockpitChatUri("w1:p1", null))
        assertEquals("cockpit://chat/w1:p1?status=working", cockpitChatUri("w1:p1", "working"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CockpitMonitorServiceTest {

    @Test
    fun startsAndStopsWithoutConnection() {
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(CockpitMonitorService::class.java).create()
        service.startCommand(0, 0)

        val shadow = org.robolectric.Shadows.shadowOf(service.get())
        val connection = ConnectionStore(app)
        if (connection.saved == null) {
            // No saved connection: the service stops itself right away.
            assertTrue(shadow.isStoppedBySelf)
        }
    }
}
