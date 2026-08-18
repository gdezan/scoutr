package dev.scoutr.app.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.app.RemoteInput
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.FakeConnectionCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
 * reach the bridge's steer route carrying the pane id and trimmed text, and a
 * broken bridge must surface as a logcat warning instead of being silently
 * swallowed. The receiver knows nothing about the transport — it calls
 * ScoutrApi.steer, which is one ordinary POST.
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
        ConnectionStore(context, FakeConnectionCipher()).save(
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
                ConnectionStore(ctx, FakeConnectionCipher()),
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
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val receiver = NotificationReplyReceiver()
        val signal = CompletableDeferred<android.content.BroadcastReceiver.PendingResult>()
        NotificationReplyReceiver.pendingResultSignal = signal
        dispatch(receiver, replyIntent("w1:p1", "  fix it now  "))
        val result = runBlocking { withTimeout(5_000) { signal.await() } }
        Shadows.shadowOf(result).future.get(5, TimeUnit.SECONDS)

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/sessions/w1%3Ap1/steer", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("fix it now", body["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun reply_brokenBridgeLogsWarningInsteadOfSwallowing() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"ok":false,"error":"herdr is down"}"""))

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
