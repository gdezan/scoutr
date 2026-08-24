package dev.scoutr.app.state

import android.content.Context
import dev.scoutr.app.data.HostPaneKey
import dev.scoutr.app.data.HostProfileKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A mute must survive the process (it is a decision, not a session), and must
 * not outlive the pane it was about — otherwise a stale id silences a future
 * agent the user never muted, with no settings screen to undo it from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MuteStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(MuteStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun nothingIsMutedByDefault() {
        assertFalse(MuteStore(context).isMuted("w1:p1"))
    }

    @Test
    fun aMutePersistsAcrossInstances() {
        MuteStore(context).mute("w1:p1")

        assertTrue(MuteStore(context).isMuted("w1:p1"))
        assertFalse(MuteStore(context).isMuted("w1:p2"))
    }

    @Test
    fun mutingIsIdempotent() {
        val store = MuteStore(context)
        store.mute("w1:p1")
        store.mute("w1:p1")

        assertTrue(store.isMuted("w1:p1"))
    }

    @Test
    fun hostGenerationAndPaneArePartOfMuteIdentity() {
        val store = MuteStore(context)
        val first = HostPaneKey(HostProfileKey("host-a", 1), "same-pane")
        val otherHost = HostPaneKey(HostProfileKey("host-b", 1), "same-pane")
        val repaired = HostPaneKey(HostProfileKey("host-a", 2), "same-pane")

        store.mute(first)

        assertTrue(store.isMuted(first))
        assertFalse(store.isMuted(otherHost))
        assertFalse(store.isMuted(repaired))
        store.clearHost("host-a")
        assertFalse(store.isMuted(first))

    }

    @Test
    fun legacyMuteIsAdoptedIntoMigratedHostGeneration() {
        val store = MuteStore(context)
        val profile = HostProfileKey("host-a", 7)
        val adopted = HostPaneKey(profile, "w1:p1")
        store.mute("w1:p1")

        store.adoptLegacyMutes(profile)

        assertFalse(store.isMuted("w1:p1"))
        assertTrue(store.isMuted(adopted))
        store.clearHost("host-a")
        assertFalse(store.isMuted(adopted))
    }

    @Test
    fun pruneDropsPanesThatAreNoLongerLive() {
        val store = MuteStore(context)
        store.mute("w1:p1")
        store.mute("w1:p2")

        store.prune(setOf("w1:p2"))

        assertFalse("a closed pane's mute must not outlive it", store.isMuted("w1:p1"))
        assertTrue(store.isMuted("w1:p2"))
        assertFalse(MuteStore(context).isMuted("w1:p1"))
    }

    @Test
    fun pruneWithNoLivePanesClearsEverything() {
        val store = MuteStore(context)
        store.mute("w1:p1")

        store.prune(emptySet())

        assertFalse(store.isMuted("w1:p1"))
    }
}
