package dev.scoutr.app.service

import android.content.Context
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.NtfyMessage
import dev.scoutr.app.net.NtfyClient
import dev.scoutr.app.state.MonitoringStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the poll cycle the monitor service runs every 30s: the persisted
 * cursor is read at the top of the cycle, each emitted message advances the
 * store, and pane-less messages are skipped. This is the regression test for
 * the stale-cursor bug where `lastId` was read once before the loop and every
 * later cycle re-fetched (and re-notified) since app start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScoutrMonitorServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var store: MonitoringStore
    private lateinit var client: NtfyClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = RuntimeEnvironment.getApplication()
        // Robolectric shares prefs across tests — start from a clean cursor.
        context.getSharedPreferences("scoutr_monitoring", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = MonitoringStore(context)
        client = NtfyClient(OkHttpClient.Builder().build())
    }

    @After
    fun tearDown() {
        // Leave no cursor behind in the shared Robolectric prefs.
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("scoutr_monitoring", Context.MODE_PRIVATE)
            .edit().clear().commit()
        server.shutdown()
    }

    /** server.url() does a reverse-DNS lookup — keep it off the main thread. */
    private fun baseUrl(): String = runBlocking(Dispatchers.IO) { server.url("/").toString().trimEnd('/') }

    private fun messageJson(id: String, paneId: String?) =
        """{"id":"$id","event":"message","topic":"t","title":"Working","message":"hi","paneId":${
            paneId?.let { "\"$it\"" } ?: "null"
        }}"""

    @Test
    fun pollOnce_readsCursorThenAdvancesItPerMessage() {
        store.ntfyCursor = "m0"
        server.enqueue(
            MockResponse().setBody(
                listOf(messageJson("m1", "w1:p1"), messageJson("m2", "w1:p2")).joinToString("\n"),
            ),
        )
        val notified = mutableListOf<NtfyMessage>()
        runBlocking {
            ScoutrMonitorService.pollOnce(
                client, store,
                baseUrl(), "topic",
            ) { notified.add(it) }
        }

        // The persisted cursor seeded the request…
        assertEquals("/topic/json?poll=1&since=m0", server.takeRequest().path)
        // …and advanced to the last delivered message.
        assertEquals("m2", store.ntfyCursor)
        assertEquals(listOf("m1", "m2"), notified.map { it.id })
    }

    @Test
    fun pollOnce_skipsPaneLessMessagesButStillAdvancesTheCursor() {
        store.ntfyCursor = "m0"
        server.enqueue(
            MockResponse().setBody(
                listOf(messageJson("m1", "w1:p1"), messageJson("m2", null)).joinToString("\n"),
            ),
        )
        val notified = mutableListOf<NtfyMessage>()
        runBlocking {
            ScoutrMonitorService.pollOnce(client, store, baseUrl(), "topic") {
                notified.add(it)
            }
        }
        assertEquals("m2", store.ntfyCursor)
        assertEquals(listOf("m1"), notified.map { it.id })
    }

    @Test
    fun pollOnce_seededFromAnEmptyCursorOmitsSince() {
        store.ntfyCursor = null
        server.enqueue(MockResponse().setBody(messageJson("m1", "w1:p1")))
        runBlocking {
            ScoutrMonitorService.pollOnce(client, store, baseUrl(), "topic") {}
        }
        assertEquals("/topic/json?poll=1", server.takeRequest().path)
    }
}
