package dev.cockpit.app.state

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
