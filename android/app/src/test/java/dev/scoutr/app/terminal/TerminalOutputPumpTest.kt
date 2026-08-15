package dev.scoutr.app.terminal

import dev.scoutr.app.net.PerformanceCounters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic coverage for the terminal output pump: ordering, burst coalescing, the 64 KiB
 * batch cap, generation replacement, shutdown, and the pending-byte bound.
 *
 * Every test drives one [StandardTestDispatcher], so enqueues accumulate while the consumer is
 * parked and drain only at an explicit [advanceUntilIdle] — the burst shape the pump exists for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalOutputPumpTest {

    private class Consumer {
        val batches = mutableListOf<ByteArray>()
        fun bytes(): ByteArray {
            val out = ByteArray(batches.sumOf { it.size })
            var at = 0
            for (batch in batches) {
                batch.copyInto(out, at)
                at += batch.size
            }
            return out
        }
        fun text(): String = bytes().toString(Charsets.UTF_8)
    }

    private fun TestScope.pump(
        consumer: Consumer,
        counters: PerformanceCounters? = null,
        maxPendingBytes: Int = TerminalOutputPump.MAX_PENDING_BYTES,
    ) = TerminalOutputPump(
        scope = CoroutineScope(coroutineContext),
        counters = counters,
        maxPendingBytes = maxPendingBytes,
        consume = { consumer.batches += it },
    )

    @Test
    fun preservesEnqueueOrderAcrossChunks() = runTest {
        val consumer = Consumer()
        val pump = pump(consumer)
        val generation = pump.resetGeneration()

        generation.enqueue("abc".toByteArray())
        generation.enqueue("def".toByteArray())
        generation.enqueue("ghi".toByteArray())
        advanceUntilIdle()

        assertEquals("abcdefghi", consumer.text())
        pump.close()
    }

    @Test
    fun burstOfSmallChunksCoalescesIntoFarFewerBatches() = runTest {
        val consumer = Consumer()
        val counters = PerformanceCounters()
        val pump = pump(consumer, counters)
        val generation = pump.resetGeneration()

        val expected = StringBuilder()
        repeat(1_000) { index ->
            val chunk = "chunk-$index;"
            expected.append(chunk)
            assertTrue(generation.enqueue(chunk.toByteArray()))
        }
        advanceUntilIdle()

        assertEquals(expected.toString(), consumer.text())
        assertTrue(
            "expected far fewer batches than chunks, got ${consumer.batches.size}",
            consumer.batches.size < 100,
        )
        assertTrue(consumer.batches.all { it.size <= TerminalOutputPump.MAX_BATCH_BYTES })
        val snapshot = counters.snapshot().terminal
        assertEquals(consumer.batches.size.toLong(), snapshot.outputBatches)
        assertEquals(expected.length.toLong(), snapshot.outputBytes)
        assertEquals(0L, snapshot.queueOverflows)
        pump.close()
    }

    @Test
    fun outputLargerThanTheBatchCapIsSplitWithoutLoss() = runTest {
        val consumer = Consumer()
        val pump = pump(consumer)
        val generation = pump.resetGeneration()

        // 200 KiB in one frame plus a tail frame: more than three full batches.
        val big = ByteArray(200 * 1024) { (it % 251).toByte() }
        val tail = ByteArray(1_000) { 7 }
        generation.enqueue(big)
        generation.enqueue(tail)
        advanceUntilIdle()

        assertTrue(consumer.batches.all { it.size <= TerminalOutputPump.MAX_BATCH_BYTES })
        assertTrue(consumer.batches.size >= 4)
        assertTrue(big.plus(tail).contentEquals(consumer.bytes()))
        pump.close()
    }

    @Test
    fun generationResetDropsQueuedBytesAndRunsThePrologueFirst() = runTest {
        val consumer = Consumer()
        val pump = pump(consumer)
        val old = pump.resetGeneration()
        old.enqueue("stale output".toByteArray())
        // Not drained yet: the consumer is parked until the dispatcher advances.

        val events = mutableListOf<String>()
        val fresh = pump.resetGeneration(prologue = { events += "reset" })
        fresh.enqueue("fresh".toByteArray())
        advanceUntilIdle()

        assertEquals("fresh", consumer.text())
        assertEquals(listOf("reset"), events)
        // The retired handle can no longer reach the emulator.
        assertFalse(old.enqueue("late".toByteArray()))
        advanceUntilIdle()
        assertEquals("fresh", consumer.text())
        pump.close()
    }

    @Test
    fun clearedGenerationAcceptsNothingAndDropsPendingBytes() = runTest {
        val consumer = Consumer()
        val pump = pump(consumer)
        val generation = pump.resetGeneration()
        generation.enqueue("queued".toByteArray())

        pump.clearGeneration()
        advanceUntilIdle()

        assertEquals("", consumer.text())
        assertFalse(generation.enqueue("after".toByteArray()))
        advanceUntilIdle()
        assertEquals("", consumer.text())
        pump.close()
    }

    @Test
    fun closeStopsTheConsumerAndRefusesFurtherOutput() = runTest {
        val consumer = Consumer()
        val pump = pump(consumer)
        val generation = pump.resetGeneration()
        generation.enqueue("before close".toByteArray())

        pump.close()
        advanceUntilIdle()

        assertEquals("", consumer.text())
        assertFalse(generation.enqueue("after close".toByteArray()))
        advanceUntilIdle()
        assertEquals("", consumer.text())
        // Idempotent.
        pump.close()
    }

    @Test
    fun pendingBoundFailsTheGenerationInsteadOfDroppingBytesSilently() = runTest {
        val consumer = Consumer()
        val counters = PerformanceCounters()
        val pump = pump(consumer, counters, maxPendingBytes = 4_096)
        var overflows = 0
        val generation = pump.resetGeneration(onFailed = { overflows++ })

        val chunk = ByteArray(1_024)
        repeat(4) { assertTrue(generation.enqueue(chunk)) }
        // The fifth chunk cannot fit: the generation fails loudly.
        assertFalse(generation.enqueue(chunk))
        assertEquals(1, overflows)

        advanceUntilIdle()
        // Nothing half-delivered: the failed generation's queue went with it.
        assertEquals(0, consumer.batches.size)
        assertFalse(generation.enqueue(chunk))
        assertEquals(1, overflows)

        val snapshot = counters.snapshot().terminal
        assertEquals(1L, snapshot.queueOverflows)
        assertEquals(4_096L, snapshot.maxPendingBytes)

        // A fresh generation recovers.
        val fresh = pump.resetGeneration()
        assertTrue(fresh.enqueue("recovered".toByteArray()))
        advanceUntilIdle()
        assertEquals("recovered", consumer.text())
        pump.close()
    }

    @Test
    fun aFailingBatchRetiresItsGenerationWithoutKillingTheConsumer() = runTest {
        val delivered = mutableListOf<String>()
        var failure: TerminalOutputFailure? = null
        val pump = TerminalOutputPump(scope = CoroutineScope(coroutineContext)) { batch ->
            val text = batch.toString(Charsets.UTF_8)
            if (text.contains("boom")) throw IllegalStateException("emulator refused the batch")
            delivered += text
        }
        val generation = pump.resetGeneration(onFailed = { failure = it })

        generation.enqueue("boom".toByteArray())
        advanceUntilIdle()
        assertEquals(TerminalOutputFailure.DELIVERY_FAILED, failure)
        // The dead generation accepts nothing more.
        assertFalse(generation.enqueue("ignored".toByteArray()))

        // The consumer survived: a fresh generation still delivers.
        val fresh = pump.resetGeneration()
        fresh.enqueue("still working".toByteArray())
        advanceUntilIdle()
        assertEquals(listOf("still working"), delivered)
        pump.close()
    }

    @Test
    fun aFloodOfTinyFramesTripsTheQueueDepthBoundBeforeTheByteBound() = runTest {
        val consumer = Consumer()
        var failure: TerminalOutputFailure? = null
        val pump = TerminalOutputPump(
            scope = CoroutineScope(coroutineContext),
            // A byte bound this large could never be reached by one-byte frames; the depth bound
            // is what keeps per-entry overhead finite.
            maxPendingBytes = 64 * 1024 * 1024,
            maxPendingChunks = 16,
        ) { consumer.batches += it }
        val generation = pump.resetGeneration(onFailed = { failure = it })

        val single = ByteArray(1) { 'x'.code.toByte() }
        repeat(16) { assertTrue(generation.enqueue(single)) }
        assertFalse(generation.enqueue(single))
        assertEquals(TerminalOutputFailure.QUEUE_OVERFLOW, failure)
        pump.close()
    }

    @Test
    fun aLoneSmallChunkIsDeliveredImmediatelyWithoutWaitingForMore() = runTest {
        val consumer = Consumer()
        val pump = pump(consumer)
        val generation = pump.resetGeneration()

        generation.enqueue("x".toByteArray())
        advanceUntilIdle()

        assertEquals(1, consumer.batches.size)
        assertEquals("x", consumer.text())
        pump.close()
    }
}
