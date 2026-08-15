package dev.scoutr.app.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One definition of "refresh first, then wait" for the ViewModels' poll
 * loops. A [start] cancels any previous loop; [stop] cancels the current
 * one; [resetNextDeadline] restarts the current loop on a fresh schedule.
 * The loop dies with [scope] (each VM passes its viewModelScope).
 *
 * Immediate-first, so the first tick doubles as the initial load — call
 * sites must not also launch their own first refresh. [resetNextDeadline]
 * deliberately starts without an immediate tick: it only moves the next
 * deadline, it never triggers a read of its own.
 */
class Poller(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var interval: Duration = 1.seconds
    private var tick: suspend () -> Unit = {}

    /**
     * Cancels any previous loop, then runs [tick] every [interval];
     * immediately first unless [immediateFirst] is false. [tick] stays the
     * trailing lambda so callers keep the `poller.start(2.5.seconds) { }`
     * form.
     */
    fun start(interval: Duration, immediateFirst: Boolean = true, tick: suspend () -> Unit) {
        job?.cancel()
        this.interval = interval
        this.tick = tick
        job = scope.launch {
            if (immediateFirst) tick()
            while (isActive) {
                delay(interval.inWholeMilliseconds)
                tick()
            }
        }
    }

    /**
     * Restart the current loop without an immediate tick, so the next poll
     * lands a full [interval] from now instead of on the old schedule. A
     * no-op when no loop is running. Used to reset the poll deadline after
     * a user-initiated refresh (a pull that just read the pane must not be
     * followed by a poll on the old cadence).
     */
    fun resetNextDeadline() {
        val current = job ?: return
        if (!current.isActive) return
        start(interval, immediateFirst = false, tick = tick)
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
