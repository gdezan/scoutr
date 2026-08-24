package dev.scoutr.app.state

import dev.scoutr.app.data.ExposureKind
import dev.scoutr.app.net.HostClientFactory
import dev.scoutr.app.net.HostConnectionBinding
import dev.scoutr.app.net.HostIdentityChangedException
import dev.scoutr.app.net.HostIncompatibleException
import dev.scoutr.app.net.HostWorkCoordinator
import dev.scoutr.app.net.ScoutrApi
import dev.scoutr.app.net.TerminalTransport
import dev.scoutr.app.net.TopologyFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun binding(hostId: String, revision: Long = 1L) = HostConnectionBinding(
    hostId = hostId,
    connectionRevision = revision,
    baseUrl = "https://$hostId.example",
    token = "token-$hostId",
    exposure = ExposureKind.Custom,
)

/** Minimal [HostClientFactory] whose per-host APIs are [dev.scoutr.app.net.FakeScoutrApi]. */
private class StatusFakeHostClients : HostClientFactory {
    val apis = mutableMapOf<String, dev.scoutr.app.net.FakeScoutrApi>()

    fun apiFor(hostId: String): dev.scoutr.app.net.FakeScoutrApi =
        apis.getOrPut(hostId) { dev.scoutr.app.net.FakeScoutrApi() }

    override fun api(hostId: String): ScoutrApi = apiFor(hostId)

    override fun terminal(hostId: String): TerminalTransport =
        error("terminal transport is not used by HostStatusRepository")

    override fun topologyFeedFactory(hostId: String): TopologyFeed.Factory =
        error("topology feeds are not used by HostStatusRepository")

    override fun probe(host: String, token: String): ScoutrApi =
        error("pairing probes are not used by HostStatusRepository")
}

class HostStatusRepositoryTest {
    private val clients = StatusFakeHostClients()
    private val work = HostWorkCoordinator()
    private val bindings = mutableMapOf(
        "host-a" to binding("host-a"),
        "host-b" to binding("host-b"),
    )

    private var nowMs = 1_000L

    private fun repository() = HostStatusRepository(
        clients = clients,
        bindingFor = { bindings[it] },
        work = work,
        clock = { nowMs },
    )

    private fun admit(binding: HostConnectionBinding) = work.activate(binding)

    @Test
    fun successful_probe_records_online_with_check_time() = runTest {
        admit(binding("host-a"))
        val repository = repository()
        nowMs = 5_000L

        val outcome = repository.probe("host-a")

        assertEquals(HostAvailability.Online(5_000L), repository.status("host-a"))
        assertEquals(HostAvailability.Online(5_000L), outcome)
    }

    @Test
    fun network_failure_keeps_previous_success_time_and_message() = runTest {
        admit(binding("host-a"))
        val repository = repository()
        nowMs = 5_000L
        repository.probe("host-a")
        nowMs = 9_000L
        clients.apiFor("host-a").healthResult =
            Result.failure(java.io.IOException("bridge unreachable"))

        repository.probe("host-a")

        assertEquals(
            HostAvailability.Offline(lastSuccessAtMs = 5_000L, message = "bridge unreachable"),
            repository.status("host-a"),
        )
    }

    @Test
    fun missing_required_feature_classifies_incompatible_without_registry_change() = runTest {
        admit(binding("host-a"))
        val repository = repository()
        clients.apiFor("host-a").healthResult =
            Result.failure(HostIncompatibleException("host-a"))

        repository.probe("host-a")

        assertTrue(repository.status("host-a") is HostAvailability.Incompatible)
    }

    @Test
    fun mismatched_reported_identity_is_classified_with_the_reported_host_id() = runTest {
        admit(binding("host-a"))
        val repository = repository()
        clients.apiFor("host-a").healthResult =
            Result.failure(HostIdentityChangedException("host-a", "host-rogue"))

        repository.probe("host-a")

        assertEquals(HostAvailability.IdentityChanged("host-rogue"), repository.status("host-a"))
    }

    @Test
    fun retired_binding_discards_the_response_and_writes_nothing() = runTest {
        val activeBinding = binding("host-a")
        admit(activeBinding)
        val repository = repository()
        val api = clients.apiFor("host-a")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.gates["health"] = gate

        val probe = async {
            try {
                repository.probe("host-a")
                null
            } catch (thrown: Throwable) {
                thrown
            }
        }
        while (api.calls.none { it.name == "health" }) {
            kotlinx.coroutines.yield()
        }
        work.retire(activeBinding)

        // Retirement surfaces as a discarded (null) probe, never as a status write.
        assertNull(probe.await())
        assertEquals(HostAvailability.Unknown, repository.status("host-a"))
    }

    @Test
    fun inactive_binding_at_admission_discards_the_probe() = runTest {
        val repository = repository()

        assertNull(repository.probe("host-a"))
        assertEquals(HostAvailability.Unknown, repository.status("host-a"))
    }

    @Test
    fun concurrent_probes_for_one_binding_are_coalesced_into_one_health_call() = runTest {
        admit(binding("host-a"))
        val repository = repository()
        val api = clients.apiFor("host-a")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.gates["health"] = gate

        val first = async { repository.probe("host-a") }
        val waiter = async { repository.probe("host-a") }
        while (api.calls.none { it.name == "health" }) {
            kotlinx.coroutines.yield()
        }
        kotlinx.coroutines.yield() // let the waiter reach its coalesced await

        assertEquals(1, api.calls.count { it.name == "health" })
        gate.complete(Unit)
        first.await()
        waiter.await()
        assertEquals(HostAvailability.Online(nowMs), repository.status("host-a"))
    }

    @Test
    fun different_hosts_probe_independently() = runTest {
        admit(binding("host-a"))
        admit(binding("host-b"))
        val repository = repository()
        clients.apiFor("host-b").healthResult =
            Result.failure(java.io.IOException("down"))

        repository.probe("host-a")
        repository.probe("host-b")

        assertTrue(repository.status("host-a") is HostAvailability.Online)
        assertTrue(repository.status("host-b") is HostAvailability.Offline)
    }

    @Test
    fun unknown_host_records_offline_instead_of_throwing() = runTest {
        val repository = repository()

        assertEquals(
            HostAvailability.Offline(lastSuccessAtMs = null, message = "Host is not available"),
            repository.probe("missing"),
        )
    }

    @Test
    fun remove_drops_all_trace_of_a_host() = runTest {
        admit(binding("host-a"))
        val repository = repository()
        repository.probe("host-a")

        repository.remove("host-a")

        assertEquals(HostAvailability.Unknown, repository.status("host-a"))
        // A later offline probe after removal must not resurrect an old success time.
        bindings.remove("host-a")
        repository.probe("host-a")
        assertEquals(
            HostAvailability.Offline(lastSuccessAtMs = null, message = "Host is not available"),
            repository.status("host-a"),
        )
    }
}
