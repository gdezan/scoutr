package dev.scoutr.app.net

import dev.scoutr.app.data.ExposureKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostWorkCoordinatorTest {
    @Test
    fun retirement_rejects_new_work_cancels_jobs_and_closes_resources() = runBlocking {
        val binding = HostConnectionBinding("host-a", 7L, "https://a.example", "token", ExposureKind.Custom)
        val work = HostWorkCoordinator()
        work.activate(binding)
        val entered = CompletableDeferred<Unit>()
        val job: Job = launch(SupervisorJob()) {
            work.track(binding) {
                entered.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    // Proves retire waits for the tracked job's completion.
                }
            }
        }
        entered.await()
        var closed = false
        assertTrue(work.registerCloser(binding) { closed = true })

        work.retire(binding)

        assertTrue(job.isCompleted)
        assertTrue(closed)
        assertFalse(work.isActive(binding))
        assertFalse(work.registerCloser(binding) {})
        job.cancelAndJoin()
    }

    @Test
    fun admission_losing_the_retirement_race_does_not_run_the_block() = runBlocking {
        val binding = HostConnectionBinding("host-a", 8L, "https://a.example", "token", ExposureKind.Custom)
        val work = HostWorkCoordinator()
        work.activate(binding)
        work.retire(binding)

        var ran = false
        val result = async { work.trackIfActive(binding) { ran = true } }.await()

        assertFalse(ran)
        org.junit.Assert.assertNull(result)
    }
}
