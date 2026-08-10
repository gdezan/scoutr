package dev.cockpit.app.state

import dev.cockpit.app.data.ConnectionStore
import dev.cockpit.app.net.BridgeClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.robolectric.Robolectric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NewSessionViewModelTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun stubEndpoints() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                val body = when {
                    path == "/api/dirs" -> """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev","Downloads"]}}"""
                    path == "/api/models" -> """{"ok":true,"catalog":{"providers":[{"name":"openai-codex","models":[{"id":"gpt-5.4","name":"GPT-5.4"}]}]}}"""
                    else -> """{"ok":false,"error":"unexpected path $path"}"""
                }
                return MockResponse().setHeader("content-type", "application/json").setBody(body)
            }
        }
    }

    private fun bridge(): BridgeClient {
        val store = ConnectionStore(org.robolectric.RuntimeEnvironment.getApplication())
        val host = server.url("/").toString().trimEnd('/')
        store.save(host, "t", null, null)
        return BridgeClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), store)
    }

    @Test
    fun directDecodeOfMockBodies() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val dirs = json.decodeFromString(dev.cockpit.app.data.DirListingResponse.serializer(),
            """{"ok":true,"listing":{"path":"/home/gdezan","dirs":["Dev","Downloads"]}}""")
        println("DECODE dirs=" + dirs.listing?.dirs)
        val models = json.decodeFromString(dev.cockpit.app.data.ModelsCatalogResponse.serializer(),
            """{"ok":true,"catalog":{"providers":[{"name":"openai-codex","models":[{"id":"gpt-5.4","name":"GPT-5.4"}]}]}}""")
        println("DECODE providers=" + models.catalog?.providers?.size)
    }

    @Test
    fun loadsDirsAndModels() {
        stubEndpoints()
        val vm = NewSessionViewModel(bridge())
        vm.waitForLoaded()
        val ui = vm.ui.value
        println("STATE error=${ui.error} path=${ui.path} dirs=${ui.dirs} providers=${ui.providers.size} reqs=${server.requestCount}")
        assertEquals(listOf("Dev", "Downloads"), ui.dirs)
        assertEquals("openai-codex", ui.providers[0].name)
        assertTrue(ui.path == "/home/gdezan" || ui.path.startsWith("/home/"))
    }

    private fun NewSessionViewModel.waitForLoaded() {
        runBlocking {
            repeat(100) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                if (!ui.value.loadingDirs && !ui.value.loadingModels && ui.value.dirs.isNotEmpty() && ui.value.providers.isNotEmpty()) return@runBlocking
                kotlinx.coroutines.delay(50)
            }
        }
    }
}
