package dev.scoutr.app.net

import dev.scoutr.app.data.ConnectionStore
import okhttp3.OkHttpClient

/** Test-only adapter for fixtures that still seed the legacy singleton preferences. */
@Suppress("FunctionName")
fun BridgeClient(
    okHttp: OkHttpClient,
    connectionStore: ConnectionStore,
    performanceCounters: PerformanceCounters? = null,
): BridgeClient {
    val saved = requireNotNull(connectionStore.saved) { "no connection configured" }
    return BridgeClient(
        okHttp = okHttp,
        binding = HostConnectionBinding(
            hostId = saved.hostId ?: "legacy-test-host",
            connectionRevision = 0,
            baseUrl = saved.host,
            token = saved.token,
            exposure = saved.exposure,
        ),
        performanceCounters = performanceCounters,
    )
}
