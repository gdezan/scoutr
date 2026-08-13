package dev.scoutr.app.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.app.RemoteInput
import dev.scoutr.app.data.ConnectionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pins the notification-shade reply chain end to end: a reply intent must
 * reach the bridge's steer WS carrying the pane id and trimmed text, and a
 * broken bridge must surface as a logcat warning instead of being silently
 * swallowed. The transport is a short-lived WS (steer is a WS verb), which is
 * why the server asserts on the upgraded frame — not an HTTP POST.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationReplyReceiverTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context

    private lateinit var originalBridgeProvider: (Context) -> dev.scoutr.app.net.ScoutrApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = RuntimeEnvironment.getApplication()
        // Robolectric shares prefs across tests — start from a clean slate.
        context.getSharedPreferences("scoutr_connection", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ConnectionStore(context).save(
            // server.url() does a reverse-DNS lookup — keep it off the main thread.
            host = runBlocking(Dispatchers.IO) { server.url("/").toString().trimEnd('/') },
            token = "test-token",
        )
        // The receiver's seam must point at a real BridgeClient (the default
        // resolves the container, which Robolectric cannot construct).
        originalBridgeProvider = NotificationReplyReceiver.bridgeProvider
        NotificationReplyReceiver.bridgeProvider = { ctx ->
            dev.scoutr.app.net.BridgeClient(
                OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
                ConnectionStore(ctx),
            )
        }
        ShadowLog.stream = System.out
    }

    @After
    fun tearDown() {
        NotificationReplyReceiver.bridgeProvider = originalBridgeProvider
        NotificationReplyReceiver.pendingResultSignal = null
        context.getSharedPreferences("scoutr_connection", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ShadowLog.stream = null
        server.shutdown()
    }

    private fun dispatch(receiver: NotificationReplyReceiver, intent: Intent) {
        // Direct onReceive() invocation makes goAsync() return null (no
        // framework dispatch sets the pending result); deliver via the
        // framework so finish() is observable through the shadow future.
        // The intent must carry the filter's action so the broadcast lands on
        // the registered instance, not a manifest-created one.
        context.registerReceiver(receiver, IntentFilter(ACTION_REPLY))
        try {
            context.sendBroadcast(intent)
            ShadowLooper.idleMainLooper()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private companion object {
        const val ACTION_REPLY = "dev.scoutr.TEST_REPLY"
    }

    private fun replyIntent(paneId: String, text: String): Intent {
        val intent = Intent(ACTION_REPLY)
            .setClass(context, NotificationReplyReceiver::class.java)
            .putExtra(NotificationReplyReceiver.EXTRA_PANE_ID, paneId)
        val results = Bundle().apply {
            putCharSequence(NotificationReplyReceiver.KEY_REPLY, text)
        }
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(NotificationReplyReceiver.KEY_REPLY).build()),
            intent,
            results,
        )
        return intent
    }

    @Test
    fun reply_routesTrimmedTextToTheSteerEndpoint() {
        val captured = CompletableDeferred<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!captured.isCompleted) captured.complete(text)
                    webSocket.send("""{"type":"ack"}""")
                    webSocket.close(1000, "test done")
                }
            }),
        )

        val receiver = NotificationReplyReceiver()
        dispatch(receiver, replyIntent("w1:p1", "  fix it now  "))

        val frame = runBlocking {
            Json.parseToJsonElement(withTimeout(5_000) { captured.await() }).jsonObject
        }
        assertEquals("steer", frame["type"]!!.jsonPrimitive.content)
        assertEquals("w1:p1", frame["target"]!!.jsonPrimitive.content)
        assertEquals("fix it now", frame["text"]!!.jsonPrimitive.content)
        assertEquals("/ws?token=test-token", server.takeRequest().path)
    }

    @Test
    fun reply_brokenBridgeLogsWarningInsteadOfSwallowing() {
        server.enqueue(MockResponse().setResponseCode(500)) // WS upgrade fails

        val receiver = NotificationReplyReceiver()
        val signal = CompletableDeferred<android.content.BroadcastReceiver.PendingResult>()
        NotificationReplyReceiver.pendingResultSignal = signal
        dispatch(receiver, replyIntent("w1:p1", "hello"))

        // finish() is called in the coroutine's finally after the failure is
        // logged — awaiting the shadow future is deterministic, no polling.
        val result = runBlocking { withTimeout(5_000) { signal.await() } }
        val finished = Shadows.shadowOf(result).future.get(5, TimeUnit.SECONDS)
        assertSame(result, finished)

        val logged = ShadowLog.getLogsForTag("ScoutrReply")
        assertTrue("expected a logged steer failure, got $logged", logged.any { it.throwable is IOException })
    }
}
