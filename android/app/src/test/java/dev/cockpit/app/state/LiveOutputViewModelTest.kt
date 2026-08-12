package dev.cockpit.app.state

import dev.cockpit.app.data.LiveOutputResponse
import dev.cockpit.app.data.LiveOutputSnapshot
import dev.cockpit.app.net.BridgeException
import dev.cockpit.app.net.FakeCockpitApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
class LiveOutputViewModelTest {
    private lateinit var fake: FakeCockpitApi

    @Before
    fun setUp() {
        fake = FakeCockpitApi()
        fake.liveOutputResult = Result.success(
            LiveOutputResponse(
                output = LiveOutputSnapshot(
                    paneId = "w1:p1",
                    text = "compile\nall tests passed",
                    revision = 9,
                    truncated = true,
                    lineLimit = 80,
                ),
            ),
        )
    }

    private fun outputReads(): Int = fake.calls.count { it.name == "liveOutput" }

    @Test
    fun refreshLoadsBoundedOutputAndPreservesItOnError() = runBlocking {
        val viewModel = viewModel()

        viewModel.refresh()

        assertEquals("compile\nall tests passed", viewModel.ui.value.text)
        assertEquals(9, viewModel.ui.value.revision)
        assertTrue(viewModel.ui.value.truncated)

        fake.liveOutputResult = Result.failure(BridgeException(503, "offline"))
        viewModel.refresh()

        // A failed poll must not blank the last snapshot; the screen shows the
        // frozen tail under a STALE marker instead of an empty panel.
        assertEquals("compile\nall tests passed", viewModel.ui.value.text)
        assertNotNull(viewModel.ui.value.error)
    }

    @Test
    fun renderedLinesSkipTerminalChrome() {
        val state = LiveOutputUiState(
            text = "Useful verification result\nTook 0.1s\nElapsed 6.0s\n────────\n.: Working...\n~/repo │ anthropic/claude-sonnet │ high\n7d:39% Pursuing goal cache R/W 63M/0\n~/Dev/agents-mobile (main) │ 101k/1.0M ↑576.",
        )

        assertEquals(listOf("Useful verification result"), state.lines)
    }

    @Test
    fun pollingRunsWhileStartedAndStopsOnStop() = runBlocking {
        val viewModel = viewModel()
        viewModel.startPolling()
        var deadline = System.currentTimeMillis() + 1_000
        while (outputReads() == 0 && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            delay(25)
        }
        assertTrue("polling starts with the screen", outputReads() > 0)

        // Keeps ticking while the screen is open…
        val atStart = outputReads()
        deadline = System.currentTimeMillis() + 3_000
        while (outputReads() <= atStart && System.currentTimeMillis() < deadline) {
            // Advance the PAUSED main looper's clock past the poll delay so
            // the next 900ms tick actually fires.
            org.robolectric.shadows.ShadowLooper.idleMainLooper(900, TimeUnit.MILLISECONDS)
            delay(25)
        }
        assertTrue("polling repeats while the screen is open", outputReads() > atStart)

        // …and stops dead when the screen goes away. Zero ambient cost is the
        // whole point of moving the poll off the chat screen.
        viewModel.stopPolling()
        delay(100)
        val atStop = outputReads()
        repeat(4) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(900, TimeUnit.MILLISECONDS)
            delay(25)
        }
        assertEquals("no reads after the screen closes", atStop, outputReads())
    }

    private fun viewModel(): LiveOutputViewModel = LiveOutputViewModel(fake, "w1:p1")
}
