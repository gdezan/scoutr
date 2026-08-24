package dev.scoutr.app.state

import android.os.Looper
import dev.scoutr.app.data.HostProfileKey
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** JVM (Robolectric) wiring for the shared [BoardHarness]. */
fun jvmBoardHarness(clock: () -> Long = { 1_000L }) =
    BoardHarness(RuntimeEnvironment.getApplication(), clock)

/** Robolectric main-looper helpers shared by the Board VM test classes. */
object BoardTestLoop {
    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
            idle()
        }
        assertTrue("condition did not become true before timeout", condition())
    }
}

/** Convenience for assertions that name a row's owning host. */
fun BoardUiState.sessionsFor(hostId: String) = hostBoards[hostId]?.sessions.orEmpty()

fun BoardUiState.profileKeyOf(hostId: String): HostProfileKey? = profiles[hostId]

/** In-memory pin/archive catalog store for JVM tests. */
class RecordingSessionCatalogStore : dev.scoutr.app.data.SessionCatalogStore {
    val pinned = mutableSetOf<dev.scoutr.app.data.SessionKey>()
    val archived = mutableSetOf<dev.scoutr.app.data.SessionKey>()

    override fun pinnedKeys(catalogKeys: Collection<dev.scoutr.app.data.HostSessionKey>) =
        pinned.mapTo(mutableSetOf()) { key ->
            dev.scoutr.app.data.HostSessionKey(
                catalogKeys.firstOrNull()?.hostId ?: "legacy-singleton",
                key,
            )
        }

    override fun archivedKeys(catalogKeys: Collection<dev.scoutr.app.data.HostSessionKey>) =
        archived.mapTo(mutableSetOf()) { key ->
            dev.scoutr.app.data.HostSessionKey(
                catalogKeys.firstOrNull()?.hostId ?: "legacy-singleton",
                key,
            )
        }

    override fun setPinned(key: dev.scoutr.app.data.HostSessionKey, pinned: Boolean) {
        if (pinned) this.pinned.add(key.session) else this.pinned.remove(key.session)
    }

    override fun setArchived(key: dev.scoutr.app.data.HostSessionKey, archived: Boolean) {
        if (archived) this.archived.add(key.session) else this.archived.remove(key.session)
    }

    override fun adoptLegacyEntries(hostId: String, catalogKeys: Collection<dev.scoutr.app.data.SessionKey>) = Unit
    override fun copyRetainedMetadata(fromHostId: String, toHostId: String, confirmed: Boolean) = Unit
}
