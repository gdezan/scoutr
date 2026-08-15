package dev.scoutr.app.net

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local counters for repeatable performance experiments.
 *
 * Nothing is persisted or uploaded. Endpoint labels are normalized before
 * storage so ids and query values cannot become metric dimensions.
 */
class PerformanceCounters {
    /** Process-local WebSocket lifecycle counts for the dedicated bridge surfaces. */
    data class SocketSnapshot(
        val opened: Long,
        val closed: Long,
        val active: Long,
    )

    data class EndpointSnapshot(
        val requests: Long,
        val responses: Long,
        val failures: Long,
        val cancellations: Long,
        val errorResponses: Long,
        val responseBytes: Long,
        val totalDurationMs: Long,
        val statuses: Map<Int, Long>,
    )
    /**
     * Chat refresh coordinator activity. [started] is one authoritative read
     * per pane; [joined] counts triggers that waited for that read instead of
     * starting their own; [startedBySource] attributes the reads to their
     * trigger; [inFlightCancelled] counts reads cancelled by STOPPED.
     */
    data class ChatRefreshSnapshot(
        val started: Long,
        val joined: Long,
        val startedBySource: Map<String, Long>,
        val pullsAttempted: Long,
        val pullsCompleted: Long,
        val pullsSucceeded: Long,
        val inFlightCancelled: Long,
    )
    data class Snapshot(
        val activeHttpRequests: Int,
        val httpRequests: Long,
        val httpResponses: Long,
        val httpFailures: Long,
        val httpCancellations: Long,
        val httpErrorResponses: Long,
        val responseBytes: Long,
        val totalDurationMs: Long,
        val endpoints: Map<String, EndpointSnapshot>,
        val feedSocket: SocketSnapshot,
        val terminalSocket: SocketSnapshot,
        val chatRefresh: ChatRefreshSnapshot,
    )

    internal class EndpointCounters {
        val requests = AtomicLong()
        val responses = AtomicLong()
        val failures = AtomicLong()
        val cancellations = AtomicLong()
        val errorResponses = AtomicLong()
        val responseBytes = AtomicLong()
        val totalDurationMs = AtomicLong()
        val statuses = ConcurrentHashMap<Int, AtomicLong>()

        fun snapshot() = EndpointSnapshot(
            requests = requests.get(),
            responses = responses.get(),
            failures = failures.get(),
            cancellations = cancellations.get(),
            errorResponses = errorResponses.get(),
            responseBytes = responseBytes.get(),
            totalDurationMs = totalDurationMs.get(),
            statuses = statuses.entries.associate { (status, count) -> status to count.get() },
        )
    }

    internal class SocketCounters(
        val opened: AtomicLong,
        val closed: AtomicLong,
        val active: AtomicLong,
    )

    internal class ChatRefreshCounters {
        val started = AtomicLong()
        val joined = AtomicLong()
        val startedBySource = ConcurrentHashMap<String, AtomicLong>()
        val pullsAttempted = AtomicLong()
        val pullsCompleted = AtomicLong()
        val pullsSucceeded = AtomicLong()
        val inFlightCancelled = AtomicLong()

        fun snapshot() = ChatRefreshSnapshot(
            started = started.get(),
            joined = joined.get(),
            startedBySource = startedBySource.entries.associate { (source, count) -> source to count.get() },
            pullsAttempted = pullsAttempted.get(),
            pullsCompleted = pullsCompleted.get(),
            pullsSucceeded = pullsSucceeded.get(),
            inFlightCancelled = inFlightCancelled.get(),
        )

        fun reset() {
            started.set(0)
            joined.set(0)
            startedBySource.clear()
            pullsAttempted.set(0)
            pullsCompleted.set(0)
            pullsSucceeded.set(0)
            inFlightCancelled.set(0)
        }
    }
    private val activeHttpRequests = AtomicInteger()
    private val httpRequests = AtomicLong()
    private val httpResponses = AtomicLong()
    private val httpFailures = AtomicLong()
    private val httpCancellations = AtomicLong()
    private val httpErrorResponses = AtomicLong()
    private val responseBytes = AtomicLong()
    private val totalDurationMs = AtomicLong()
    private val endpoints = ConcurrentHashMap<String, EndpointCounters>()
    private val feedSocketCounters = SocketCounters(AtomicLong(), AtomicLong(), AtomicLong())
    private val terminalSocketCounters = SocketCounters(AtomicLong(), AtomicLong(), AtomicLong())
    private val chatRefreshCounters = ChatRefreshCounters()

    /** Begin one BridgeClient request; the returned handle is safe to settle once. */
    fun beginHttpRequest(path: String): HttpRequest? {
        val endpoint = normalizeEndpoint(path) ?: return null
        val endpointCounters = endpoints.computeIfAbsent(endpoint) { EndpointCounters() }
        activeHttpRequests.incrementAndGet()
        httpRequests.incrementAndGet()
        endpointCounters.requests.incrementAndGet()
        return HttpRequest(endpointCounters)
    }

    inner class HttpRequest internal constructor(
        private val endpointCounters: EndpointCounters,
    ) {
        private val settled = AtomicBoolean(false)
        private val startedAtNanos = System.nanoTime()

        /** Record an HTTP response and its serialized body size. */
        fun complete(status: Int, bodyBytes: Long) {
            if (!settled.compareAndSet(false, true)) return
            val bytes = bodyBytes.coerceAtLeast(0)
            val durationMs = elapsedMillis()
            activeHttpRequests.updateAndGet { it.dec().coerceAtLeast(0) }
            httpResponses.incrementAndGet()
            responseBytes.addAndGet(bytes)
            totalDurationMs.addAndGet(durationMs)
            endpointCounters.responses.incrementAndGet()
            endpointCounters.responseBytes.addAndGet(bytes)
            endpointCounters.totalDurationMs.addAndGet(durationMs)
            endpointCounters.statuses.computeIfAbsent(status) { AtomicLong() }.incrementAndGet()
            if (status >= 400) {
                httpErrorResponses.incrementAndGet()
                endpointCounters.errorResponses.incrementAndGet()
            }
        }

        /** Record a transport failure or cancellation. Repeated settlement is ignored. */
        fun fail(cancelled: Boolean) {
            if (!settled.compareAndSet(false, true)) return
            activeHttpRequests.updateAndGet { it.dec().coerceAtLeast(0) }
            if (cancelled) {
                httpCancellations.incrementAndGet()
                endpointCounters.cancellations.incrementAndGet()
            } else {
                httpFailures.incrementAndGet()
                endpointCounters.failures.incrementAndGet()
            }
        }

        private fun elapsedMillis(): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0)
    }

    /** Record a WebSocket opening and return an idempotent close callback. */
    fun beginSocket(kind: SocketKind): SocketHandle {
        val counters = when (kind) {
            SocketKind.Feed -> feedSocketCounters
            SocketKind.Terminal -> terminalSocketCounters
        }
        counters.opened.incrementAndGet()
        counters.active.incrementAndGet()
        return SocketHandle(counters)
    }

    enum class SocketKind { Feed, Terminal }

    inner class SocketHandle internal constructor(
        private val counters: SocketCounters,
    ) {
        private val closed = AtomicBoolean(false)

        /** Record one WebSocket close; duplicate error/close callbacks are ignored. */
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            counters.closed.incrementAndGet()
            counters.active.updateAndGet { it.dec().coerceAtLeast(0) }
        }
    }
    /** One authoritative Chat read began (the single-flight owner). */
    fun beginChatRefresh(source: String) {
        chatRefreshCounters.started.incrementAndGet()
        chatRefreshCounters.startedBySource.computeIfAbsent(source) { AtomicLong() }.incrementAndGet()
    }

    /** A trigger joined an already-running Chat read instead of racing it. */
    fun joinChatRefresh(source: String) {
        chatRefreshCounters.joined.incrementAndGet()
    }

    /** A pull-to-refresh gesture was requested. */
    fun chatPullAttempted() {
        chatRefreshCounters.pullsAttempted.incrementAndGet()
    }

    /** A pull's refresh settled; [success] says whether it read the pane. */
    fun chatPullCompleted(success: Boolean) {
        chatRefreshCounters.pullsCompleted.incrementAndGet()
        if (success) chatRefreshCounters.pullsSucceeded.incrementAndGet()
    }

    /** An in-flight Chat read was cancelled (the screen reached STOPPED). */
    fun chatRefreshCancelled() {
        chatRefreshCounters.inFlightCancelled.incrementAndGet()
    }

    fun snapshot(): Snapshot = Snapshot(
        activeHttpRequests = activeHttpRequests.get(),
        httpRequests = httpRequests.get(),
        httpResponses = httpResponses.get(),
        httpFailures = httpFailures.get(),
        httpCancellations = httpCancellations.get(),
        httpErrorResponses = httpErrorResponses.get(),
        responseBytes = responseBytes.get(),
        totalDurationMs = totalDurationMs.get(),
        endpoints = endpoints.entries.associate { (endpoint, counters) -> endpoint to counters.snapshot() },
        feedSocket = feedSocketCounters.snapshot(),
        terminalSocket = terminalSocketCounters.snapshot(),
        chatRefresh = chatRefreshCounters.snapshot(),
    )

    /** Reset a completed experiment; active HTTP or WebSocket work must be settled first. */
    fun reset() {
        check(activeHttpRequests.get() == 0) { "cannot reset performance counters with active requests" }
        check(feedSocketCounters.active.get() == 0L && terminalSocketCounters.active.get() == 0L) {
            "cannot reset performance counters with active sockets"
        }
        httpRequests.set(0)
        httpResponses.set(0)
        httpFailures.set(0)
        httpCancellations.set(0)
        httpErrorResponses.set(0)
        responseBytes.set(0)
        totalDurationMs.set(0)
        endpoints.clear()
        for (counters in listOf(feedSocketCounters, terminalSocketCounters)) {
            counters.opened.set(0)
            counters.closed.set(0)
        }
        chatRefreshCounters.reset()
    }

    companion object {
        private const val MAX_ENDPOINT_LENGTH = 96

        /** Keep metric labels route-shaped and free of user-controlled values. */
        internal fun normalizeEndpoint(path: String): String? {
            val cleanPath = path.substringBefore('?')
            return when {
                cleanPath == "/ws" || cleanPath == "/ws/terminal" -> cleanPath
                cleanPath.startsWith("/api/sessions/") -> "/api/sessions/:paneId"
                cleanPath.startsWith("/api/session-catalog/") -> "/api/session-catalog/:action"
                cleanPath.startsWith("/api/repo/") -> "/api/repo/:operation"
                cleanPath.startsWith("/api/") -> cleanPath.takeIf { it.length <= MAX_ENDPOINT_LENGTH }
                else -> "/other"
            }
        }
    }
}

private fun PerformanceCounters.SocketCounters.snapshot(): PerformanceCounters.SocketSnapshot =
    PerformanceCounters.SocketSnapshot(
        opened = opened.get(),
        closed = closed.get(),
        active = active.get(),
    )
