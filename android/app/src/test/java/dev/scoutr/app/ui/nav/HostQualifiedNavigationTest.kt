package dev.scoutr.app.ui.nav

import dev.scoutr.app.data.HostProfileKey
import dev.scoutr.app.data.SessionKey
import dev.scoutr.app.data.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostQualifiedNavigationTest {
    private val profile = HostProfileKey("bridge-a", 17)

    @Test
    fun remoteBuildersCarryProfileGenerationAndArguments() {
        val session = SessionKey("pi", "/sessions/a.jsonl")
        assertTrue(AppRoutes.chat(profile, session, "working").contains("hostProfile=${profile.encode()}"))
        assertTrue(AppRoutes.fileBrowser(profile, "/workspace").contains("hostProfile=${profile.encode()}"))
        assertTrue(AppRoutes.fileViewer(profile, "/workspace", "notes.md").contains("hostProfile=${profile.encode()}"))
        assertTrue(AppRoutes.usage(profile).contains("hostProfile=${profile.encode()}"))
        assertTrue(AppRoutes.review(profile, "/workspace").contains("hostProfile=${profile.encode()}"))
        assertTrue(AppRoutes.terminal(profile, "pane-1").contains("hostProfile=${profile.encode()}"))
    }

    @Test
    fun staleOrMalformedRouteProfilesNeverResolve() {
        assertEquals(profile, decodeRouteProfile(profile.encode()))
        assertNull(decodeRouteProfile("hpk1.bad.not-a-generation"))
        assertNull(decodeRouteProfile(""))
        assertNotNull(AppRoutes.bootstrapChat(profile, "pane-1", "working"))
    }

    @Test
    fun pendingLegacyMigrationStartsOnBoardInsteadOfConnect() {
        assertEquals(Destination.Board.route, initialStartDestination(false, true))
        assertEquals(Destination.Board.route, initialStartDestination(true, false))
        assertEquals(AppRoutes.CONNECT, initialStartDestination(false, false))
    }
}
