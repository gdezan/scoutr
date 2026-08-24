package dev.scoutr.app.state

import dev.scoutr.app.data.AgentsResponse
import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.QuestionEntry
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.data.SessionReadResponse
import dev.scoutr.app.net.FakeScoutrApi
import dev.scoutr.app.net.PerformanceCounters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.IOException

/**
 * Reverse-history pagination: the first Chat read is a bounded tail, later
 * polls stay incremental, and older pages prepend without duplicating keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatPaginationTest {

    private lateinit var fake: FakeScoutrApi
    private lateinit var counters: PerformanceCounters

    @Before
    fun setUp() {
        fake = FakeScoutrApi()
        fake.agentsResult = Result.success(
            AgentsResponse(
                agents = listOf(
                    dev.scoutr.app.data.liveSessionFixture(
                        paneId = "w1:p1",
                        workspaceId = "w1",
                        tabId = "w1:t1",
                        agentKind = "pi",
                        status = "working",
                        cwd = "/repo",
                        key = dev.scoutr.app.data.SessionKey("pi", "/repo/sessions/s.jsonl"),
                    ),
                ),
            ),
        )
        counters = PerformanceCounters()
    }

    private fun newViewModel(): ChatViewModel =
        ChatViewModel(fake, dev.scoutr.app.data.SessionKey("pi", "/repo/sessions/s.jsonl"), "w1:p1", "working", counters)

    private fun sessionCalls() = fake.calls.filter { it.name == "session" }

    private fun entry(id: String) = SessionEntry(
        entryId = id,
        role = "user",
        content = listOf(ContentBlock(type = "text", text = id)),
    )

    private fun page(
        entries: List<SessionEntry>,
        since: String? = null,
        beforeCursor: String? = null,
        hasMoreBefore: Boolean = false,
        questions: List<QuestionEntry> = emptyList(),
    ) = SessionReadResponse(
        ok = true,
        exists = true,
        path = "/repo/sessions/s.jsonl",
        since = since,
        entries = entries,
        questions = questions,
        beforeCursor = beforeCursor,
        hasMoreBefore = hasMoreBefore,
    )

    private fun pumpUntil(description: String, condition: () -> Boolean) {
        runBlocking {
            repeat(500) {
                ShadowLooper.idleMainLooper()
                if (condition()) return@runBlocking
                delay(10)
            }
        }
        assertTrue("$description did not become true", condition())
    }

    private fun ChatViewModel.awaitRefreshSettled() =
        pumpUntil("transcript ready") { ui.value.transcript is Loadable.Ready }

    private fun ChatViewModel.refreshBlocking(source: RefreshSource): Boolean {
        var result = false
        runBlocking {
            val job = launch { result = refresh(source) }
            repeat(500) {
                ShadowLooper.idleMainLooper()
                if (job.isCompleted) return@runBlocking
                delay(10)
            }
            job.join()
        }
        return result
    }

    @Test
    fun prependSessionEntriesDropsOverlapAtTheHead() {
        val merged = prependSessionEntries(
            existing = listOf(entry("e3"), entry("e4")),
            incoming = listOf(entry("e2"), entry("e3")),
        )
        assertEquals(listOf("e2", "e3", "e4"), merged.map { it.entryId })
    }

    @Test
    fun firstReadAsksForTheNewestPageAndKeepsTheReverseCursor() {
        fake.sessionResult = Result.success(
            page(
                entries = listOf(entry("e3"), entry("e4")),
                beforeCursor = "e3",
                hasMoreBefore = true,
            ),
        )
        val vm = newViewModel()
        vm.startPolling()
        vm.awaitRefreshSettled()
        vm.stopPolling()

        val first = sessionCalls().first()
        assertEquals(null, first.args["since"])
        assertEquals(null, first.args["before"])
        assertEquals(INITIAL_SESSION_PAGE_LIMIT, first.args["limit"])
        assertEquals(listOf("e3", "e4"), vm.ui.value.entries.map { it.entryId })
        assertEquals("e3", vm.ui.value.beforeCursor)
        assertTrue(vm.ui.value.hasOlderEntries)
    }

    @Test
    fun incrementalPollDoesNotResetTheReverseCursor() {
        fake.sessionResult = Result.success(
            page(
                entries = listOf(entry("e3"), entry("e4")),
                beforeCursor = "e3",
                hasMoreBefore = true,
            ),
        )
        val vm = newViewModel()
        vm.startPolling()
        vm.awaitRefreshSettled()

        fake.sessionResult = Result.success(
            page(entries = listOf(entry("e5")), since = "e4"),
        )
        assertTrue(vm.refreshBlocking(RefreshSource.PollTick))
        vm.stopPolling()

        val last = sessionCalls().last()
        assertEquals("e4", last.args["since"])
        assertEquals(null, last.args["before"])
        assertEquals(null, last.args["limit"])
        assertEquals(listOf("e3", "e4", "e5"), vm.ui.value.entries.map { it.entryId })
        assertEquals("e3", vm.ui.value.beforeCursor)
        assertTrue(vm.ui.value.hasOlderEntries)
    }

    @Test
    fun loadOlderPrependsWithoutDuplicatingIds() {
        fake.sessionResult = Result.success(
            page(
                entries = listOf(entry("e3"), entry("e4")),
                beforeCursor = "e3",
                hasMoreBefore = true,
            ),
        )
        val vm = newViewModel()
        vm.startPolling()
        vm.awaitRefreshSettled()

        fake.sessionResult = Result.success(
            page(
                entries = listOf(entry("e1"), entry("e2"), entry("e3")),
                beforeCursor = null,
                hasMoreBefore = false,
            ),
        )
        vm.loadOlderEntries()
        pumpUntil("older page merged") { vm.ui.value.entries.size == 4 }
        vm.stopPolling()

        val older = sessionCalls().last()
        assertEquals(null, older.args["since"])
        assertEquals("e3", older.args["before"])
        assertEquals(INITIAL_SESSION_PAGE_LIMIT, older.args["limit"])
        assertEquals(listOf("e1", "e2", "e3", "e4"), vm.ui.value.entries.map { it.entryId })
        assertFalse(vm.ui.value.hasOlderEntries)
        assertFalse(vm.ui.value.loadingOlderEntries)
    }

    @Test
    fun overlappingLiveAndReverseReadsDoNotDuplicateKeys() {
        fake.sessionResult = Result.success(
            page(
                entries = listOf(entry("e3"), entry("e4")),
                beforeCursor = "e3",
                hasMoreBefore = true,
            ),
        )
        val vm = newViewModel()
        vm.startPolling()
        vm.awaitRefreshSettled()

        fake.onCall = { name, args ->
            if (name != "session") {
                null
            } else if (args["before"] != null) {
                Result.success(
                    page(
                        entries = listOf(entry("e1"), entry("e2")),
                        beforeCursor = null,
                        hasMoreBefore = false,
                    ),
                )
            } else if (args["since"] != null) {
                Result.success(page(entries = listOf(entry("e5")), since = args["since"] as String))
            } else {
                Result.success(
                    page(
                        entries = listOf(entry("e3"), entry("e4")),
                        beforeCursor = "e3",
                        hasMoreBefore = true,
                    ),
                )
            }
        }

        vm.loadOlderEntries()
        assertTrue(vm.refreshBlocking(RefreshSource.PollTick))
        pumpUntil("both ends merged") {
            vm.ui.value.entries.map { it.entryId } == listOf("e1", "e2", "e3", "e4", "e5")
        }
        vm.stopPolling()

        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), vm.ui.value.entries.map { it.entryId })
        assertEquals(5, vm.ui.value.entries.map { it.entryId }.toSet().size)
    }

    @Test
    fun failedOlderLoadLeavesTheCurrentTranscript() {
        fake.sessionResult = Result.success(
            page(
                entries = listOf(entry("e3"), entry("e4")),
                beforeCursor = "e3",
                hasMoreBefore = true,
            ),
        )
        val vm = newViewModel()
        vm.startPolling()
        vm.awaitRefreshSettled()

        fake.sessionResult = Result.failure(IOException("stale reverse cursor"))
        vm.loadOlderEntries()
        pumpUntil("older load finished") { !vm.ui.value.loadingOlderEntries && sessionCalls().size >= 2 }
        vm.stopPolling()

        assertEquals(listOf("e3", "e4"), vm.ui.value.entries.map { it.entryId })
        assertEquals("e3", vm.ui.value.beforeCursor)
        assertTrue(vm.ui.value.hasOlderEntries)
        assertTrue(vm.ui.value.transcript is Loadable.Ready)
    }

    @Test
    fun stopPollingCancelsAnInFlightOlderLoad() {
        fake.sessionResult = Result.success(
            page(
                entries = listOf(entry("e3"), entry("e4")),
                beforeCursor = "e3",
                hasMoreBefore = true,
            ),
        )
        val vm = newViewModel()
        vm.startPolling()
        vm.awaitRefreshSettled()

        val gate = CompletableDeferred<Unit>()
        fake.gates["session"] = gate
        vm.loadOlderEntries()
        pumpUntil("reverse read parked") { vm.ui.value.loadingOlderEntries }
        vm.stopPolling()
        pumpUntil("older load cancelled") { !vm.ui.value.loadingOlderEntries }

        assertEquals(listOf("e3", "e4"), vm.ui.value.entries.map { it.entryId })
        assertTrue(vm.ui.value.hasOlderEntries)
    }
}
