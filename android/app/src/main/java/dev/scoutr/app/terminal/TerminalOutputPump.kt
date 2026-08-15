package dev.scoutr.app.terminal

import dev.scoutr.app.net.PerformanceCounters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bounded producer/consumer seam between the terminal WebSocket and the emulator.
 *
 * The transport callback thread only [Generation.enqueue]s bytes and signals one wake; a single
 * long-lived consumer coroutine on the terminal dispatcher drains everything already available
 * into one contiguous batch (capped at [MAX_BATCH_BYTES]) and hands it to [consume]. Bursty output
 * therefore costs one emulator append and one screen update per drained batch instead of one per
 * WebSocket frame, without any artificial render delay: a lone small frame wakes the consumer
 * immediately and is delivered on its own.
 *
 * Batching emerges from draining, never from a timer (see docs/performance-study.md, "terminal
 * render batching").
 *
 * Ordering and generation safety:
 *  - bytes are delivered in exact enqueue order, byte for byte;
 *  - a generation is a [Generation] handle, and only the handle installed by the last
 *    [resetGeneration] is live: bytes offered through a superseded handle are refused rather than
 *    filtered downstream, so an obsolete socket cannot reach a newer emulator;
 *  - [resetGeneration] drops every byte still queued for the previous generation and runs the
 *    caller's `prologue` (the emulator reset) on the consumer thread before the first batch of the
 *    new generation, so the reset and the bytes it must precede cannot be reordered.
 *
 * Failure is explicit, never silent: when a generation's pending output exceeds its bounds, or
 * delivering one of its batches throws, the generation is retired through its `onFailed` callback
 * and its queue is dropped. That mirrors the bridge's slow-client policy — recover through a fresh
 * generation, which replays the screen — instead of quietly discarding output inside a live
 * generation or leaving the single consumer dead.
 */
class TerminalOutputPump(
    scope: CoroutineScope,
    private val counters: PerformanceCounters? = null,
    private val maxBatchBytes: Int = MAX_BATCH_BYTES,
    private val maxPendingBytes: Int = MAX_PENDING_BYTES,
    private val maxPendingChunks: Int = MAX_PENDING_CHUNKS,
    private val consume: (ByteArray) -> Unit,
) {
    /**
     * Handle for one terminal generation. The transport listener holds the handle it was opened
     * with, so a stale socket's [enqueue] is refused by identity instead of by a generation
     * comparison the caller could forget.
     */
    inner class Generation internal constructor(
        internal val onFailed: (TerminalOutputFailure) -> Unit,
        internal var prologue: (() -> Unit)?,
    ) {
        /**
         * Offer transport bytes for this generation. Returns false when the generation is no
         * longer live (superseded, failed, or the pump is closed) or when the offer overflowed the
         * pending bound — in which case `onFailed` has been invoked and this generation is dead.
         * Safe to call from the transport callback thread.
         */
        fun enqueue(bytes: ByteArray): Boolean = enqueueFor(this, bytes)
    }

    private val lock = Any()

    /** Queued chunks for [current], in arrival order. Guarded by [lock]. */
    private val queue = ArrayDeque<ByteArray>()

    /** Bytes of [queue] head already delivered in an earlier batch. Guarded by [lock]. */
    private var headOffset = 0

    /** Bytes queued but not yet consumed. Guarded by [lock]. */
    private var pendingBytes = 0

    /** The only generation allowed to enqueue or be consumed. Guarded by [lock]. */
    private var current: Generation? = null

    /** Guarded by [lock]. */
    private var closed = false

    /** Conflated: many enqueues collapse into one wake, which is what produces the batching. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val consumerJob: Job = scope.launch {
        try {
            while (isActive) {
                wake.receive()
                drainAll()
            }
        } catch (_: ClosedReceiveChannelException) {
            // close() raced the consumer between cancellation and the next receive.
        }
    }

    /**
     * Replace the live generation: drop everything still queued for the previous one and install a
     * fresh handle. [prologue] runs on the consumer thread before the new generation's first batch
     * (the emulator reset); [onFailed] retires the generation when its queue is exhausted or when
     * delivering one of its batches threw.
     */
    fun resetGeneration(
        onFailed: (TerminalOutputFailure) -> Unit = {},
        prologue: (() -> Unit)? = null,
    ): Generation {
        val generation = synchronized(lock) {
            check(!closed) { "terminal output pump is closed" }
            clearQueueLocked()
            Generation(onFailed, prologue).also { current = it }
        }
        wake.trySend(Unit)
        return generation
    }

    /**
     * Retire the live generation without installing a new one: queued bytes are dropped and later
     * offers from the retired handle are refused. Used when the socket ends without a replacement
     * (release, close, failure).
     */
    fun clearGeneration() {
        synchronized(lock) {
            if (closed) return
            current = null
            clearQueueLocked()
        }
    }

    /** Stop the consumer and drop pending work; further offers are refused. Idempotent. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            current = null
            clearQueueLocked()
        }
        consumerJob.cancel()
        wake.close()
    }

    /** Test/diagnostic seam: bytes queued but not yet consumed. */
    internal fun pendingBytes(): Int = synchronized(lock) { pendingBytes }

    private fun enqueueFor(generation: Generation, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val overflowed = synchronized(lock) {
            if (closed || current !== generation) return false
            // Both bounds matter: bytes for ordinary frames, depth because a flood of tiny
            // frames costs per-entry overhead the byte figure does not account for.
            if (pendingBytes + bytes.size > maxPendingBytes || queue.size >= maxPendingChunks) {
                // Explicit failure, never a silent drop: kill the generation here so no
                // half-delivered prefix reaches the emulator, and let the owner rebuild.
                current = null
                clearQueueLocked()
                true
            } else {
                queue.addLast(bytes)
                pendingBytes += bytes.size
                counters?.terminalPendingBytes(pendingBytes)
                false
            }
        }
        if (overflowed) {
            counters?.terminalQueueOverflow()
            generation.onFailed(TerminalOutputFailure.QUEUE_OVERFLOW)
            return false
        }
        wake.trySend(Unit)
        return true
    }

    /**
     * Retire [generation] if it is still live and report why. Used for failures the owner must
     * recover from with a fresh generation.
     */
    private fun failGeneration(generation: Generation, failure: TerminalOutputFailure) {
        val live = synchronized(lock) {
            if (current !== generation) return
            current = null
            clearQueueLocked()
            true
        }
        if (live) generation.onFailed(failure)
    }

    /** Guarded by [lock]. */
    private fun clearQueueLocked() {
        queue.clear()
        headOffset = 0
        pendingBytes = 0
    }

    private fun drainAll() {
        while (true) {
            val work = nextWork() ?: return
            // Collapsing N launches into one consumer means one thrown batch would otherwise end
            // terminal output for the ViewModel's lifetime. Contain it: retire the generation and
            // let the owner rebuild, exactly like an overflow.
            try {
                when (work) {
                    is Work.Prologue -> work.run()
                    is Work.Batch -> {
                        counters?.terminalOutputBatch(work.bytes.size)
                        consume(work.bytes)
                    }
                }
            } catch (error: Throwable) {
                // Cancellation is the pump shutting down. A VirtualMachineError must not become a
                // reconnect: replaying the screen is the most allocation-heavy response available,
                // which is the worst possible answer to an OutOfMemoryError.
                if (error is CancellationException || error is VirtualMachineError) throw error
                counters?.terminalDeliveryFailure()
                failGeneration(work.generation, TerminalOutputFailure.DELIVERY_FAILED)
            }
        }
    }

    private sealed interface Work {
        val generation: Generation

        class Prologue(override val generation: Generation, val run: () -> Unit) : Work
        class Batch(override val generation: Generation, val bytes: ByteArray) : Work
    }

    /**
     * Take the next unit of consumer work: a pending generation prologue first, otherwise one
     * contiguous batch of at most [maxBatchBytes] drained from the queue.
     */
    private fun nextWork(): Work? = synchronized(lock) {
        if (closed) return null
        val generation = current ?: return null
        generation.prologue?.let { prologue ->
            generation.prologue = null
            return Work.Prologue(generation, prologue)
        }
        if (queue.isEmpty()) return null

        val head = queue.first()
        if (headOffset == 0 && queue.size == 1 && head.size <= maxBatchBytes) {
            // One whole transport frame and nothing behind it — the interactive case. Hand over
            // the array the socket already allocated instead of copying it.
            queue.removeFirst()
            pendingBytes -= head.size
            return Work.Batch(generation, head)
        }

        // Otherwise size the batch first, then copy once into it. Sizing walks the queue without
        // allocating anything per chunk; a chunk larger than the cap is split across batches,
        // keeping order exact.
        var total = 0
        var skipped = headOffset
        for (chunk in queue) {
            total += minOf(chunk.size - skipped, maxBatchBytes - total)
            skipped = 0
            if (total >= maxBatchBytes) break
        }

        // Invariant across the sizing and copying loops: no queued chunk is empty (enqueue
        // rejects empty arrays) and headOffset is always inside the head, so total > 0 here. If
        // that ever stopped holding, returning null parks the consumer rather than spinning on
        // empty batches.
        if (total <= 0) return null

        val batch = ByteArray(total)
        var at = 0
        while (at < total) {
            val chunk = queue.first()
            val take = minOf(chunk.size - headOffset, total - at)
            System.arraycopy(chunk, headOffset, batch, at, take)
            at += take
            if (headOffset + take == chunk.size) {
                queue.removeFirst()
                headOffset = 0
            } else {
                headOffset += take
            }
        }
        pendingBytes -= total
        return Work.Batch(generation, batch)
    }

    companion object {
        /**
         * Maximum bytes handed to the emulator in one append.
         *
         * Termux moved its terminal receive buffer from 4 KiB to 64 KiB in February 2026 because
         * the smaller buffer caused serious lag under large-output workloads such as terminal
         * multiplexers. That is the evidence behind this starting point, not a permanent constant:
         * change it from Scoutr's own counter measurements (docs/performance-study.md).
         */
        const val MAX_BATCH_BYTES = 64 * 1024

        /**
         * Maximum bytes queued for one generation before it is failed. Sized as ~64 full batches,
         * an order of magnitude above the 256 KiB outbound input queue in `TerminalSocketClient`
         * and well under the bridge's 512 KiB output pause threshold, so the bridge's own
         * slow-client policy — not this queue — is what a genuinely stalled consumer hits first.
         */
        const val MAX_PENDING_BYTES = 4 * 1024 * 1024

        /**
         * Maximum queued chunks for one generation: a backstop against a flood of very small
         * frames, whose ~24 bytes of per-entry overhead [MAX_PENDING_BYTES] does not account for.
         *
         * Deliberately loose. It must not become a second, tighter bound that retires a healthy
         * generation: a program flushing per short line produces 50–200 byte frames, and 64k of
         * those is still only a few MiB — the byte bound is what should trip there. At this depth
         * the queue's own overhead stays around 1.5 MiB even in the 1-byte-frame worst case.
         */
        const val MAX_PENDING_CHUNKS = 65536
    }
}

/** Why a [TerminalOutputPump] generation was retired; both need a fresh generation to recover. */
enum class TerminalOutputFailure {
    /** Pending output exceeded the queue bounds: the emulator could not keep up with the burst. */
    QUEUE_OVERFLOW,

    /** Delivering a batch to the emulator threw. */
    DELIVERY_FAILED,
}
