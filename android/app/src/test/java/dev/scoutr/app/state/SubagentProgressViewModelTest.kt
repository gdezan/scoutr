package dev.scoutr.app.state

import dev.scoutr.app.data.PiSubagentProgress
import dev.scoutr.app.net.BridgeException
import dev.scoutr.app.net.FakeScoutrApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubagentProgressViewModelTest {
    private lateinit var fake: FakeScoutrApi

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
    }

    private fun waitFor(timeoutMs: Long = 4000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            runBlocking { delay(25) }
            if (condition()) return true
        }
        return false
    }

    private fun payload(status: String = "running") = PiSubagentProgress(
        runId = "run-abc",
        role = "researcher",
        label = "Find the seam",
        task = "Trace nestLiveSubagents",
        status = status,
        lastMessage = "still looking",
    )

    @Test
    fun pollsSubagentProgressAndRecordsTheRunId() {
        fake.subagentProgressResult = Result.success(payload())
        val viewModel = SubagentProgressViewModel(fake, "run-abc", pollIntervalMs = 50L)

        assertTrue("progress never loaded", waitFor { viewModel.ui.value.progress is Loadable.Ready })
        assertEquals("run-abc", fake.calls.first { it.name == "subagentProgress" }.args["runId"])
        val body = (viewModel.ui.value.progress as Loadable.Ready).value
        assertEquals("researcher", body.role)
        assertEquals("Trace nestLiveSubagents", body.task)
    }

    @Test
    fun firstMissIsAFailure() {
        fake.subagentProgressResult = Result.failure(BridgeException(404, "run not found"))
        val viewModel = SubagentProgressViewModel(fake, "missing-run", pollIntervalMs = 200L)

        assertTrue("first 404 never failed", waitFor { viewModel.ui.value.progress is Loadable.Failed })
        assertTrue((viewModel.ui.value.progress as Loadable.Failed).reason.contains("404"))
    }

    @Test
    fun laterFourOhFourKeepsTheLastPayload() {
        fake.subagentProgressResult = Result.success(payload())
        val viewModel = SubagentProgressViewModel(fake, "run-abc", pollIntervalMs = 60_000L)

        assertTrue("first payload never arrived", waitFor { viewModel.ui.value.progress is Loadable.Ready })
        fake.subagentProgressResult = Result.failure(BridgeException(404, "run not found"))
        viewModel.retry()
        assertTrue(
            "retry never hit the vanished run",
            waitFor { fake.calls.count { it.name == "subagentProgress" } >= 2 },
        )
        val body = (viewModel.ui.value.progress as Loadable.Ready).value
        assertEquals("still looking", body.lastMessage)
    }
}
