package dev.scoutr.app.state

import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Poller contract: immediate first tick, restart cancels the old loop, loop dies with the scope. */
class PollerTest {

    @Test
    fun ticksImmediatelyThenEveryInterval() = runTest {
        val poller = Poller(this)
        var ticks = 0
        poller.start(1.seconds) { ticks++ }
        runCurrent()
        assertEquals("first tick must not wait for the interval", 1, ticks)
        advanceTimeBy(999.milliseconds.inWholeMilliseconds)
        runCurrent()
        assertEquals("no tick before the interval elapses", 1, ticks)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("tick after the interval", 2, ticks)
        poller.stop()
    }

    @Test
    fun restartCancelsPreviousLoop() = runTest {
        val poller = Poller(this)
        var ticksA = 0
        var ticksB = 0
        poller.start(1.seconds) { ticksA++ }
        runCurrent()
        advanceTimeBy(2.seconds.inWholeMilliseconds)
        runCurrent()
        assertEquals(3, ticksA)
        poller.start(1.seconds) { ticksB++ }
        runCurrent()
        advanceTimeBy(5.seconds.inWholeMilliseconds)
        runCurrent()
        assertEquals("new loop ticks from its own start", 6, ticksB)
        assertEquals("old loop must be cancelled by the restart", 3, ticksA)
        poller.stop()
    }

    @Test
    fun resetNextDeadlineDeferrsNextTickToAFullInterval() = runTest {
        val poller = Poller(this)
        var ticks = 0
        poller.start(1.seconds) { ticks++ }
        runCurrent()
        assertEquals("immediate first tick", 1, ticks)

        // Restart the deadline halfway through the interval: the next tick
        // must land a full interval later, not on the original schedule.
        advanceTimeBy(500)
        runCurrent()
        poller.resetNextDeadline()
        runCurrent()
        assertEquals("reset must not tick immediately", 1, ticks)

        advanceTimeBy(500)
        runCurrent()
        assertEquals("old deadline must not fire", 1, ticks)

        advanceTimeBy(500)
        runCurrent()
        assertEquals("new deadline fires a full interval after the reset", 2, ticks)

        // The loop continues on the restarted schedule.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("loop keeps ticking after the reset", 3, ticks)
        poller.stop()
    }

    @Test
    fun resetNextDeadlineIsANoOpAfterStop() = runTest {
        val poller = Poller(this)
        var ticks = 0
        poller.start(1.seconds) { ticks++ }
        runCurrent()
        poller.stop()
        poller.resetNextDeadline()
        advanceTimeBy(10.seconds.inWholeMilliseconds)
        runCurrent()
        assertEquals("no loop restarts after stop()", 1, ticks)
    }

    @Test
    fun stopCancelsTheLoop() = runTest {
        val poller = Poller(this)
        var ticks = 0
        poller.start(1.seconds) { ticks++ }
        runCurrent()
        poller.stop()
        advanceTimeBy(5.seconds.inWholeMilliseconds)
        runCurrent()
        assertEquals("no ticks after stop()", 1, ticks)
    }

    @Test
    fun loopDiesWithItsScope() = runTest {
        val scope = TestScope(testScheduler)
        val poller = Poller(scope)
        var ticks = 0
        poller.start(1.seconds) { ticks++ }
        runCurrent()
        scope.cancel()
        advanceTimeBy(10.seconds.inWholeMilliseconds)
        runCurrent()
        assertEquals("no ticks after the scope is cancelled", 1, ticks)
    }
}
