package dev.scoutr.app.net

import dev.scoutr.app.data.NtfyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Pins the ntfy NDJSON poll: message lines emit in order, keepalive and
 * malformed lines are skipped, the `since` cursor reaches the request path,
 * and failures close the flow with an IOException.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NtfyClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: NtfyClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NtfyClient(OkHttpClient.Builder().build())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** server.url() does a reverse-DNS lookup — keep it off the main thread. */
    private fun baseUrl(): String = runBlocking(Dispatchers.IO) { server.url("/").toString().trimEnd('/') }

    private fun messageJson(id: String) =
        """{"id":"$id","event":"message","topic":"t","title":"Needs you","message":"hello","paneId":"w1:p1"}"""

    @Test
    fun messages_emitsOnlyMessageEventsInOrder() {
        server.enqueue(
            MockResponse().setBody(
                listOf(
                    messageJson("m1"),
                    "this is a keepalive line",
                    messageJson("m2"),
                    "{malformed",
                ).joinToString("\n"),
            ),
        )
        val emitted = runBlocking { client.messages(baseUrl(), "topic").toList() }
        assertEquals(listOf("m1", "m2"), emitted.map { it.id })
    }

    @Test
    fun messages_initialSinceReachesTheRequestPath() {
        server.enqueue(MockResponse().setBody(messageJson("m1")))
        runBlocking { client.messages(baseUrl(), "topic", initialSince = "m0").toList() }
        assertEquals("/topic/json?poll=1&since=m0", server.takeRequest().path)
    }

    @Test
    fun messages_non2xxClosesWithIOException() {
        server.enqueue(MockResponse().setResponseCode(500))
        var failure: Throwable? = null
        try {
            runBlocking { client.messages(baseUrl(), "topic").toList() }
        } catch (e: Exception) {
            failure = e
        }
        assertTrue("flow must close with an IOException, got $failure", failure is IOException)
    }

    @Test
    fun latestId_returnsLastIdAcrossMixedLines() {
        server.enqueue(
            MockResponse().setBody(
                listOf(messageJson("m1"), "garbage", messageJson("m2")).joinToString("\n"),
            ),
        )
        val last = runBlocking { client.latestId(baseUrl(), "topic") }
        assertEquals("m2", last)
    }

    @Test
    fun latestId_emptyBodyReturnsNull() {
        server.enqueue(MockResponse().setBody(""))
        val last = runBlocking { client.latestId(baseUrl(), "topic") }
        assertNull(last)
    }

    @Test
    fun messages_emptyBodyEmitsNothing() {
        server.enqueue(MockResponse().setBody(""))
        val emitted = runBlocking { client.messages(baseUrl(), "topic").toList() }
        assertTrue(emitted.isEmpty())
    }
}
