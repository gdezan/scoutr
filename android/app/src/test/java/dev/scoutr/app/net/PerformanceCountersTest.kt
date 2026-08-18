package dev.scoutr.app.net

import android.content.Context
import dev.scoutr.app.data.ConnectionStore
import dev.scoutr.app.data.FakeConnectionCipher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PerformanceCountersTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("scoutr_connection", Context.MODE_PRIVATE)
            .edit()
            .putString("host", server.url("/").toString().trimEnd('/'))
            .putString("token", "test-token")
            .apply()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun bridgeClientRecordsResponseStatusBytesDurationAndNormalizedRoute() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("offline"))
        val counters = PerformanceCounters()
        val client = BridgeClient(
            okHttp = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            connectionStore = ConnectionStore(RuntimeEnvironment.getApplication(), FakeConnectionCipher()),
            performanceCounters = counters,
        )

        runBlocking {
            try {
                client.usage()
                throw AssertionError("expected BridgeException")
            } catch (error: BridgeException) {
                assertEquals(503, error.status)
            }
        }

        val snapshot = counters.snapshot()
        assertEquals(0, snapshot.activeHttpRequests)
        assertEquals(1, snapshot.httpRequests)
        assertEquals(1, snapshot.httpResponses)
        assertEquals(1, snapshot.httpErrorResponses)
        assertEquals(0, snapshot.httpFailures)
        assertTrue(snapshot.responseBytes > 0)
        assertTrue(snapshot.totalDurationMs >= 0)
        assertEquals(1, snapshot.endpoints.size)
        assertTrue(snapshot.endpoints.containsKey("/api/usage"))
    }

    @Test
    fun requestHandleSettlesOnlyOnceAndResetClearsCompletedWork() {
        val counters = PerformanceCounters()
        val request = counters.beginHttpRequest("/api/sessions/pane-secret/control?token=secret")!!

        request.complete(status = 200, bodyBytes = 3)
        request.complete(status = 500, bodyBytes = 100)
        request.fail(cancelled = false)

        assertEquals(1, counters.snapshot().httpResponses)
        assertEquals(0, counters.snapshot().httpFailures)
        assertTrue(counters.snapshot().endpoints.keys.none { it.contains("secret") })
        counters.reset()
        assertEquals(0, counters.snapshot().httpRequests)
        assertEquals(0, counters.snapshot().endpoints.size)
    }

    @Test
    fun terminalThroughputCountersRecordHighWaterMarksAndReset() {
        val counters = PerformanceCounters()

        counters.terminalBinaryMessage(120)
        counters.terminalBinaryMessage(80)
        counters.terminalPendingBytes(4_096)
        counters.terminalPendingBytes(1_024)
        counters.terminalOutputBatch(200)
        counters.terminalOutputBatch(50)
        counters.terminalEmulatorAppend()
        counters.terminalEmulatorAppend()
        counters.terminalScreenUpdate()
        counters.terminalQueueOverflow()

        val terminal = counters.snapshot().terminal
        assertEquals(2, terminal.binaryMessages)
        assertEquals(200, terminal.bytesReceived)
        assertEquals(2, terminal.outputBatches)
        assertEquals(250, terminal.outputBytes)
        // Maxima, not last values.
        assertEquals(200, terminal.maxBatchBytes)
        assertEquals(4_096, terminal.maxPendingBytes)
        assertEquals(1, terminal.queueOverflows)
        assertEquals(2, terminal.emulatorAppends)
        assertEquals(1, terminal.screenUpdates)

        counters.reset()
        val cleared = counters.snapshot().terminal
        assertEquals(0, cleared.binaryMessages)
        assertEquals(0, cleared.bytesReceived)
        assertEquals(0, cleared.outputBatches)
        assertEquals(0, cleared.outputBytes)
        assertEquals(0, cleared.maxBatchBytes)
        assertEquals(0, cleared.maxPendingBytes)
        assertEquals(0, cleared.queueOverflows)
        assertEquals(0, cleared.emulatorAppends)
        assertEquals(0, cleared.screenUpdates)
    }
}
