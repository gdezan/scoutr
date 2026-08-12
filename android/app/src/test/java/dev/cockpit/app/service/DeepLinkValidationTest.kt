package dev.cockpit.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 005 deep-link hardening: pushed ntfy payload is untrusted — the click
 * string must be validated and rebuilt exactly like MainActivity's entry
 * path, never handed to the launcher raw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepLinkValidationTest {

    @Test
    fun evilClickFallsBackToPaneId() {
        val link = resolveNotificationLink(
            click = "https://evil.example/x",
            paneId = "p1",
            status = "working",
        )
        assertEquals("cockpit://chat/p1?status=working", link?.uri)
        assertEquals("p1", link?.paneId)
    }

    @Test
    fun evilClickWithoutPaneIdDropsTheDeepLink() {
        assertNull(resolveNotificationLink(click = "https://evil.example/x", paneId = null, status = "working"))
    }

    @Test
    fun validClickPassesThroughRebuilt() {
        val link = resolveNotificationLink(
            click = "cockpit://chat/p1?status=blocked",
            paneId = null,
            status = "working",
        )
        assertEquals("cockpit://chat/p1?status=blocked", link?.uri)
        assertEquals("p1", link?.paneId)
    }

    @Test
    fun parsedClickWinsOverRawPaneId() {
        // A validated pane must never be overridden by a raw payload field.
        val link = resolveNotificationLink(
            click = "cockpit://chat/p1?status=blocked",
            paneId = "p2",
            status = "working",
        )
        assertEquals("cockpit://chat/p1?status=blocked", link?.uri)
        assertEquals("p1", link?.paneId)
    }

    @Test
    fun noClickFallsBackToPaneIdWithTitleStatus() {
        val link = resolveNotificationLink(
            click = null,
            paneId = "p3",
            status = statusForTitle("Agent needs you"),
        )
        assertEquals("cockpit://chat/p3?status=blocked", link?.uri)
        assertEquals("p3", link?.paneId)
    }

    @Test
    fun parseCockpitUriRejectsForeignSchemes() {
        assertNull(parseCockpitUri("https://evil.example/x"))
        assertNull(parseCockpitUri("cockpit://other/p1"))
        assertEquals("p1", parseCockpitUri("cockpit://chat/p1?status=blocked")?.paneId)
    }

    @Test
    fun serviceTitleDrivesBlockedStatus() {
        assertEquals("blocked", statusForTitle("Agent needs you"))
        assertEquals("working", statusForTitle("Agent finished"))
        assertEquals("working", statusForTitle(null))
    }
}
