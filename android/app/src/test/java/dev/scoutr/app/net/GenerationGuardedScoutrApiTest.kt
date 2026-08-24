package dev.scoutr.app.net

import android.content.Context
import dev.scoutr.app.data.FakeConnectionCipher
import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.HostRegistryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationGuardedScoutrApiTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val cipher = FakeConnectionCipher()

    @Before
    fun clearRegistry() {
        context.getSharedPreferences(HostRegistryStore.FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun stale_profile_is_rejected_before_delegate_dispatch_after_repair() = runBlocking {
        val registry = HostRegistryStore(context, cipher)
        val first = registry.addOrRefresh("bridge-a", "https://a.example", "token-a")
        val fake = FakeScoutrApi()
        val api = GenerationGuardedScoutrApi(
            registry,
            HostProfileKey(first.hostId, first.profileGeneration),
            fake,
        )

        api.agents()
        assertEquals(1, fake.calls.count { it.name == "agents" })

        registry.forget("bridge-a")
        registry.completePendingCleanup("bridge-a")
        val repaired = registry.addOrRefresh("bridge-a", "https://a.example", "token-b")
        assert(repaired.profileGeneration > first.profileGeneration)

        assertThrows(StaleHostRouteException::class.java) { runBlocking { api.agents() } }
        assertEquals("stale route must not reach the delegate", 1, fake.calls.count { it.name == "agents" })
    }
}
