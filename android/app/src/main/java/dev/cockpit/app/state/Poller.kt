package dev.cockpit.app.state

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
 * one. The loop dies with [scope] (each VM passes its viewModelScope).
 *
 * Immediate-first, so the first tick doubles as the initial load — call
 * sites must not also launch their own first refresh.
 */
class Poller(private val scope: CoroutineScope) {
    private var job: Job? = null

    /** Cancels any previous loop, then runs [tick] immediately and every [interval]. */
    fun start(interval: Duration, tick: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                tick()
                delay(interval.inWholeMilliseconds)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}