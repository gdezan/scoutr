package dev.scoutr.app.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Destination is the single source of truth for the tab routes and the bar check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DestinationTest {

    @Test
    fun routesContainsExactlyTheFourTabRoutes() {
        assertEquals(
            setOf("board", "sessions", "usage", "review"),
            Destination.routes,
        )
    }

    @Test
    fun everyEntryRoundTripsThroughForRoute() {
        for (destination in Destination.entries) {
            assertEquals(destination, Destination.forRoute(destination.route))
        }
    }

    @Test
    fun forRouteRejectsUnknownAndNullRoutes() {
        assertNull(Destination.forRoute("settings"))
        assertNull(Destination.forRoute("chat/w1:p1"))
        assertNull(Destination.forRoute(null))
    }

    @Test
    fun routesSetMatchesEntryCount() {
        assertEquals(Destination.entries.size, Destination.routes.size)
        assertTrue(Destination.entries.all { it.route in Destination.routes })
    }
}
